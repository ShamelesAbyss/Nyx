package com.nyx.ai.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A measured check, not a promise about arbitrary prompts, schemas, or future availability. */
enum class DiagnosticCheck { PASSED, FAILED, NOT_TESTED }

enum class DiagnosticFormatCompatibility {
    OLLAMA_SCHEMA_SAMPLE_PASSED,
    PROMPT_JSON_SAMPLE_PASSED,
    INVALID_RESPONSE,
    NOT_TESTED,
}

data class ModelDiagnosticResult(
    val providerId: String,
    val model: String,
    val providerReachable: DiagnosticCheck = DiagnosticCheck.NOT_TESTED,
    val modelAvailable: DiagnosticCheck = DiagnosticCheck.NOT_TESTED,
    val modelAccessible: DiagnosticCheck = DiagnosticCheck.NOT_TESTED,
    val tinyJsonResponse: DiagnosticCheck = DiagnosticCheck.NOT_TESTED,
    val formatCompatibility: DiagnosticFormatCompatibility = DiagnosticFormatCompatibility.NOT_TESTED,
    val firstResponseLatencyMillis: Long? = null,
    val totalLatencyMillis: Long = 0,
    val stats: InferenceTokenStats? = null,
    val timedOut: Boolean = false,
    val detail: String? = null,
) {
    /** Never includes raw model output, endpoint URL, credentials, or server error body. */
    val summary: String get() = buildString {
        append("Provider reachable: ${providerReachable.label()}. Model listed: ${modelAvailable.label()}. ")
        append("Model accessible: ${modelAccessible.label()}. Tiny JSON response: ${tinyJsonResponse.label()}.\n")
        append("Format: ")
        append(when (formatCompatibility) {
            DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED -> "Ollama schema-requested sample passed (not proof of general schema enforcement)."
            DiagnosticFormatCompatibility.PROMPT_JSON_SAMPLE_PASSED -> "prompt-only JSON sample passed; enforced schema support was not tested."
            DiagnosticFormatCompatibility.INVALID_RESPONSE -> "sample did not match the required tiny JSON object."
            DiagnosticFormatCompatibility.NOT_TESTED -> "not verified."
        })
        append("\nFirst text: ${firstResponseLatencyMillis?.let { "$it ms" } ?: "unavailable"} (queue/load included). Total: $totalLatencyMillis ms.")
        append("\nPrompt tokens: ${stats?.promptTokens?.toString() ?: "unavailable"}; output tokens: ${stats?.outputTokens?.toString() ?: "unavailable"}.")
        val tokens = stats?.outputTokens
        val duration = stats?.generationDurationNanos
        val throughput = if (tokens != null && duration != null && duration > 0) tokens.toDouble() * 1_000_000_000.0 / duration else null
        append("\nProvider-reported generation throughput: ${throughput?.let { String.format(java.util.Locale.ROOT, "%.2f tokens/s", it) } ?: "unavailable"}; short sample, not a workload benchmark.")
        if (timedOut) append("\nStopped at the diagnostic time limit.")
        detail?.let { append("\n$it") }
    }
}

private fun DiagnosticCheck.label(): String = when (this) {
    DiagnosticCheck.PASSED -> "yes"
    DiagnosticCheck.FAILED -> "no"
    DiagnosticCheck.NOT_TESTED -> "not verified"
}

/**
 * No constructor/startup work. Invoke only after a user requests a check for one selected model.
 * At most one inventory request and one no-tools, 32-token JSON request; no retry or model pull.
 * Does not close caller-owned providers or alter their selected model/settings.
 */
object ModelDiagnostic {
    const val TIMEOUT_MILLIS = 30_000L
    const val MAX_OUTPUT_TOKENS = 32
    const val MAX_RESPONSE_CHARACTERS = 2048
    const val RESPONSE_SCHEMA = """{"type":"object","properties":{"ok":{"type":"boolean","enum":[true]}},"required":["ok"],"additionalProperties":false}"""

    suspend fun run(
        provider: InferenceProvider,
        model: String,
        options: InferenceOptions = InferenceOptions(),
    ): ModelDiagnosticResult = runBounded(provider, model, options, TIMEOUT_MILLIS, System::nanoTime)

    /** Clock/deadline injection for deterministic tests; production always uses the fixed short budget. */
    internal suspend fun runBounded(
        provider: InferenceProvider,
        model: String,
        options: InferenceOptions,
        timeoutMillis: Long,
        nanoTime: () -> Long,
    ): ModelDiagnosticResult {
        require(model.isNotBlank() && model.length <= 4096) { "Choose one valid model ID." }
        require(timeoutMillis in 1..TIMEOUT_MILLIS) { "Diagnostic timeout exceeds its bounded budget." }
        val started = nanoTime()
        fun elapsedMillis(since: Long): Long = ((nanoTime() - since) / 1_000_000).coerceAtLeast(0)
        var result = ModelDiagnosticResult(providerId = provider.id, model = model)
        val firstTextNanos = AtomicReference<Long?>(null)
        var inferenceStarted: Long? = null
        val completed = withTimeoutOrNull(timeoutMillis) {
            try {
                val models = provider.listModels()
                val available = models.any { it.name == model }
                result = result.copy(providerReachable = DiagnosticCheck.PASSED,
                    modelAvailable = if (available) DiagnosticCheck.PASSED else DiagnosticCheck.FAILED)
                if (!available) {
                    result = result.copy(detail = "The selected model was not listed; generation was skipped.")
                    return@withTimeoutOrNull true
                }
                val schemaRequested = provider.kind == "ollama"
                val diagnosticOptions = options.copy(temperature = 0.0, maxOutputTokens = MAX_OUTPUT_TOKENS,
                    responseSchema = if (schemaRequested) RESPONSE_SCHEMA else null,
                    executionTimeouts = null) // A user-selected long run never widens the small diagnostic budget.
                val receivedCharacters = AtomicInteger()
                val prompt = "Return exactly {\"ok\":true}, with no other text. JSON schema: $RESPONSE_SCHEMA"
                inferenceStarted = nanoTime()
                val chat = InferenceFabric.wrap(provider).chat(model,
                    listOf(ApiMessage("user", prompt)), null, diagnosticOptions) { chunk ->
                    if (chunk.isNotEmpty()) firstTextNanos.compareAndSet(null, nanoTime())
                    if (receivedCharacters.addAndGet(chunk.length) > MAX_RESPONSE_CHARACTERS) {
                        throw DiagnosticResponseLimitException()
                    }
                }
                val valid = chat.done && chat.toolCalls.isEmpty() && isExpectedTinyJson(chat.content)
                result = result.copy(modelAccessible = DiagnosticCheck.PASSED,
                    tinyJsonResponse = if (valid) DiagnosticCheck.PASSED else DiagnosticCheck.FAILED,
                    formatCompatibility = when {
                        !valid -> DiagnosticFormatCompatibility.INVALID_RESPONSE
                        schemaRequested -> DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED
                        else -> DiagnosticFormatCompatibility.PROMPT_JSON_SAMPLE_PASSED
                    }, stats = chat.stats,
                    detail = if (valid) null else "The model responded, but the diagnostic JSON sample was not valid.")
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val duringGeneration = inferenceStarted != null
                result = result.copy(
                    providerReachable = if (duringGeneration || error is InferenceHttpException) DiagnosticCheck.PASSED else DiagnosticCheck.FAILED,
                    modelAccessible = if (duringGeneration) DiagnosticCheck.FAILED else DiagnosticCheck.NOT_TESTED,
                    tinyJsonResponse = if (error is DiagnosticResponseLimitException) DiagnosticCheck.FAILED else DiagnosticCheck.NOT_TESTED,
                    formatCompatibility = if (error is DiagnosticResponseLimitException) DiagnosticFormatCompatibility.INVALID_RESPONSE else DiagnosticFormatCompatibility.NOT_TESTED,
                    detail = safeDiagnosticFailure(error, duringGeneration),
                )
                true
            }
        }
        val firstNanos = firstTextNanos.get()
        val latency = inferenceStarted?.let { requestStarted ->
            firstNanos?.let { ((it - requestStarted) / 1_000_000).coerceAtLeast(0) }
        }
        return result.copy(firstResponseLatencyMillis = latency, totalLatencyMillis = elapsedMillis(started),
            timedOut = completed == null,
            detail = if (completed == null) "The check did not finish within its time budget; unfinished checks remain unverified." else result.detail)
    }

    /** Exact schema sample validation also rejects fences, explanations, extra keys and loose JSON syntax. */
    internal fun isExpectedTinyJson(text: String): Boolean =
        text.length <= MAX_RESPONSE_CHARACTERS &&
            Regex("""\{[ \t\r\n]*"ok"[ \t\r\n]*:[ \t\r\n]*true[ \t\r\n]*}""")
                .matches(text.trim(' ', '\t', '\r', '\n'))
}

private class DiagnosticResponseLimitException : IOException("Diagnostic response exceeded its size limit")

private fun safeDiagnosticFailure(error: Exception, duringGeneration: Boolean): String = when {
    error is InferenceHttpException && error.statusCode in listOf(401, 403) -> "Provider denied access (HTTP ${error.statusCode}). Check configured access credentials."
    error is InferenceHttpException -> "Provider returned HTTP ${error.statusCode}; ${if (duringGeneration) "generation/format compatibility" else "model inventory"} could not be verified."
    error is DiagnosticResponseLimitException -> "The response exceeded the diagnostic size limit; generation was stopped."
    duringGeneration -> "The selected model could not complete the diagnostic request."
    else -> "Provider model inventory could not be reached or read."
}

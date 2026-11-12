package com.nyx.ai.autonomy

import org.json.JSONArray
import org.json.JSONObject

/** Kinds of evidence NYX can check locally without trusting a model's claim of success. */
enum class EvidenceKind {
    EXIT_CODE,
    OUTPUT_CONTAINS,
    OUTPUT_NOT_CONTAINS,
    FILE_EXISTS,
    FILE_CONTAINS,
    CHECKSUM_SHA256,
    ARTIFACT_NONEMPTY,
}

data class EvidenceSpec(
    val kind: EvidenceKind,
    val target: String? = null,
    val expected: String? = null,
    val description: String = "",
)

/** Everything the verifier is allowed to inspect. File access is injected so tests stay pure. */
data class ObservationBundle(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val agentOutput: String = "",
    val fileExists: suspend (String) -> Boolean = { false },
    val fileText: suspend (String, Int) -> String? = { _, _ -> null },
    val checksum: suspend (String) -> String? = { null },
)

data class EvidenceResult(
    val spec: EvidenceSpec,
    val passed: Boolean,
    val actual: String,
    val detail: String? = null,
)

data class VerificationReport(
    val passed: Boolean,
    val results: List<EvidenceResult>,
    val reason: String?,
) {
    val evidenceJson: String
        get() = JSONArray().apply {
            results.forEach { result ->
                put(
                    JSONObject()
                        .put("kind", result.spec.kind.name.lowercase())
                        .put("target", result.spec.target)
                        .put("expected", result.spec.expected)
                        .put("actual", result.actual.take(1_000))
                        .put("passed", result.passed)
                        .put("detail", result.detail)
                )
            }
        }.toString()
}

/** Parses optional, bounded evidence declarations from a plan task. */
object EvidenceSpecParser {
    const val MAX_SPECS = 8
    private const val MAX_FIELD_CHARS = 512

    fun parse(raw: JSONArray?): List<EvidenceSpec> {
        if (raw == null) return emptyList()
        val result = ArrayList<EvidenceSpec>()
        for (i in 0 until minOf(raw.length(), MAX_SPECS)) {
            val obj = raw.optJSONObject(i) ?: continue
            val kind = runCatching { EvidenceKind.valueOf(obj.optString("kind").uppercase()) }.getOrNull() ?: continue
            val target = obj.optString("target").take(MAX_FIELD_CHARS).ifBlank { null }
            val expected = obj.optString("expected").take(MAX_FIELD_CHARS).ifBlank { null }
            val description = obj.optString("description").take(MAX_FIELD_CHARS)
            if (kind == EvidenceKind.FILE_EXISTS && target == null) continue
            if (kind == EvidenceKind.FILE_CONTAINS && (target == null || expected == null)) continue
            if (kind == EvidenceKind.CHECKSUM_SHA256 && (target == null || expected == null)) continue
            if (kind == EvidenceKind.OUTPUT_CONTAINS && expected == null) continue
            result += EvidenceSpec(kind, target, expected, description)
        }
        return result
    }

    /** Accepts either a bare specs array or the persisted {"specs":[...],"results":[...]} object. */
    fun parse(raw: String?): List<EvidenceSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) parse(JSONObject(trimmed).optJSONArray("specs"))
            else parse(JSONArray(trimmed))
        }.getOrDefault(emptyList())
    }
}

/**
 * Verifies a task against declared evidence. A model's prose is never sufficient on its own: when
 * evidence is declared, every item must pass. When no evidence is declared, the task can only pass
 * if the agent produced output and no tool invocation failed, and the report says so explicitly.
 */
object TaskVerifier {
    suspend fun verify(
        specs: List<EvidenceSpec>,
        observation: ObservationBundle,
        toolFailureCount: Int = 0,
    ): VerificationReport {
        val results = ArrayList<EvidenceResult>()
        if (toolFailureCount > 0) {
            results += EvidenceResult(
                EvidenceSpec(EvidenceKind.ARTIFACT_NONEMPTY, description = "tool execution"),
                passed = false,
                actual = "$toolFailureCount tool call(s) failed or were denied",
                detail = "A failed tool call cannot be treated as completed work.",
            )
        }
        for (spec in specs.take(EvidenceSpecParser.MAX_SPECS)) {
            results += evaluate(spec, observation)
        }
        if (specs.isEmpty()) {
            val nonEmpty = observation.agentOutput.isNotBlank() || observation.stdout.isNotBlank()
            results += EvidenceResult(
                EvidenceSpec(EvidenceKind.ARTIFACT_NONEMPTY, description = "non-empty result"),
                passed = nonEmpty,
                actual = if (nonEmpty) "non-empty output" else "empty output",
                detail = "No explicit evidence was declared; this only proves the task produced output.",
            )
        }
        val passed = results.isNotEmpty() && results.all { it.passed }
        val reason = when {
            passed -> null
            results.isEmpty() -> "No verifiable evidence was available."
            else -> results.firstOrNull { !it.passed }?.let { "${it.spec.kind.name.lowercase()}: ${it.detail ?: "expected ${it.spec.expected}, got ${it.actual}"}" }
        }
        return VerificationReport(passed, results, reason)
    }

    private suspend fun evaluate(spec: EvidenceSpec, observation: ObservationBundle): EvidenceResult = try {
        when (spec.kind) {
            EvidenceKind.EXIT_CODE -> {
                val expected = spec.expected?.trim()?.toIntOrNull() ?: 0
                val actual = observation.exitCode
                EvidenceResult(spec, actual == expected, actual?.toString() ?: "no exit code", "expected exit code $expected")
            }
            EvidenceKind.OUTPUT_CONTAINS -> {
                val needle = spec.expected.orEmpty()
                val found = observation.stdout.contains(needle, ignoreCase = true) ||
                    observation.stderr.contains(needle, ignoreCase = true)
                EvidenceResult(spec, found, if (found) "found" else "not found", "expected output to contain '$needle'")
            }
            EvidenceKind.OUTPUT_NOT_CONTAINS -> {
                val needle = spec.expected.orEmpty()
                val found = observation.stdout.contains(needle, ignoreCase = true) ||
                    observation.stderr.contains(needle, ignoreCase = true)
                EvidenceResult(spec, !found, if (found) "found" else "absent", "expected output not to contain '$needle'")
            }
            EvidenceKind.FILE_EXISTS -> {
                val target = spec.target.orEmpty()
                val exists = target.isNotBlank() && observation.fileExists(target)
                EvidenceResult(spec, exists, if (exists) "exists" else "missing", "expected file $target")
            }
            EvidenceKind.FILE_CONTAINS -> {
                val target = spec.target.orEmpty()
                val text = observation.fileText(target, 64_000)
                val found = text?.contains(spec.expected.orEmpty(), ignoreCase = true) == true
                EvidenceResult(spec, found, if (text == null) "unreadable" else if (found) "found" else "not found", "expected $target to contain '${spec.expected}'")
            }
            EvidenceKind.CHECKSUM_SHA256 -> {
                val actual = observation.checksum(spec.target.orEmpty())
                val expected = spec.expected.orEmpty().lowercase()
                val matches = actual != null && actual.lowercase() == expected
                EvidenceResult(spec, matches, actual ?: "unavailable", "expected sha256 $expected")
            }
            EvidenceKind.ARTIFACT_NONEMPTY -> {
                val nonEmpty = observation.agentOutput.isNotBlank() || observation.stdout.isNotBlank()
                EvidenceResult(spec, nonEmpty, if (nonEmpty) "non-empty" else "empty")
            }
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (e: Exception) {
        EvidenceResult(spec, false, "error", e.message?.take(200) ?: "verification failed")
    }

    /** Builds a stable signature so identical failures are not retried forever. */
    fun failureSignature(taskId: String, reason: String?): String {
        val normalized = (reason ?: "unknown").lowercase()
            .replace(Regex("0x[0-9a-f]+"), "#")
            .replace(Regex("\\b[0-9a-f]{8,}\\b"), "#")
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
        return "$taskId:${normalized.take(160).hashCode()}"
    }
}

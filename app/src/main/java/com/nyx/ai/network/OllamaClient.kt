package com.nyx.ai.network

import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LocalModel(val name: String, val sizeBytes: Long = 0, val modifiedAt: String = "")
data class ToolCall(val id: String, val name: String, val arguments: JSONObject)
/** Provider-reported tokenizer counts; never derive these from streamed chunks or characters. */
data class InferenceTokenStats(
    val promptTokens: Long? = null,
    val outputTokens: Long? = null,
    val generationDurationNanos: Long? = null,
    val totalDurationNanos: Long? = null,
    val loadDurationNanos: Long? = null,
)
data class ChatResult(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val done: Boolean = true,
    val stats: InferenceTokenStats? = null,
)
data class ApiMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCalls: JSONArray? = null,
    val toolCallId: String? = null
)
data class InferenceOptions(
    val temperature: Double = 0.7,
    val contextLength: Int = 4096,
    val cpuThreads: Int = 4,
    val maxOutputTokens: Int? = null,
    val responseSchema: String? = null,
    val executionTimeouts: ExecutionTimeoutPolicy? = null,
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "Output token limit must be positive." }
    }
}
class ToolCallingUnsupportedException(message: String) : IOException(message)
class InferenceHttpException(val statusCode: Int, message: String) : IOException(message)

/** Local HTTP client for Ollama and OpenAI-compatible APIs. Requests go only to this configured base URL. */
class OllamaClient(baseUrl: String, private val apiKey: String? = null) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')
    private val openAiCompatible = ProviderFactory.isOpenAiCompatible(this.baseUrl)
    // A process-wide client shares its dispatcher and connection pool across endpoint/client instances.
    private val http = sharedHttp
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(): List<LocalModel> {
        val url = endpoint(if (openAiCompatible) "/models" else "/api/tags")
        val request = Request.Builder().url(url).get().apply { addApiKeyIfNeeded() }.build()
        return http.newCall(request).awaitValue { response ->
            if (!response.isSuccessful) {
                val detail = readBoundedErrorBody(response)
                throw InferenceHttpException(response.code, "Inference server returned HTTP ${response.code}${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val source = response.body?.source() ?: throw IOException("Empty model inventory response.")
            val buffer = okio.Buffer()
            while (!source.exhausted()) {
                source.read(buffer, minOf(8192L, MAX_MODEL_LIST_BYTES + 1 - buffer.size))
                if (buffer.size > MAX_MODEL_LIST_BYTES) throw IOException("Model inventory exceeds its size limit.")
            }
            val root = JSONObject(buffer.readUtf8())
            val arr = root.optJSONArray(if (openAiCompatible) "data" else "models")
                ?: throw IOException("Inference server returned an invalid model inventory.")
            (0 until arr.length()).mapNotNull { index ->
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                LocalModel(
                    name = item.optString(if (openAiCompatible) "id" else "name"),
                    sizeBytes = item.optLong("size", 0L),
                    modifiedAt = item.optString("modified_at", "")
                ).takeIf { model -> model.name.isNotBlank() }
            }
        }
    }

    suspend fun healthCheck(): Boolean = try { listModels(); true } catch (_: Exception) { false }

    /** Streams text deltas and returns complete function tool calls from either provider format. */
    suspend fun chat(
        model: String,
        messages: List<ApiMessage>,
        tools: JSONArray? = null,
        options: InferenceOptions = InferenceOptions(),
        onToken: (String) -> Unit = {}
    ): ChatResult = withExecutionTimeouts(options, onToken) { observedText ->
        chatStream(model, messages, tools, options, observedText)
    }

    private suspend fun chatStream(
        model: String,
        messages: List<ApiMessage>,
        tools: JSONArray?,
        options: InferenceOptions,
        onToken: (String) -> Unit,
    ): ChatResult = suspendCancellableCoroutine { continuation ->
        val messageArray = JSONArray()
        messages.forEach { message ->
            val item = JSONObject().put("role", message.role).put("content", message.content)
            if (message.toolCallId != null && openAiCompatible) item.put("tool_call_id", message.toolCallId)
            if (!message.toolName.isNullOrBlank() && !openAiCompatible) item.put("tool_name", message.toolName)
            if (message.toolCalls != null) item.put("tool_calls", if (openAiCompatible) toOpenAiToolCalls(message.toolCalls) else message.toolCalls)
            messageArray.put(item)
        }
        val payload = JSONObject().put("model", model).put("messages", messageArray).put("stream", true)
        if (openAiCompatible) {
            payload.put("temperature", options.temperature.coerceIn(0.0, 2.0))
            options.maxOutputTokens?.let { payload.put("max_tokens", it) }
        } else {
            val modelOptions = JSONObject()
                .put("temperature", options.temperature.coerceIn(0.0, 2.0))
                .put("num_ctx", options.contextLength.coerceIn(512, 131072))
                .put("num_thread", options.cpuThreads.coerceIn(1, 32))
            options.maxOutputTokens?.let { modelOptions.put("num_predict", it) }
            payload.put("options", modelOptions)
            options.responseSchema?.let { schema ->
                require(schema.length <= 16_384) { "Response schema exceeds 16,384 characters." }
                payload.put("format", parseJsonObject(schema))
            }
        }
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        val url = endpoint(if (openAiCompatible) "/chat/completions" else "/api/chat")
        val request = Request.Builder().url(url).post(payload.toString().toRequestBody(jsonType)).apply { addApiKeyIfNeeded() }.build()
        val call = httpFor(options).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val detail = readBoundedErrorBody(res)
                        val message = "Inference server returned HTTP ${res.code}${if (detail.isNotBlank()) ": $detail" else ": ${res.message}"}"
                        val normalized = detail.lowercase()
                        val doesNotSupportTools = tools != null && tools.length() > 0 && res.code == 400 && listOf(
                            "does not support tools", "doesn't support tools", "tool calling is not supported",
                            "tools are not supported", "does not support tool calling", "unsupported tool calling"
                        ).any(normalized::contains)
                        if (continuation.isActive) continuation.resumeWithException(
                            if (doesNotSupportTools) ToolCallingUnsupportedException(message) else InferenceHttpException(res.code, message)
                        )
                        return
                    }
                    try {
                        val source = res.body?.source() ?: throw IOException("Empty response from inference server")
                        val allText = StringBuilder()
                        val completedCalls = mutableListOf<ToolCall>()
                        val partialCalls = linkedMapOf<Int, PartialToolCall>()
                        var stats: InferenceTokenStats? = null
                        var streamDone = false
                        while (!source.exhausted() && continuation.isActive) {
                            val line = readBoundedStreamLine(source) ?: break
                            if (line.isBlank()) continue
                            if (openAiCompatible) {
                                // SSE comments, event metadata and empty frames are transport activity, not text.
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data.isBlank()) continue
                                if (data == "[DONE]") { streamDone = true; break }
                                val root = JSONObject(data)
                                if (root.has("error")) throw IOException("OpenAI-compatible server returned an API error.")
                                root.optJSONObject("usage")?.let { usage ->
                                    stats = InferenceTokenStats(
                                        promptTokens = usage.nonnegativeLong("prompt_tokens"),
                                        outputTokens = usage.nonnegativeLong("completion_tokens"),
                                    ).takeIf { it.promptTokens != null || it.outputTokens != null }
                                }
                                val choice = root.optJSONArray("choices")?.optJSONObject(0)
                                if (choice != null && !choice.isNull("finish_reason") && choice.optString("finish_reason").isNotBlank()) streamDone = true
                                val delta = choice?.optJSONObject("delta") ?: continue
                                val text = delta.optString("content", "")
                                if (text.isNotEmpty()) { allText.append(text); onToken(text) }
                                val rawCalls = delta.optJSONArray("tool_calls")
                                if (rawCalls != null) for (i in 0 until rawCalls.length()) {
                                    val update = rawCalls.optJSONObject(i) ?: continue
                                    val index = update.optInt("index", i)
                                    val partial = partialCalls.getOrPut(index) { PartialToolCall() }
                                    update.optString("id").takeIf { it.isNotBlank() }?.let { partial.id = it }
                                    val fn = update.optJSONObject("function")
                                    if (fn != null) {
                                        partial.name.append(fn.optString("name", ""))
                                        partial.arguments.append(fn.optString("arguments", ""))
                                    }
                                }
                            } else {
                                val root = JSONObject(line)
                                if (root.has("error")) throw IOException("Ollama server returned an API error.")
                                val message = root.optJSONObject("message") ?: JSONObject()
                                val text = message.optString("content", "")
                                if (text.isNotEmpty()) { allText.append(text); onToken(text) }
                                val rawCalls = message.optJSONArray("tool_calls")
                                if (rawCalls != null) for (i in 0 until rawCalls.length()) {
                                    val tc = rawCalls.optJSONObject(i) ?: continue
                                    val fn = tc.optJSONObject("function") ?: continue
                                    completedCalls += ToolCall(tc.optString("id", "tool-$i"), fn.optString("name"), argumentsObject(fn.opt("arguments")))
                                }
                                if (root.optBoolean("done", false)) {
                                    streamDone = true
                                    stats = InferenceTokenStats(
                                        promptTokens = root.nonnegativeLong("prompt_eval_count"),
                                        outputTokens = root.nonnegativeLong("eval_count"),
                                        generationDurationNanos = root.nonnegativeLong("eval_duration"),
                                        totalDurationNanos = root.nonnegativeLong("total_duration"),
                                        loadDurationNanos = root.nonnegativeLong("load_duration"),
                                    ).takeIf { value ->
                                        value.promptTokens != null || value.outputTokens != null ||
                                            value.generationDurationNanos != null || value.totalDurationNanos != null ||
                                            value.loadDurationNanos != null
                                    }
                                    break
                                }
                            }
                        }
                        if (openAiCompatible) partialCalls.toSortedMap().values.forEach { partial ->
                            if (partial.name.isNotBlank()) {
                                completedCalls += ToolCall(partial.id.ifBlank { "tool-${completedCalls.size}" }, partial.name.toString(), argumentsObject(partial.arguments.toString()))
                            }
                        }
                        if (continuation.isActive) continuation.resume(ChatResult(allText.toString(), completedCalls, done = streamDone, stats = stats))
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    internal fun httpFor(options: InferenceOptions): OkHttpClient {
        val timeouts = options.executionTimeouts ?: return http
        return http.newBuilder()
            // Socket activity is bounded by the whole request; text-only inactivity is enforced above.
            .readTimeout(timeouts.requestMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeouts.requestMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private data class PartialToolCall(var id: String = "", val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())

    private fun readBoundedStreamLine(source: okio.BufferedSource): String? {
        val newline = source.indexOf('\n'.code.toByte(), 0, MAX_STREAM_LINE_BYTES + 1)
        if (newline >= 0) return source.readUtf8LineStrict(MAX_STREAM_LINE_BYTES)
        if (source.buffer.size > MAX_STREAM_LINE_BYTES) throw IOException("Inference stream frame exceeds its size limit.")
        return if (source.buffer.size > 0) source.readUtf8() else null
    }

    private fun endpoint(suffix: String): okhttp3.HttpUrl {
        val url = baseUrl.toHttpUrl()
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + suffix).build()
    }

    private fun Request.Builder.addApiKeyIfNeeded(): Request.Builder {
        if (openAiCompatible && !apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        return this
    }

    private fun readBoundedErrorBody(response: Response): String {
        val body = response.body ?: return ""
        val buffer = okio.Buffer()
        val count = runCatching { body.source().read(buffer, 2048) }.getOrDefault(0L)
        if (count <= 0) return ""
        var text = buffer.readUtf8().replace(Regex("[\\p{Cntrl}&&[^\\t]]"), " ").replace(Regex("\\s+"), " ").trim()
        val key = apiKey?.takeIf { it.isNotBlank() }
        if (key != null) text = text.replace(key, "[REDACTED]")
        return text.take(600)
    }

    private fun argumentsObject(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> try { JSONObject(value) } catch (_: Exception) { JSONObject() }
        else -> JSONObject()
    }

    /** Ollama represents arguments as an object; OpenAI-compatible APIs require a JSON string. */
    private fun toOpenAiToolCalls(input: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until input.length()) {
            val call = input.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function") ?: continue
            val args = function.opt("arguments")
            put(JSONObject()
                .put("id", call.optString("id", "tool-$i"))
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", function.optString("name"))
                    .put("arguments", if (args is String) args else args?.toString() ?: "{}")))
        }
    }

    private suspend fun <T> Call.awaitValue(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        val value = read(it)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    companion object {
        private const val MAX_MODEL_LIST_BYTES = 2 * 1024 * 1024L
        private const val MAX_STREAM_LINE_BYTES = 1024 * 1024L
        private val sharedHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

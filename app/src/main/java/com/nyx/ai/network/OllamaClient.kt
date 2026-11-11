package com.nyx.ai.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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
data class ChatResult(val content: String, val toolCalls: List<ToolCall> = emptyList(), val done: Boolean = true)
data class ApiMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCalls: JSONArray? = null,
    val toolCallId: String? = null
)
data class InferenceOptions(val temperature: Double = 0.7, val contextLength: Int = 4096, val cpuThreads: Int = 4)
class ToolCallingUnsupportedException(message: String) : IOException(message)

/** Local HTTP client for Ollama and OpenAI-compatible APIs. Requests go only to this configured base URL. */
class OllamaClient(baseUrl: String, private val apiKey: String? = null) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')
    private val openAiCompatible = this.baseUrl.substringBefore('?').endsWith("/v1")
    // A process-wide client shares its dispatcher and connection pool across endpoint/client instances.
    private val http = sharedHttp
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(): List<LocalModel> {
        val url = if (openAiCompatible) "$baseUrl/models" else "$baseUrl/api/tags"
        val request = Request.Builder().url(url).get().apply { addApiKeyIfNeeded() }.build()
        val response = http.newCall(request).await()
        response.use {
            if (!it.isSuccessful) {
                val detail = readBoundedErrorBody(it)
                throw IOException("Inference server returned HTTP ${it.code}${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val root = JSONObject(it.body?.string().orEmpty())
            val arr = root.optJSONArray(if (openAiCompatible) "data" else "models") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { index ->
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
        } else {
            payload.put("options", JSONObject()
                .put("temperature", options.temperature.coerceIn(0.0, 2.0))
                .put("num_ctx", options.contextLength.coerceIn(512, 131072))
                .put("num_thread", options.cpuThreads.coerceIn(1, 32)))
        }
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        val url = if (openAiCompatible) "$baseUrl/chat/completions" else "$baseUrl/api/chat"
        val request = Request.Builder().url(url).post(payload.toString().toRequestBody(jsonType)).apply { addApiKeyIfNeeded() }.build()
        val call = http.newCall(request)
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
                            if (doesNotSupportTools) ToolCallingUnsupportedException(message) else IOException(message)
                        )
                        return
                    }
                    try {
                        val source = res.body?.source() ?: throw IOException("Empty response from inference server")
                        val allText = StringBuilder()
                        val completedCalls = mutableListOf<ToolCall>()
                        val partialCalls = linkedMapOf<Int, PartialToolCall>()
                        while (!source.exhausted() && continuation.isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            if (openAiCompatible) {
                                val data = line.removePrefix("data:").trim()
                                if (data.isBlank()) continue
                                if (data == "[DONE]") break
                                val root = JSONObject(data)
                                if (root.has("error")) throw IOException("OpenAI-compatible server returned an API error.")
                                val delta = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
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
                                val message = root.optJSONObject("message") ?: JSONObject()
                                val text = message.optString("content", "")
                                if (text.isNotEmpty()) { allText.append(text); onToken(text) }
                                val rawCalls = message.optJSONArray("tool_calls")
                                if (rawCalls != null) for (i in 0 until rawCalls.length()) {
                                    val tc = rawCalls.optJSONObject(i) ?: continue
                                    val fn = tc.optJSONObject("function") ?: continue
                                    completedCalls += ToolCall(tc.optString("id", "tool-$i"), fn.optString("name"), argumentsObject(fn.opt("arguments")))
                                }
                                if (root.optBoolean("done", false)) break
                            }
                        }
                        if (openAiCompatible) partialCalls.toSortedMap().values.forEach { partial ->
                            if (partial.name.isNotBlank()) {
                                completedCalls += ToolCall(partial.id.ifBlank { "tool-${completedCalls.size}" }, partial.name.toString(), argumentsObject(partial.arguments.toString()))
                            }
                        }
                        if (continuation.isActive) continuation.resume(ChatResult(allText.toString(), completedCalls))
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private data class PartialToolCall(var id: String = "", val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())

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

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    companion object {
        private val sharedHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

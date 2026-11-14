package com.nyx.ai.gateway

import com.nyx.ai.network.InferenceEndpointPolicy
import com.nyx.ai.network.NetworkAddressPolicy
import com.nyx.ai.network.NetworkTransportGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Endpoint rules shared by every gateway transport that speaks to a user-supplied URL.
 * A remote plain-HTTP endpoint is refused because bearer credentials and tool results would travel
 * in clear text; loopback HTTP stays available for a local MCP sidecar.
 */
object McpEndpointPolicy {
    fun isLoopback(host: String?): Boolean = host != null && NetworkAddressPolicy.isLoopbackHost(host)

    /** Returns the normalized endpoint or throws [GatewayException] with a non-secret reason. */
    fun validate(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) throw GatewayException("MCP endpoint is missing or too long.")
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: throw GatewayException("MCP endpoint is not a valid URL.")
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") throw GatewayException("MCP endpoints must use HTTPS (HTTP is allowed only on loopback).")
        if (!parsed.userInfo.isNullOrEmpty()) throw GatewayException("MCP endpoints must not embed credentials.")
        if (parsed.host.isNullOrBlank()) throw GatewayException("MCP endpoint has no host.")
        if (parsed.rawQuery != null) throw GatewayException("MCP endpoint queries are refused; use vault-backed header credentials.")
        if (parsed.fragment != null) throw GatewayException("MCP endpoints must not contain a fragment.")
        if (scheme == "http" && !isLoopback(parsed.host)) {
            throw GatewayException("Plain HTTP is refused for remote MCP endpoints; use HTTPS or a loopback address.")
        }
        try { InferenceEndpointPolicy.parse(trimmed) } catch (_: IllegalArgumentException) { throw GatewayException("MCP endpoint URL is invalid.") }
        return trimmed
    }

    /** Host-only label for logs and health text: query strings never reach a log line. */
    fun label(endpoint: String): String {
        val parsed = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return "invalid-endpoint"
        val host = parsed.host ?: return "invalid-endpoint"
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        val path = parsed.path?.takeIf { it.isNotBlank() } ?: "/"
        return "${parsed.scheme}://$host$port$path"
    }
}

/**
 * A genuine Model Context Protocol client over Streamable HTTP.
 *
 * Each request is a JSON-RPC 2.0 POST that accepts either `application/json` or `text/event-stream`.
 * Redirects are never followed, bodies are capped at [GatewayLimits.MAX_BODY_BYTES], and every
 * network call is cancelled when the surrounding coroutine is cancelled or [close] runs.
 */
class McpClient(
    private val config: GatewayServerConfig,
    private val tokenProvider: GatewayTokenProvider,
    httpClient: OkHttpClient? = null,
) : GatewayToolExecutor {
    private val endpoint: String = McpEndpointPolicy.validate(config.endpoint)
    private val ownedDispatcher: Dispatcher? = if (httpClient == null) Dispatcher() else null
    private val httpClient: OkHttpClient = NetworkTransportGuard.inferenceClient(
        httpClient ?: defaultClient(config.timeoutSeconds, ownedDispatcher!!),
        InferenceEndpointPolicy.parse(endpoint), !config.credentialRef.isNullOrBlank(),
    )

    private val requestIds = AtomicLong(0)
    private val initMutex = Mutex()
    private val activeCalls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())

    @Volatile private var initialized = false
    @Volatile private var closed = false
    @Volatile private var sessionId: String? = null
    @Volatile private var negotiatedVersion: String? = null

    /** Session id issued by the server during `initialize`, if any. */
    val mcpSessionId: String? get() = sessionId

    /** Protocol version chosen by the server (or the version NYX proposed). */
    val protocolVersion: String? get() = negotiatedVersion

    override suspend fun listTools(): List<GatewayTool> {
        ensureOpen()
        ensureInitialized()
        return fetchTools()
    }

    override suspend fun callTool(name: String, argumentsJson: String): GatewayToolResult {
        ensureOpen()
        if (name.isBlank()) throw GatewayException("Tool name must not be blank.")
        if (config.allowedTools.isNotEmpty() && name !in config.allowedTools) {
            throw GatewayException("Tool '$name' is not allowed by gateway '${config.id}'.")
        }
        if (argumentsJson.length > GatewayLimits.MAX_ARGUMENT_CHARS) {
            throw GatewayException("Tool arguments exceed ${GatewayLimits.MAX_ARGUMENT_CHARS} characters.")
        }
        val arguments = if (argumentsJson.isBlank()) JSONObject() else runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: throw GatewayException("Tool arguments must be a JSON object.")
        ensureInitialized()
        val token = resolveToken()
        val started = System.nanoTime()
        val result = rpc(
            method = "tools/call",
            params = JSONObject().put("name", name).put("arguments", arguments),
            token = token,
        ) ?: throw GatewayException("MCP server returned no result for tools/call '$name'.")
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val isError = result.optBoolean("isError", false)
        val content = extractContent(result, config.maxResponseChars, token)
        val raw = GatewayLimits.truncate(
            GatewayLimits.redactJson(result.toString(), listOfNotNull(token)),
            GatewayLimits.MAX_BODY_BYTES,
        )
        return GatewayToolResult(
            success = !isError,
            content = content,
            isError = isError,
            latencyMs = latencyMs,
            boundedRaw = raw,
        )
    }

    override suspend fun health(): GatewayHealth {
        if (closed) return GatewayHealth(GatewayHealthState.MISCONFIGURED, "Client is closed.", 0)
        return try {
            val tools = listTools()
            GatewayHealth(GatewayHealthState.HEALTHY, "Connected to ${McpEndpointPolicy.label(endpoint)}.", tools.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = GatewayLimits.redact(failure.message ?: failure::class.simpleName ?: "MCP server unreachable.")
            GatewayHealth(GatewayHealthState.UNREACHABLE, detail.take(300), 0)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach { call -> runCatching { call.cancel() } }
        synchronized(activeCalls) { activeCalls.clear() }
        ownedDispatcher?.executorService?.shutdown()
    }

    // ------------------------------------------------------------------ handshake

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            if (closed) throw GatewayException("MCP client is closed.")
            val token = resolveToken()
            val params = JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "NYX").put("version", CLIENT_VERSION))
            val requestId = requestIds.incrementAndGet()
            val payload = post(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", requestId)
                    .put("method", "initialize")
                    .put("params", params),
                expectedId = requestId,
                token = token,
            )
            val result = payload.result
                ?: throw GatewayException("MCP initialize returned no result.")
            negotiatedVersion = result.optString("protocolVersion").takeIf { it.isNotBlank() } ?: PROTOCOL_VERSION
            payload.sessionId?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            notifyInitialized(token)
            initialized = true
        }
    }

    private suspend fun notifyInitialized(token: String?) {
        val payload = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized"),
            expectedId = null,
            token = token,
        )
        if (payload.status !in 200..299) {
            throw GatewayException("MCP notifications/initialized failed with HTTP ${payload.status}.")
        }
    }

    private suspend fun fetchTools(): List<GatewayTool> {
        val tools = LinkedHashMap<String, GatewayTool>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            if (pages >= MAX_TOOL_PAGES) throw GatewayException("MCP tools/list exceeded $MAX_TOOL_PAGES pages.")
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val result = rpc("tools/list", params, resolveToken()) ?: throw GatewayException("MCP tools/list returned no result.")
            val array = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index)
                    ?: throw GatewayException("MCP tools/list entry $index is not an object.")
                val name = entry.optString("name").trim()
                if (name.isEmpty()) throw GatewayException("MCP tools/list entry $index has no name.")
                val schema = entry.opt("inputSchema")
                val schemaJson = when (schema) {
                    is JSONObject -> {
                        if (schema.has("type") && !schema.optString("type").equals("object", ignoreCase = true)) {
                            throw GatewayException("MCP tool '$name' declares a non-object input schema.")
                        }
                        schema.toString()
                    }

                    null, JSONObject.NULL -> DEFAULT_INPUT_SCHEMA
                    else -> throw GatewayException("MCP tool '$name' declares a non-object input schema.")
                }
                tools[name] = GatewayTool(
                    name = name,
                    description = entry.optString("description").take(MAX_DESCRIPTION_CHARS),
                    inputSchemaJson = GatewayLimits.truncate(schemaJson, MAX_SCHEMA_CHARS),
                )
                if (tools.size > MAX_TOOLS) throw GatewayException("MCP tools/list returned more than $MAX_TOOLS tools.")
            }
            pages++
            val next = result.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
            if (next == null) break
            if (next == cursor) throw GatewayException("MCP tools/list repeated the same pagination cursor.")
            cursor = next
        }
        return tools.values.toList()
    }

    // ------------------------------------------------------------------ JSON-RPC

    /** Sends one request and returns its `result`, or null for notifications. */
    private suspend fun rpc(method: String, params: JSONObject?, token: String?): JSONObject? {
        val id = requestIds.incrementAndGet()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) body.put("params", params)
        return post(body, expectedId = id, token = token).result
    }

    private suspend fun post(body: JSONObject, expectedId: Long?, token: String?): RpcPayload {
        ensureOpen()
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", USER_AGENT)
            .apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                sessionId?.takeIf { it.isNotBlank() }?.let { header("Mcp-Session-Id", it) }
                negotiatedVersion?.takeIf { it.isNotBlank() }?.let { header("MCP-Protocol-Version", it) }
            }
            .build()
        val call = httpClient.newCall(request)
        activeCalls.add(call)
        try {
            // The continuation stays alive while the body is consumed, so cancelling the coroutine
            // closes the socket instead of waiting for a slow server to finish the stream.
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isActive) return
                        continuation.resumeWith(
                            Result.failure(
                                GatewayException(
                                    "MCP transport failed for '${config.id}': " +
                                        GatewayLimits.redact(e.message ?: "I/O error", listOfNotNull(token)),
                                    e,
                                ),
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome: Result<RpcPayload> = try {
                            Result.success(
                                response.use { received ->
                                    val contentLength = received.body?.contentLength() ?: -1L
                                    if (contentLength > GatewayLimits.MAX_BODY_BYTES) {
                                        throw GatewayException(
                                            "MCP response exceeded the ${GatewayLimits.MAX_BODY_BYTES} byte limit.",
                                        )
                                    }
                                    val text = readBody(received)
                                    val status = received.code
                                    if (status !in 200..299) throw httpFailure(status, text, token)
                                    val contentType = received.header("Content-Type")?.lowercase(Locale.ROOT)
                                    if (expectedId == null) {
                                        // Notifications are fire-and-forget; a 2xx is the whole contract.
                                        RpcPayload(status, null, received.header("Mcp-Session-Id"), contentType)
                                    } else {
                                        RpcPayload(
                                            status,
                                            extractResponse(text, contentType, expectedId, token),
                                            received.header("Mcp-Session-Id"),
                                            contentType,
                                        )
                                    }
                                },
                            )
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                        if (continuation.isActive) continuation.resumeWith(outcome)
                    }
                })
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    /** Blocking, bounded read performed on OkHttp's callback thread. */
    private fun readBody(response: Response): String {
        val source = response.body?.byteStream() ?: return ""
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = source.read(chunk)
            if (read < 0) break
            total += read
            if (total > GatewayLimits.MAX_BODY_BYTES) {
                throw GatewayException("MCP response exceeded the ${GatewayLimits.MAX_BODY_BYTES} byte limit.")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray().toString(Charsets.UTF_8)
    }

    /** Accepts a plain JSON body, an SSE stream, or an SSE stream mislabelled as JSON. */
    private fun extractResponse(text: String, contentType: String?, expectedId: Long, token: String?): JSONObject {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw GatewayException("MCP server returned an empty response for request $expectedId.")
        val looksLikeSse = contentType?.contains("text/event-stream") == true ||
            trimmed.startsWith("event:") || trimmed.startsWith("data:") || trimmed.startsWith(":")
        if (!looksLikeSse) {
            val json = parseJson(trimmed)
            return readEnvelope(json, expectedId, token)
                ?: throw GatewayException("MCP response did not match request id $expectedId.")
        }
        var frames = 0
        for (event in sseEvents(trimmed)) {
            frames++
            if (frames > MAX_SSE_FRAMES) throw GatewayException("MCP SSE stream exceeded $MAX_SSE_FRAMES frames.")
            val trimmedEvent = event.trim()
            if (trimmedEvent.isEmpty() || trimmedEvent == "[DONE]") continue
            val json = runCatching { JSONObject(trimmedEvent) }.getOrNull() ?: continue
            readEnvelope(json, expectedId, token)?.let { return it }
        }
        throw GatewayException("MCP SSE stream had no response for request id $expectedId.")
    }

    /** Returns the result only when the envelope correlates with [expectedId]. */
    private fun readEnvelope(json: JSONObject, expectedId: Long, token: String?): JSONObject? {
        val rawId = if (json.has("id")) json.opt("id") else null
        if (rawId == null || rawId == JSONObject.NULL) return null
        val matches = when (rawId) {
            is Number -> rawId.toLong() == expectedId
            is String -> rawId == expectedId.toString()
            else -> false
        }
        if (!matches) return null
        val error = json.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code", -1)
            val message = GatewayLimits.redact(error.optString("message", "unknown error"), listOfNotNull(token))
            val data = error.opt("data")
                ?.takeIf { it != JSONObject.NULL }
                ?.toString()
                ?.let { GatewayLimits.redact(it, listOfNotNull(token)) }
                ?.take(200)
            throw GatewayException("MCP error $code: ${message.take(300)}" + if (data != null) " [$data]" else "")
        }
        return json.optJSONObject("result")
            ?: throw GatewayException("MCP response for request $expectedId had no result object.")
    }

    private fun parseJson(text: String): JSONObject = try {
        JSONObject(text)
    } catch (failure: Exception) {
        throw GatewayException("MCP response was not a JSON object.")
    }

    /** Splits an SSE body into `data:` payloads, joining multi-line data fields as the spec requires. */
    private fun sseEvents(body: String): List<String> {
        val events = mutableListOf<String>()
        val data = StringBuilder()
        fun flush() {
            if (data.isNotEmpty()) {
                events.add(data.toString())
                data.setLength(0)
            }
        }
        for (line in body.lineSequence()) {
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit // comment / keep-alive
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                // event:, id:, retry: are transport metadata and carry no JSON-RPC payload.
                else -> Unit
            }
        }
        flush()
        return events
    }

    private fun httpFailure(status: Int, body: String, token: String?): GatewayException {
        val reason = when (status) {
            401, 403 -> "MCP server rejected the credential (HTTP $status)."
            404 -> "MCP endpoint not found (HTTP 404)."
            429 -> "MCP server rate limited the request (HTTP 429)."
            else -> "MCP request failed with HTTP $status."
        }
        val snippet = GatewayLimits.redact(body, listOfNotNull(token)).replace(Regex("\\s+"), " ").trim().take(200)
        return GatewayException(if (snippet.isEmpty()) reason else "$reason $snippet")
    }

    private fun extractContent(result: JSONObject, maxChars: Int, token: String?): String {
        val builder = StringBuilder()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (builder.isNotEmpty()) builder.append('\n')
                when (val type = item.optString("type", "text")) {
                    "text" -> builder.append(item.optString("text"))
                    "image", "audio" -> builder.append("[$type ${item.optString("mimeType", "binary")}, ${item.optString("data").length} base64 chars]")
                    "resource" -> {
                        val resource = item.optJSONObject("resource")
                        val text = resource?.optString("text").orEmpty()
                        if (text.isNotBlank()) {
                            builder.append(text)
                        } else {
                            builder.append("[resource ${resource?.optString("uri").orEmpty()}]")
                        }
                    }

                    else -> builder.append(item.toString())
                }
            }
        } else if (result.has("content") && !result.isNull("content")) {
            builder.append(result.optString("content"))
        }
        val structured = result.optJSONObject("structuredContent")
        if (structured != null && builder.isEmpty()) builder.append(structured.toString())
        return GatewayLimits.truncate(GatewayLimits.redact(builder.toString(), listOfNotNull(token)), maxChars)
    }

    private suspend fun resolveToken(): String? {
        val reference = config.credentialRef?.takeIf { it.isNotBlank() } ?: return null
        val token = try { tokenProvider(reference) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw GatewayException("Configured MCP credential is unavailable; nothing was sent.") }
        return token?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 && it.none(Char::isISOControl) }
            ?: throw GatewayException("Configured MCP credential is unavailable or invalid; nothing was sent.")
    }

    private fun ensureOpen() {
        if (closed) throw GatewayException("MCP client is closed.")
    }

    private data class RpcPayload(
        val status: Int,
        val result: JSONObject?,
        val sessionId: String?,
        val contentType: String?,
    )

    companion object {
        /** Protocol revision NYX proposes; the server may answer with an older supported revision. */
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_VERSION = "0.7.5"
        const val USER_AGENT = "NYX/$CLIENT_VERSION (Android MCP client)"
        const val DEFAULT_INPUT_SCHEMA = """{"type":"object","properties":{}}"""
        const val MAX_TOOLS = 200
        const val MAX_TOOL_PAGES = 10
        const val MAX_SSE_FRAMES = 250
        const val MAX_DESCRIPTION_CHARS = 1_024
        const val MAX_SCHEMA_CHARS = 16_384
        private const val CHUNK_BYTES = 16 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(timeoutSeconds: Int, dispatcher: Dispatcher): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(minOf(10, timeoutSeconds).toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds.toLong() + 5, TimeUnit.SECONDS)
            .build()
    }
}

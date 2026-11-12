package com.nyx.ai.gateway

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Exercises the MCP client against an in-process MCP server that echoes JSON-RPC ids, issues a
 * session id, paginates tools/list and streams tools/call over SSE.
 */
class McpClientTest {
    private lateinit var server: MockWebServer
    private val calls = Collections.synchronizedList(mutableListOf<Recorded>())

    private data class Recorded(
        val httpMethod: String,
        val path: String,
        val method: String,
        val headers: Headers,
        val body: JSONObject,
    ) {
        val params: JSONObject? get() = body.optJSONObject("params")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `initialize handshake captures the session and sends initialized notification`() = runBlocking {
        serve()
        val client = McpClient(config(), tokenProvider())
        val tools = client.listTools()

        assertEquals(listOf("echo"), tools.map { it.name })
        assertEquals(3, calls.size)
        val init = calls[0]
        assertEquals("POST", init.httpMethod)
        assertEquals("/mcp", init.path)
        assertEquals("initialize", init.method)
        assertEquals("2.0", init.body.optString("jsonrpc"))
        assertTrue(init.body.has("id"))
        assertEquals(McpClient.PROTOCOL_VERSION, init.params?.optString("protocolVersion"))
        assertEquals("NYX", init.params?.optJSONObject("clientInfo")?.optString("name"))
        assertEquals("Bearer $TOKEN", init.headers["Authorization"])
        val accept = init.headers["Accept"].orEmpty()
        assertTrue(accept.contains("application/json"))
        assertTrue(accept.contains("text/event-stream"))
        assertTrue(init.headers["User-Agent"].orEmpty().startsWith("NYX/"))

        val notification = calls[1]
        assertEquals("notifications/initialized", notification.method)
        assertFalse(notification.body.has("id"))
        assertEquals(SESSION, notification.headers["Mcp-Session-Id"])

        val list = calls[2]
        assertEquals("tools/list", list.method)
        assertEquals(SESSION, list.headers["Mcp-Session-Id"])
        assertEquals(SESSION, client.mcpSessionId)
        assertEquals(McpClient.PROTOCOL_VERSION, client.protocolVersion)
        client.close()
    }

    @Test
    fun `tools list follows pagination cursors`() = runBlocking {
        serve(
            toolsPages = listOf(
                page(listOf(toolJson("echo")), nextCursor = "cursor-2"),
                page(listOf(toolJson("create_issue"))),
            ),
        )
        val client = McpClient(config(), tokenProvider())
        val tools = client.listTools()

        assertEquals(listOf("echo", "create_issue"), tools.map { it.name })
        assertEquals(4, calls.size)
        assertEquals("tools/list", calls[2].method)
        assertFalse(calls[2].params?.has("cursor") ?: true)
        assertEquals("cursor-2", calls[3].params?.optString("cursor"))
        client.close()
    }

    @Test
    fun `tools list rejects a non-object input schema`() = runBlocking {
        serve(toolsPages = listOf(page(listOf(toolJson("bad", schema = "not-an-object")))))
        val client = McpClient(config(), tokenProvider())
        try {
            client.listTools()
            fail("Expected a schema refusal")
        } catch (expected: GatewayException) {
            assertTrue(expected.message.orEmpty().contains("non-object input schema"))
        }
        client.close()
    }

    @Test
    fun `tools call parses SSE frames and extracts structured content`() = runBlocking {
        serve(
            callResponse = { request ->
                val id = request.opt("id")
                val stream = buildString {
                    appendLine(": keep-alive")
                    appendLine()
                    appendLine("event: message")
                    appendLine("""data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":10}}""")
                    appendLine()
                    appendLine("event: message")
                    appendLine(
                        """data: {"jsonrpc":"2.0","id":$id,"result":{"content":[""" +
                            """{"type":"text","text":"echo: hello"},""" +
                            """{"type":"image","mimeType":"image/png","data":"AAAA"}],""" +
                            """"structuredContent":{"ok":true},"isError":false}}""",
                    )
                    appendLine()
                }
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
            },
        )
        val client = McpClient(config(), tokenProvider())
        client.listTools()
        val result = client.callTool("echo", """{"text":"hello"}""")

        assertTrue(result.success)
        assertFalse(result.isError)
        assertTrue(result.content.contains("echo: hello"))
        assertTrue(result.content.contains("[image image/png, 4 base64 chars]"))
        assertTrue(result.boundedRaw.contains("structuredContent"))
        val call = calls.last()
        assertEquals("tools/call", call.method)
        assertEquals("echo", call.params?.optString("name"))
        assertEquals("hello", call.params?.optJSONObject("arguments")?.optString("text"))
        client.close()
    }

    @Test
    fun `plain json tool errors are surfaced as isError`() = runBlocking {
        serve(
            callResponse = { request ->
                json(
                    result(
                        request,
                        JSONObject()
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "boom")))
                            .put("isError", true),
                    ),
                )
            },
        )
        val client = McpClient(config(), tokenProvider())
        client.listTools()
        val result = client.callTool("echo", "{}")

        assertFalse(result.success)
        assertTrue(result.isError)
        assertEquals("boom", result.content)
        client.close()
    }

    @Test
    fun `json rpc errors are surfaced without leaking the token`() = runBlocking {
        serve(
            callResponse = { request ->
                json(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", request.opt("id"))
                        .put(
                            "error",
                            JSONObject()
                                .put("code", -32602)
                                .put("message", "rejected credential $TOKEN"),
                        ),
                )
            },
        )
        val client = McpClient(config(), tokenProvider())
        client.listTools()
        try {
            client.callTool("echo", "{}")
            fail("Expected a JSON-RPC error")
        } catch (expected: GatewayException) {
            val message = expected.message.orEmpty()
            assertTrue(message.contains("MCP error -32602"))
            assertFalse(message.contains(TOKEN))
            assertTrue(message.contains("[redacted]"))
        }
        client.close()
    }

    @Test
    fun `remote plain http endpoints are refused`() {
        val endpoints = listOf(
            "http://example.com/mcp",
            "http://192.168.1.10:8080/mcp",
            "http://[2001:db8::1]/mcp",
            "https://user:pass@example.com/mcp",
        )
        for (endpoint in endpoints) {
            try {
                McpClient(config(endpoint = endpoint), tokenProvider())
                fail("Expected a refusal for $endpoint")
            } catch (expected: GatewayException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun `loopback plain http and remote https are allowed`() {
        assertEquals("http://127.0.0.1:9/mcp", McpEndpointPolicy.validate("http://127.0.0.1:9/mcp"))
        assertEquals("http://localhost:9/mcp", McpEndpointPolicy.validate("http://localhost:9/mcp"))
        assertEquals("http://[::1]:9/mcp", McpEndpointPolicy.validate("http://[::1]:9/mcp"))
        assertEquals("https://mcp.example.com/mcp", McpEndpointPolicy.validate("https://mcp.example.com/mcp"))
        assertTrue(McpEndpointPolicy.isLoopback("localhost"))
        assertFalse(McpEndpointPolicy.isLoopback("example.com"))
        assertEquals("https://mcp.example.com/live", McpEndpointPolicy.label("https://mcp.example.com/live?key=secret"))
    }

    @Test
    fun `allowlist blocks a tool before any request is sent`() = runBlocking {
        serve()
        val client = McpClient(config(allowed = setOf("echo")), tokenProvider())
        try {
            client.callTool("write_file", "{}")
            fail("Expected an allowlist refusal")
        } catch (expected: GatewayException) {
            assertTrue(expected.message.orEmpty().contains("not allowed"))
        }
        assertEquals(0, server.requestCount)
        client.close()
    }

    @Test
    fun `response content is capped at maxResponseChars`() = runBlocking {
        serve(
            callResponse = { request ->
                json(
                    result(
                        request,
                        JSONObject().put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "text").put("text", "x".repeat(5_000))),
                        ),
                    ),
                )
            },
        )
        val client = McpClient(config(maxResponseChars = 200), tokenProvider())
        client.listTools()
        val result = client.callTool("echo", "{}")

        assertTrue(result.content.length <= 200 + GatewayToolResult.TRUNCATION_MARKER.length)
        assertTrue(result.truncated)
        client.close()
    }

    @Test
    fun `coroutine cancellation aborts an in flight call`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                val method = body.optString("method")
                calls.add(Recorded(request.method.orEmpty(), request.path.orEmpty(), method, request.headers, body))
                return when (method) {
                    "initialize" -> json(result(body, initializeResult())).setHeader("Mcp-Session-Id", SESSION)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> json(result(body, page(listOf(toolJson("echo")))))
                    "tools/call" -> json(
                        result(
                            body,
                            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "late"))),
                        ),
                    ).setBodyDelay(3, TimeUnit.SECONDS)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val client = McpClient(config(), tokenProvider())
        client.listTools()
        val job = launch {
            runCatching { client.callTool("echo", "{}") }
        }
        delay(300)
        val started = System.currentTimeMillis()
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(System.currentTimeMillis() - started < 2_500)
        client.close()
    }

    @Test
    fun `closed client refuses further work`() = runBlocking {
        serve()
        val client = McpClient(config(), tokenProvider())
        client.listTools()
        client.close()
        try {
            client.callTool("echo", "{}")
            fail("Expected a closed-client refusal")
        } catch (expected: GatewayException) {
            assertTrue(expected.message.orEmpty().contains("closed"))
        }
    }

    // ------------------------------------------------------------------ mock MCP server

    private fun serve(
        toolsPages: List<JSONObject> = listOf(page(listOf(toolJson("echo")))),
        callResponse: (JSONObject) -> MockResponse = { request ->
            json(
                result(
                    request,
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))),
                ),
            )
        },
    ) {
        var pageIndex = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                val method = body.optString("method")
                calls.add(Recorded(request.method.orEmpty(), request.path.orEmpty(), method, request.headers, body))
                return when (method) {
                    "initialize" -> json(result(body, initializeResult())).setHeader("Mcp-Session-Id", SESSION)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> {
                        val payload = toolsPages[pageIndex.coerceAtMost(toolsPages.lastIndex)]
                        pageIndex++
                        json(result(body, payload))
                    }

                    "tools/call" -> callResponse(body)
                    else -> MockResponse().setResponseCode(404).setBody("""{"jsonrpc":"2.0","error":{"code":-32601}}""")
                }
            }
        }
    }

    private fun config(
        endpoint: String = server.url("/mcp").toString(),
        allowed: Set<String> = emptySet(),
        maxResponseChars: Int = 20_000,
        timeoutSeconds: Int = 5,
        credentialRef: String? = "mcp-cred",
    ): GatewayServerConfig = GatewayServerConfig(
        id = "mock-mcp",
        name = "Mock MCP",
        kind = GatewayKind.MCP_HTTP,
        endpoint = endpoint,
        credentialRef = credentialRef,
        enabled = true,
        allowedTools = allowed,
        timeoutSeconds = timeoutSeconds,
        maxCallsPerRun = 10,
        maxResponseChars = maxResponseChars,
    )

    private fun tokenProvider(token: String = TOKEN): GatewayTokenProvider =
        { reference -> if (reference == "mcp-cred") token else null }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", McpClient.PROTOCOL_VERSION)
        .put("capabilities", JSONObject())
        .put("serverInfo", JSONObject().put("name", "mock-mcp").put("version", "1.0"))

    private fun result(request: JSONObject, payload: Any): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", request.opt("id") ?: JSONObject.NULL)
        .put("result", payload)

    private fun page(tools: List<JSONObject>, nextCursor: String? = null): JSONObject {
        val payload = JSONObject().put("tools", JSONArray(tools))
        if (nextCursor != null) payload.put("nextCursor", nextCursor)
        return payload
    }

    private fun toolJson(name: String, schema: Any = JSONObject().put("type", "object").put("properties", JSONObject())): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", "Test tool $name")
            .put("inputSchema", schema)

    private fun json(payload: JSONObject): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(payload.toString())

    companion object {
        private const val TOKEN = "test-token-123"
        private const val SESSION = "session-abc-123"
    }
}

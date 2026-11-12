package com.nyx.ai.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Verifies durable configs, per-server policy (allowlist, budgets, caps, timeouts) and log rotation. */
class GatewayRepositoryTest {
    private lateinit var root: File
    private lateinit var server: MockWebServer
    private lateinit var repository: GatewayRepository

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nyx-gateway").toFile()
        server = MockWebServer()
        server.start()
        repository = GatewayRepository(
            root = root,
            tokenProvider = provider(),
            githubBaseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        repository.close()
        server.shutdown()
        root.deleteRecursively()
    }

    @Test
    fun `configs persist across repository instances without secrets`() {
        repository.upsert(
            githubConfig(
                allowed = setOf(GitHubGateway.TOOL_CREATE_ISSUE),
                maxCalls = 3,
                maxResponseChars = 5_000,
            ),
        )
        repository.upsert(mcpConfig(allowed = setOf("echo"), maxCalls = 2))

        val configFile = File(root, GatewayRepository.CONFIG_FILE_NAME)
        assertTrue(configFile.exists())
        assertFalse(configFile.readText().contains(TOKEN))

        val reopened = GatewayRepository(root, provider(), server.url("/").toString())
        try {
            val configs = reopened.list()
            assertEquals(2, configs.size)
            val github = reopened.get("github")
            assertNotNull(github)
            requireNotNull(github)
            assertEquals(GatewayKind.GITHUB, github.kind)
            assertEquals("https://api.github.com", github.endpoint)
            assertEquals(setOf(GitHubGateway.TOOL_CREATE_ISSUE), github.allowedTools)
            assertEquals(3, github.maxCallsPerRun)
            assertEquals(5_000, github.maxResponseChars)
            assertEquals("github-cred", github.credentialRef)
            assertTrue(github.enabled)

            val mcp = reopened.get("mcp")
            requireNotNull(mcp)
            assertEquals(GatewayKind.MCP_HTTP, mcp.kind)
            assertEquals(server.url("/mcp").toString(), mcp.endpoint)
            assertEquals(setOf("echo"), mcp.allowedTools)
            assertEquals(2, mcp.maxCallsPerRun)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `enable disable and delete are persisted`() {
        repository.upsert(mcpConfig())
        assertTrue(repository.get("mcp")?.enabled ?: false)

        repository.setEnabled("mcp", false)
        assertFalse(repository.get("mcp")?.enabled ?: true)

        val afterDisable = GatewayRepository(root, provider(), server.url("/").toString())
        try {
            assertFalse(afterDisable.get("mcp")?.enabled ?: true)
        } finally {
            afterDisable.close()
        }

        assertTrue(repository.delete("mcp"))
        assertNull(repository.get("mcp"))
        val afterDelete = GatewayRepository(root, provider(), server.url("/").toString())
        try {
            assertNull(afterDelete.get("mcp"))
        } finally {
            afterDelete.close()
        }
    }

    @Test
    fun `invalid and unsafe configurations are refused`() {
        val invalid = listOf(
            mcpConfig().copy(id = "Bad Id"),
            mcpConfig().copy(maxCallsPerRun = 0),
            mcpConfig().copy(maxResponseChars = 100),
            mcpConfig().copy(timeoutSeconds = 0),
            mcpConfig(endpoint = "http://example.com/mcp"),
            mcpConfig().copy(endpoint = "https://user:pass@example.com/mcp"),
            githubConfig().copy(credentialRef = null),
        )
        for (config in invalid) {
            try {
                repository.upsert(config)
                fail("Expected rejection for ${config.id}/${config.endpoint}")
            } catch (expected: GatewayException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
        assertEquals(0, repository.list().size)
    }

    @Test
    fun `disabled gateway refuses calls without touching the network`() = runBlocking {
        installMcpServer()
        repository.upsert(mcpConfig(enabled = false))

        val result = repository.callTool("mcp", "echo", """{"text":"hi"}""")

        assertFalse(result.success)
        assertTrue(result.content.contains("disabled"))
        assertEquals(0, server.requestCount)
        val logs = repository.logs()
        assertEquals(1, logs.size)
        assertEquals("mcp", logs[0].serverId)
        assertFalse(logs[0].success)
    }

    @Test
    fun `allowlist blocks unlisted tools and filters listTools`() = runBlocking {
        installMcpServer()
        repository.upsert(mcpConfig(allowed = setOf("echo")))

        val denied = repository.callTool("mcp", "write_file", "{}")
        assertFalse(denied.success)
        assertTrue(denied.content.contains("not allowed"))
        assertEquals(0, server.requestCount)

        assertEquals(listOf("echo"), repository.listTools("mcp").map { it.name })
        val allowed = repository.callTool("mcp", "echo", """{"text":"hi"}""")
        assertTrue(allowed.success)
        assertEquals("mcp-ok", allowed.content)
    }

    @Test
    fun `maxCallsPerRun is enforced per run scope`() = runBlocking {
        installMcpServer()
        repository.upsert(mcpConfig(maxCalls = 2))

        repository.beginRun("run-1")
        assertTrue(repository.callTool("mcp", "echo", "{}", "run-1").success)
        assertTrue(repository.callTool("mcp", "echo", "{}", "run-1").success)
        val third = repository.callTool("mcp", "echo", "{}", "run-1")
        assertFalse(third.success)
        assertTrue(third.content.contains("exhausted"))
        assertEquals(2, repository.callsUsed("run-1"))

        repository.beginRun("run-2")
        assertTrue(repository.callTool("mcp", "echo", "{}", "run-2").success)
        repository.endRun("run-2")
        assertEquals(0, repository.callsUsed("run-2"))
    }

    @Test
    fun `call budgets are tracked per server within a run`() = runBlocking {
        installMcpServer()
        repository.upsert(mcpConfig(id = "mcp-a", maxCalls = 1))
        repository.upsert(mcpConfig(id = "mcp-b", maxCalls = 1))
        repository.beginRun("shared-run")

        assertTrue(repository.callTool("mcp-a", "echo", "{}", "shared-run").success)
        assertTrue(repository.callTool("mcp-b", "echo", "{}", "shared-run").success)
        val exhausted = repository.callTool("mcp-a", "echo", "{}", "shared-run")

        assertFalse(exhausted.success)
        assertTrue(exhausted.content.contains("exhausted"))
        assertEquals(1, repository.callsUsed("shared-run", "mcp-a"))
        assertEquals(1, repository.callsUsed("shared-run", "mcp-b"))
        assertEquals(2, repository.callsUsed("shared-run"))
    }

    @Test
    fun `response cap truncates gateway content and log summary`() = runBlocking {
        installMcpServer(text = "y".repeat(5_000))
        repository.upsert(mcpConfig(maxResponseChars = 300))

        val result = repository.callTool("mcp", "echo", "{}")

        assertTrue(result.success)
        assertTrue(result.content.length <= 300 + GatewayToolResult.TRUNCATION_MARKER.length)
        assertTrue(result.truncated)
        val entry = repository.logs().last()
        assertTrue(entry.resultSummary.length <= GatewayLimits.MAX_LOG_RESULT_CHARS)
        assertTrue(entry.argsSummary.length <= GatewayLimits.MAX_LOG_ARGS_CHARS)
    }

    @Test
    fun `oversized arguments are refused before dispatch`() = runBlocking {
        repository.upsert(mcpConfig())
        val result = repository.callTool("mcp", "echo", "x".repeat(GatewayLimits.MAX_ARGUMENT_CHARS + 1))
        assertFalse(result.success)
        assertTrue(result.content.contains("exceed"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unknown gateway is refused`() = runBlocking {
        val result = repository.callTool("missing", "echo", "{}")
        assertFalse(result.success)
        assertTrue(result.content.contains("not configured"))
    }

    @Test
    fun `github publishing requires a credential and sends nothing without one`() = runBlocking {
        repository.upsert(githubConfig())
        val withoutToken = GatewayRepository(root, provider(token = null), server.url("/").toString())
        try {
            val result = withoutToken.callTool(
                "github",
                GitHubGateway.TOOL_CREATE_ISSUE,
                """{"owner":"nyx","repo":"app","title":"t"}""",
            )
            assertFalse(result.success)
            assertTrue(result.content.contains("credential"))
            assertEquals(0, server.requestCount)
        } finally {
            withoutToken.close()
        }
    }

    @Test
    fun `github publishing is authorized and logged without the token`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"number":7,"html_url":"https://github.com/nyx/app/issues/7"}"""),
        )
        repository.upsert(githubConfig(allowed = setOf(GitHubGateway.TOOL_CREATE_ISSUE)))

        val result = repository.callTool(
            "github",
            GitHubGateway.TOOL_CREATE_ISSUE,
            """{"owner":"nyx","repo":"app","title":"Gateway report","body":"Created by NYX"}""",
        )

        assertTrue(result.success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/nyx/app/issues", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        val entry = repository.logs().last()
        assertEquals(GitHubGateway.TOOL_CREATE_ISSUE, entry.tool)
        assertTrue(entry.success)
        assertTrue(entry.argsSummary.contains("Gateway report"))
        assertFalse(entry.argsSummary.contains(TOKEN))
        assertFalse(entry.resultSummary.contains(TOKEN))
    }

    @Test
    fun `log file is capped at 300 entries`() = runBlocking {
        repository.upsert(mcpConfig(enabled = false))
        repeat(GatewayLimits.LOG_CAP + 10) {
            repository.callTool("mcp", "echo", "{}")
        }

        assertEquals(GatewayLimits.LOG_CAP, repository.logCount())
        assertEquals(GatewayLimits.LOG_CAP, repository.logs(1_000).size)
        val lines = File(root, GatewayRepository.LOG_FILE_NAME).readLines().filter { it.isNotBlank() }
        assertEquals(GatewayLimits.LOG_CAP, lines.size)
    }

    @Test
    fun `timeout produces a bounded failure`() = runBlocking {
        installMcpServer(bodyDelayMs = 3_000)
        repository.upsert(mcpConfig(timeoutSeconds = 1))

        val started = System.currentTimeMillis()
        val result = repository.callTool("mcp", "echo", "{}")
        val elapsed = System.currentTimeMillis() - started

        assertFalse(result.success)
        assertTrue(result.content.contains("timed out"))
        assertTrue("elapsed=$elapsed", elapsed < 5_000)
    }

    @Test
    fun `health reports disabled without network`() = runBlocking {
        repository.upsert(mcpConfig(enabled = false))
        val health = repository.health("mcp")
        assertEquals(GatewayHealthState.DISABLED, health.state)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `health reports misconfigured when the credential cannot be resolved`() = runBlocking {
        repository.upsert(mcpConfig(credentialRef = "missing-cred"))
        val health = repository.health("mcp")
        assertEquals(GatewayHealthState.MISCONFIGURED, health.state)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ fixtures

    private fun mcpConfig(
        id: String = "mcp",
        enabled: Boolean = true,
        allowed: Set<String> = emptySet(),
        maxCalls: Int = 10,
        maxResponseChars: Int = 20_000,
        timeoutSeconds: Int = 5,
        credentialRef: String? = "mcp-cred",
        endpoint: String = server.url("/mcp").toString(),
    ): GatewayServerConfig = GatewayServerConfig(
        id = id,
        name = "Mock MCP $id",
        kind = GatewayKind.MCP_HTTP,
        endpoint = endpoint,
        credentialRef = credentialRef,
        enabled = enabled,
        allowedTools = allowed,
        timeoutSeconds = timeoutSeconds,
        maxCallsPerRun = maxCalls,
        maxResponseChars = maxResponseChars,
    )

    private fun githubConfig(
        enabled: Boolean = true,
        allowed: Set<String> = emptySet(),
        maxCalls: Int = 10,
        maxResponseChars: Int = 20_000,
        credentialRef: String? = "github-cred",
    ): GatewayServerConfig = GatewayServerConfig(
        id = "github",
        name = "GitHub",
        kind = GatewayKind.GITHUB,
        endpoint = "https://api.github.com",
        credentialRef = credentialRef,
        enabled = enabled,
        allowedTools = allowed,
        timeoutSeconds = 5,
        maxCallsPerRun = maxCalls,
        maxResponseChars = maxResponseChars,
    )

    private fun provider(token: String? = TOKEN): GatewayTokenProvider =
        { reference -> if (reference == "mcp-cred" || reference == "github-cred") token else null }

    private fun installMcpServer(text: String = "mcp-ok", bodyDelayMs: Long = 0) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                return when (body.optString("method")) {
                    "initialize" -> json(
                        envelope(
                            body,
                            JSONObject()
                                .put("protocolVersion", McpClient.PROTOCOL_VERSION)
                                .put("capabilities", JSONObject())
                                .put("serverInfo", JSONObject().put("name", "mock").put("version", "1")),
                        ),
                    ).setHeader("Mcp-Session-Id", "session-1")

                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> json(
                        envelope(
                            body,
                            JSONObject().put(
                                "tools",
                                JSONArray()
                                    .put(tool("echo"))
                                    .put(tool("write_file")),
                            ),
                        ),
                    )

                    "tools/call" -> {
                        val name = body.optJSONObject("params")?.optString("name")
                        val response = json(
                            envelope(
                                body,
                                JSONObject().put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", if (name == "write_file") "written" else text),
                                    ),
                                ),
                            ),
                        )
                        if (bodyDelayMs > 0) response.setBodyDelay(bodyDelayMs, TimeUnit.MILLISECONDS)
                        response
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun tool(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("description", "Test tool")
        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))

    private fun envelope(request: JSONObject, payload: Any): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", request.opt("id") ?: JSONObject.NULL)
        .put("result", payload)

    private fun json(payload: JSONObject): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(payload.toString())

    companion object {
        private const val TOKEN = "ghp_repository_secret_456"
    }
}

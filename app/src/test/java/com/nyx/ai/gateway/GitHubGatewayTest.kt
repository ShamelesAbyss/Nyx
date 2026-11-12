package com.nyx.ai.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Verifies the native GitHub REST integration: auth header placement, payloads, diff cap, redaction. */
class GitHubGatewayTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `repository read sends the bearer token only in the authorization header`() = runBlocking {
        server.enqueue(
            json(
                """{"full_name":"nyx/app","default_branch":"main","private":false}""",
            ),
        )
        val result = gateway().callTool(
            GitHubGateway.TOOL_REPOSITORY,
            """{"owner":"nyx","repo":"app"}""",
        )
        val request = server.takeRequest()

        assertEquals("GET", request.method)
        assertEquals("/repos/nyx/app", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals(GitHubGateway.API_VERSION, request.getHeader("X-GitHub-Api-Version"))
        assertTrue(request.requestUrl.toString().let { !it.contains(TOKEN) && !it.contains("access_token") })
        assertTrue(result.success)
        assertFalse(result.isError)
        assertTrue(result.content.contains("full_name"))
        assertFalse(result.content.contains(TOKEN))
    }

    @Test
    fun `create issue posts an authorized json body`() = runBlocking {
        server.enqueue(
            json("""{"number":7,"html_url":"https://github.com/nyx/app/issues/7"}""", code = 201),
        )
        val result = gateway().callTool(
            GitHubGateway.TOOL_CREATE_ISSUE,
            """{"owner":"nyx","repo":"app","title":"Gateway report","body":"Created by NYX","labels":["automation"]}""",
        )
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/repos/nyx/app/issues", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("Gateway report", payload.optString("title"))
        assertEquals("Created by NYX", payload.optString("body"))
        assertEquals("automation", payload.optJSONArray("labels")?.optString(0))
        assertTrue(result.success)
        assertTrue(result.content.contains("\"number\":7"))
    }

    @Test
    fun `api error bodies are surfaced without the token`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"Bad credentials for $TOKEN"}"""),
        )
        try {
            gateway().callTool(GitHubGateway.TOOL_CREATE_ISSUE, """{"owner":"nyx","repo":"app","title":"t"}""")
            fail("Expected an API error")
        } catch (expected: GatewayException) {
            val message = expected.message.orEmpty()
            assertTrue(message.contains("HTTP 403"))
            assertFalse(message.contains(TOKEN))
            assertTrue(message.contains("[redacted]"))
        }
    }

    @Test
    fun `missing credential refuses before any network call`() = runBlocking {
        val gateway = gateway(provider = { null })
        try {
            gateway.callTool(GitHubGateway.TOOL_REPOSITORY, """{"owner":"nyx","repo":"app"}""")
            fail("Expected a credential refusal")
        } catch (expected: GatewayException) {
            assertTrue(expected.message.orEmpty().contains("credential"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `diff requests the diff media type and caps the body`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/plain").setBody("d".repeat(400_000)),
        )
        val result = gateway(maxResponseChars = 400_000).callTool(
            GitHubGateway.TOOL_DIFF,
            """{"owner":"nyx","repo":"app","number":12}""",
        )
        val request = server.takeRequest()

        assertEquals("application/vnd.github.diff", request.getHeader("Accept"))
        assertEquals("/repos/nyx/app/pulls/12", request.path)
        assertEquals(GitHubGateway.MAX_DIFF_BYTES + GatewayToolResult.TRUNCATION_MARKER.length, result.content.length)
        assertTrue(result.truncated)
        assertTrue(result.content.endsWith(GatewayToolResult.TRUNCATION_MARKER))
    }

    @Test
    fun `contents decodes a base64 text file`() = runBlocking {
        val plain = "hello nyx\nline two\n"
        val encoded = java.util.Base64.getMimeEncoder(20, "\n".toByteArray()).encodeToString(plain.toByteArray())
        server.enqueue(
            json(
                JSONObject()
                    .put("type", "file")
                    .put("encoding", "base64")
                    .put("path", "README.md")
                    .put("content", encoded)
                    .toString(),
            ),
        )
        val result = gateway().callTool(
            GitHubGateway.TOOL_CONTENTS,
            """{"owner":"nyx","repo":"app","path":"README.md"}""",
        )

        assertEquals("/repos/nyx/app/contents/README.md", server.takeRequest().path)
        val payload = JSONObject(result.content)
        assertTrue(payload.optBoolean("decoded"))
        assertEquals("utf-8", payload.optString("encoding"))
        assertEquals(plain, payload.optString("content"))
    }

    @Test
    fun `pull request publishing sends only safe fields`() = runBlocking {
        server.enqueue(json("""{"number":9,"state":"open"}""", code = 201))
        val result = gateway().callTool(
            GitHubGateway.TOOL_CREATE_PULL_REQUEST,
            """{"owner":"nyx","repo":"app","title":"Feature","head":"feature/x","base":"main","body":"b","draft":true,"force":true,"merge":true}""",
        )
        val request = server.takeRequest()
        val payload = JSONObject(request.body.readUtf8())

        assertEquals("/repos/nyx/app/pulls", request.path)
        assertEquals("Feature", payload.optString("title"))
        assertEquals("feature/x", payload.optString("head"))
        assertEquals("main", payload.optString("base"))
        assertTrue(payload.optBoolean("draft"))
        assertEquals(setOf("title", "head", "base", "body", "draft"), payload.keys().asSequence().toSet())
        assertTrue(result.success)
    }

    @Test
    fun `identical head and base are refused before the network`() = runBlocking {
        try {
            gateway().callTool(
                GitHubGateway.TOOL_CREATE_PULL_REQUEST,
                """{"owner":"nyx","repo":"app","title":"t","head":"main","base":"main"}""",
            )
            fail("Expected a branch refusal")
        } catch (expected: GatewayException) {
            assertTrue(expected.message.orEmpty().contains("must differ"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `repository traversal and unknown tools are refused`() = runBlocking {
        val gateway = gateway()
        val attempts = listOf(
            GitHubGateway.TOOL_CONTENTS to """{"owner":"nyx","repo":"app","path":"../../etc/passwd"}""",
            "github_delete_repository" to """{"owner":"nyx","repo":"app"}""",
            GitHubGateway.TOOL_ISSUES to """{"owner":"nyx","repo":"app","state":"merged"}""",
        )
        for ((tool, args) in attempts) {
            try {
                gateway.callTool(tool, args)
                fail("Expected a refusal for $tool")
            } catch (expected: GatewayException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tool catalog separates read and publish tools with object schemas`() = runBlocking {
        val catalog = gateway().listTools()

        assertEquals(11, catalog.size)
        assertEquals(catalog.size, catalog.map { it.name }.toSet().size)
        assertTrue(GitHubGateway.READ_TOOLS.intersect(GitHubGateway.PUBLISH_TOOLS).isEmpty())
        assertEquals(8, GitHubGateway.READ_TOOLS.size)
        assertEquals(3, GitHubGateway.PUBLISH_TOOLS.size)
        for (tool in catalog) {
            val schema = JSONObject(tool.inputSchemaJson)
            assertEquals("object", schema.optString("type"))
            assertTrue(schema.optJSONObject("properties") != null)
            assertFalse(tool.name.contains("delete"))
            assertFalse(tool.name.contains("force"))
            assertFalse(tool.name.contains("merge"))
        }
    }

    @Test
    fun `listTools respects the configured allowlist`() = runBlocking {
        val gateway = gateway(allowed = setOf(GitHubGateway.TOOL_ISSUES))
        assertEquals(listOf(GitHubGateway.TOOL_ISSUES), gateway.listTools().map { it.name })

        try {
            gateway.callTool(GitHubGateway.TOOL_REPOSITORY, """{"owner":"nyx","repo":"app"}""")
            fail("Expected an allowlist refusal")
        } catch (expected: GatewayException) {
            assertTrue(expected.message.orEmpty().contains("not allowed"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `health reports missing credentials without calling the api`() = runBlocking {
        val gateway = gateway(credentialRef = null)
        val health = gateway.health()
        assertEquals(GatewayHealthState.MISCONFIGURED, health.state)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `health reads the rate limit for a configured credential`() = runBlocking {
        server.enqueue(json("""{"resources":{"core":{"limit":5000,"remaining":42}}}"""))
        val health = gateway().health()
        assertEquals(GatewayHealthState.HEALTHY, health.state)
        assertEquals("/rate_limit", server.takeRequest().path)
        assertTrue(health.detail.contains("42"))
        assertEquals(11, health.toolCount)
    }

    // ------------------------------------------------------------------ helpers

    private fun gateway(
        allowed: Set<String> = emptySet(),
        provider: GatewayTokenProvider = { reference -> if (reference == "github-cred") TOKEN else null },
        maxResponseChars: Int = 20_000,
        credentialRef: String? = "github-cred",
    ): GitHubGateway = GitHubGateway(
        GatewayServerConfig(
            id = "github",
            name = "GitHub",
            kind = GatewayKind.GITHUB,
            endpoint = "https://api.github.com",
            credentialRef = credentialRef,
            enabled = true,
            allowedTools = allowed,
            timeoutSeconds = 5,
            maxCallsPerRun = 10,
            maxResponseChars = maxResponseChars,
        ),
        provider,
        baseUrl = server.url("/").toString(),
    )

    private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    companion object {
        private const val TOKEN = "ghp_test_secret_token_123"
    }
}

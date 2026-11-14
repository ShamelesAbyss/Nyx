package com.nyx.ai.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class BedrockGatewaySecurityTest {
    private fun config(endpoint: String, ref: String? = "gateway:fixture") = GatewayServerConfig("fixture", "Fixture", GatewayKind.MCP_HTTP, endpoint, ref, enabled = true)

    @Test fun `query userinfo fragment and malformed MCP endpoints refused directly and in metadata`() {
        listOf("https://mcp.example/mcp?token=fixture-secret", "https://mcp.example/mcp?", "https://user:secret@mcp.example/mcp",
            "https://mcp.example/mcp#secret", "https://mcp.example:0/mcp", "https://mcp.example:65536/mcp", "http://192.168.1.2/mcp").forEach {
            assertThrows(GatewayException::class.java) { McpClient(config(it), { "fixture-key" }) }
            assertFalse(config(it).isValid())
        }
        assertTrue(config("https://mcp.example/mcp").isValid())
        assertTrue(config("http://127.0.0.1:11435/mcp").isValid())
    }

    @Test fun `save refuses query token and reload skips without rewriting existing secret metadata`() {
        val root = Files.createTempDirectory("nyx-bedrock-gateway-security-").toFile()
        try {
            val repository = GatewayRepository(root, { "fixture-key" })
            val unsafe = config("https://mcp.example/mcp?token=fixture-secret")
            assertThrows(GatewayException::class.java) { repository.upsert(unsafe) }
            assertFalse(File(root, GatewayRepository.CONFIG_FILE_NAME).exists())
            val bytes = JSONObject().put("version", 1).put("servers", JSONArray().put(unsafe.toJson())).toString().toByteArray()
            val file = File(root, GatewayRepository.CONFIG_FILE_NAME)
            file.writeBytes(bytes)
            assertTrue(repository.reload().isEmpty())
            assertArrayEquals(bytes, file.readBytes())
            repository.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun `direct configured missing credential never downgrades to anonymous request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = McpClient(config(server.url("/mcp").toString()), { null })
            try {
                assertThrows(GatewayException::class.java) { runBlocking { client.listTools() } }
                assertEquals(0, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun `credential provider cancellation propagates without any request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = McpClient(config(server.url("/mcp").toString()), { throw CancellationException("fixture cancellation") })
            try {
                assertThrows(CancellationException::class.java) { runBlocking { client.listTools() } }
                assertEquals(0, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun `loopback MCP HTTP credential route cannot be rebound off device`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val dns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("192.168.1.2")) }
            val client = McpClient(config(server.url("/mcp").toString()), { "fixture-key" }, OkHttpClient.Builder().dns(dns).build())
            try {
                assertThrows(GatewayException::class.java) { runBlocking { client.listTools() } }
                assertEquals(0, server.requestCount)
            } finally { client.close() }
        }
    }
}

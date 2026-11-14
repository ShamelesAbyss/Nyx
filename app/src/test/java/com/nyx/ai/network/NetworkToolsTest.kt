package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** These local HTTP fixtures are not production public-fetch permission or real model tests. */
class NetworkToolsTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun `fetch text returns body and final url`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("hello nyx"))
        val page = NetworkTools.forTests().fetchText(server.url("/page").toString())
        assertEquals("hello nyx", page.text)
        assertEquals(200, page.status)
        assertTrue(page.finalUrl.contains("/page"))
        assertTrue(page.contentType.orEmpty().startsWith("text/plain"))
    }

    @Test fun `fetch truncates at the byte cap`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("abcdefghij"))
        val page = NetworkTools.forTests().fetchText(server.url("/big").toString(), maxBytes = 4)
        assertEquals("abcd", page.text)
        assertTrue(page.truncated)
    }

    @Test fun `fetch rejects binary content`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("binary"))
        try { NetworkTools.forTests().fetchText(server.url("/bin").toString()); throw AssertionError("Expected refusal") }
        catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("non-text")) }
    }

    @Test fun `download writes the file and hashes the exact bytes`(): Unit = runBlocking {
        val payload = "nyx-download-payload"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(payload))
        val directory = Files.createTempDirectory("nyx-bedrock-net-download-").toFile()
        try {
            val destination = File(directory, "artifact.txt")
            val result = NetworkTools.forTests().download(server.url("/artifact").toString(), destination)
            assertEquals(payload, destination.readText())
            assertEquals(payload.toByteArray().size.toLong(), result.bytes)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }, result.sha256)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `oversized download is refused and leaves no partial file`(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("0123456789"))
        val directory = Files.createTempDirectory("nyx-bedrock-net-cap-").toFile()
        try {
            try { NetworkTools.forTests().download(server.url("/large").toString(), File(directory, "large.bin"), maxBytes = 4); throw AssertionError("Expected refusal") }
            catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("exceeds")) }
            assertEquals(0, directory.listFiles().orEmpty().size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `redirect to a private address is refused`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.5/secret"))
        try { NetworkTools.forTests().fetchText(server.url("/redirect").toString()); throw AssertionError("Expected refusal") }
        catch (_: IOException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun `untrusted block marks source and caps entire output`() {
        val page = FetchedPage("https://example.com", "https://example.com", 200, "text/plain", "data".repeat(100), false)
        val block = page.asUntrustedBlock(256)
        assertTrue(block.startsWith("UNTRUSTED EXTERNAL EVIDENCE"))
        assertTrue(block.contains("Source: https://example.com"))
        assertTrue(block.contains("<<<NYX_UNTRUSTED"))
        assertTrue(block.length <= 256)
        assertTrue(!block.contains("data".repeat(100)))
    }
}

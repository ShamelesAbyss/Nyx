package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilityTest {
    @Test
    fun detectsLocalHostsAndRejectsRemoteOrInvalidUrls() {
        assertTrue(ProviderFactory.isLocalUrl("http://127.0.0.1:11434"))
        assertTrue(ProviderFactory.isLocalUrl("http://localhost:11434"))
        assertTrue(ProviderFactory.isLocalUrl("http://[::1]:11434"))
        assertFalse(ProviderFactory.isLocalUrl("http://10.0.2.2:11434"))
        assertFalse(ProviderFactory.isLocalUrl("http://inference-box.local:11434"))
        assertFalse(ProviderFactory.isLocalUrl("https://api.example.com"))
        assertFalse(ProviderFactory.isLocalUrl("not a URL"))
    }

    @Test
    fun recognizesOpenAiV1PathWithTrailingSlashCaseInsensitively() {
        assertTrue(ProviderFactory.isOpenAiCompatible("https://api.example.com/V1/"))
        assertTrue(ProviderFactory.isOpenAiCompatible("https://api.example.com/v1///?key=value"))
        assertFalse(ProviderFactory.isOpenAiCompatible("https://api.example.com/v2"))
        assertFalse(ProviderFactory.isOpenAiCompatible("not a URL/v1"))
    }

    @Test
    fun factorySelectsProviderKindFromBaseUrl() {
        val compatible = ProviderFactory.from("compat", "Compatible", "https://api.example.com/v1/", null)
        val ollama = ProviderFactory.from("ollama", "Ollama", "http://localhost:11434", null)

        assertTrue(compatible is OpenAiCompatibleInferenceProvider)
        assertEquals("openai-compatible", compatible.kind)
        assertTrue(ollama is OllamaInferenceProvider)
        assertEquals("ollama", ollama.kind)
    }

    @Test
    fun capabilitiesAreHealthyAndCountModelsFromTagsResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"alpha"},{"name":"beta"}]}"""))
            val provider = OllamaInferenceProvider(
                id = "local-test",
                displayName = "Local test",
                client = OllamaClient(server.url("/").toString()),
                local = true,
                knownContextLength = 8192,
            )

            val capabilities = provider.capabilities()

            assertTrue(capabilities.healthy)
            assertTrue(capabilities.toolCalling)
            assertTrue(capabilities.streaming)
            assertEquals(2, capabilities.modelCount)
            assertEquals(8192, capabilities.contextLength)
            assertEquals(1, capabilities.maxConcurrentRequests)
            assertNull(capabilities.detail)
            assertEquals("/api/tags", server.takeRequest().path)
        }
    }

    @Test
    fun capabilitiesAreUnhealthyOnServerErrorWithoutExposingResponseBody() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(500).setBody("private token should not leak"))
            val provider = OllamaInferenceProvider(
                id = "remote-test",
                displayName = "Remote test",
                client = OllamaClient(server.url("/").toString()),
                local = false,
            )

            val capabilities = provider.capabilities()

            assertFalse(capabilities.healthy)
            assertEquals(0, capabilities.modelCount)
            assertEquals(2, capabilities.maxConcurrentRequests)
            assertNotNull(capabilities.detail)
            assertFalse(capabilities.detail.orEmpty().contains("private token"))
            assertEquals("/api/tags", server.takeRequest().path)
        }
    }

    @Test
    fun toolSupportCacheOverridesUnknownDefaultForProviderAndModel() {
        val cache = ProviderCapabilityCache()
        assertNull(cache.toolSupport("provider", "model"))
        cache.recordToolSupport("provider", "model", true)
        assertEquals(true, cache.toolSupport("provider", "model"))
        cache.recordToolSupport("provider", "model", false)
        assertEquals(false, cache.toolSupport("provider", "model"))
        assertNull(cache.toolSupport("provider", "other-model"))
    }

    @Test
    fun cacheEvictsOldestEntriesAfter256Records() {
        val cache = ProviderCapabilityCache()
        repeat(257) { index -> cache.recordToolSupport("provider", "model-$index", true) }

        assertNull(cache.toolSupport("provider", "model-0"))
        assertEquals(true, cache.toolSupport("provider", "model-1"))
        assertEquals(true, cache.toolSupport("provider", "model-256"))
    }

    @Test
    fun unsupportedToolExceptionRecordsFalseSupportAndIsRethrown() = runBlocking {
        providerCapabilityCache.clear()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"model does not support tools"}"""),
            )
            val provider = OllamaInferenceProvider(
                id = "no-tools",
                displayName = "No tools",
                client = OllamaClient(server.url("/").toString()),
                local = true,
            )
            val tools = JSONArray().put(
                JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject().put("name", "test_tool")),
            )

            try {
                provider.chat("model-a", emptyList(), tools, InferenceOptions())
                throw AssertionError("Expected ToolCallingUnsupportedException")
            } catch (_: ToolCallingUnsupportedException) {
                // The original exception must pass through after support is recorded.
            }

            assertEquals(false, providerCapabilityCache.toolSupport("no-tools", "model-a"))
            assertEquals("/api/chat", server.takeRequest().path)
        }
    }
}

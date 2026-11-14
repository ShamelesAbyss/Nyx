package com.nyx.ai.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local scripted HTTP only: not a real Ollama endpoint or measured model benchmark. */
class BedrockProviderEvidenceTest {
    private fun tools() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "fixture_tool")))

    @Test fun explicitToolRejectionIsRecordedOnlyForTheActualRequestConfiguration() = runBlocking {
        ModelCapabilities.store().clear()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"model does not support tools"}"""))
            val provider = OllamaInferenceProvider("bedrock-rejection", "HTTP fake", OllamaClient(server.url("/").toString()), true, runtimeRevision = "r1")
            val options = InferenceOptions(contextLength = 2048, cpuThreads = 2, maxOutputTokens = 384)
            val requestTools = tools()
            try { provider.chat("fixture", emptyList(), requestTools, options); fail("Explicit unsupported response must propagate") }
            catch (_: ToolCallingUnsupportedException) { }
            val actual = ModelIdentity.fromRequest(provider, "fixture", options, requestTools)
            val learned = requireNotNull(ModelCapabilities.store().get(actual))
            assertEquals(CapabilitySupport.UNSUPPORTED, learned.nativeTools)
            assertEquals(CapabilitySupport.UNKNOWN, learned.textActions)
            assertEquals(CapabilitySupport.UNKNOWN, learned.structuredOutput)
            assertNull(ModelCapabilities.store().get(actual.copy(contextLength = 8192)))
            assertNull(ModelCapabilities.store().get(actual.copy(runtimeRevision = "changed")))
            assertNull(ModelCapabilities.store().get(ModelIdentity.fromRequest(provider, "fixture", options, null)))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun genericHttpFailuresDoNotDisproveToolsOrClaimValidatedTextFallback() = runBlocking {
        ModelCapabilities.store().clear()
        MockWebServer().use { server ->
            server.start()
            val provider = OllamaInferenceProvider("bedrock-transient", "HTTP fake", OllamaClient(server.url("/").toString()), true)
            val options = InferenceOptions()
            val requestTools = tools()
            for (code in listOf(401, 403, 500)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("private failure"))
                try { provider.chat("fixture", emptyList(), requestTools, options); fail("HTTP failure must propagate") }
                catch (_: InferenceHttpException) { }
                assertNull(ModelCapabilities.store().get(ModelIdentity.fromRequest(provider, "fixture", options, requestTools)))
            }
        }
    }

    @Test fun adapterAvailabilityAndHealthyInventoryNeverBecomeModelToolSupport() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = OllamaInferenceProvider("bedrock-inventory", "HTTP fake", OllamaClient(server.url("/").toString()), true)
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"fixture"}]}"""))
            var capabilities = provider.capabilities()
            assertTrue(capabilities.toolCalling)
            assertTrue(capabilities.healthy)
            assertEquals(CapabilitySupport.UNKNOWN, capabilities.modelToolCalling)
            server.enqueue(MockResponse().setResponseCode(500).setBody("secret-body"))
            capabilities = provider.capabilities()
            assertFalse(capabilities.healthy)
            assertEquals(CapabilitySupport.UNKNOWN, capabilities.modelToolCalling)
            assertFalse(capabilities.detail.orEmpty().contains("secret-body"))
        }
    }

    @Test fun sameProviderIdAtDifferentEndpointOrRuntimeDoesNotShareFingerprint() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            val a = OllamaInferenceProvider("same-id", "a", OllamaClient(first.url("/").toString()), true, runtimeRevision = "one")
            val b = OllamaInferenceProvider("same-id", "b", OllamaClient(second.url("/").toString()), true, runtimeRevision = "one")
            val c = OllamaInferenceProvider("same-id", "c", OllamaClient(first.url("/").toString()), true, runtimeRevision = "two")
            val identities = listOf(a, b, c).map { ModelIdentity.fromRequest(it, "fixture", InferenceOptions()).fingerprint() }
            assertEquals(3, identities.toSet().size)
            assertFalse(a.capabilityRevision.contains(first.url("/").toString()))
        } }
    }
}

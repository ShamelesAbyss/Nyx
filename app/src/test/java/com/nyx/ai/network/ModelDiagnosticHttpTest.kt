package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelDiagnosticHttpTest {
    @Test fun explicitOllamaDiagnosticListsThenSendsOneBoundedSchemaRequestAndReportsRealCounts() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"models\":[{\"name\":\"tiny\"}]}"))
            server.enqueue(MockResponse().setBody("""{"message":{"content":"{\"ok\":"},"done":false}
{"message":{"content":"true}"},"done":false}
{"done":true,"prompt_eval_count":42,"eval_count":5,"eval_duration":1000000}
"""))
            val provider = ProviderFactory.from("local", "Local", server.url("/").toString(), "must-not-leak")
            assertEquals(0, server.requestCount)
            val result = ModelDiagnostic.run(provider, "tiny", InferenceOptions(contextLength = 8192, cpuThreads = 6))
            assertEquals(2, server.requestCount)
            assertEquals("/api/tags", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val chat = server.takeRequest(2, TimeUnit.SECONDS)!!
            val body = JSONObject(chat.body.readUtf8())
            assertEquals("/api/chat", chat.path); assertNull(chat.getHeader("Authorization"))
            assertEquals(32, body.getJSONObject("options").getInt("num_predict"))
            assertEquals(8192, body.getJSONObject("options").getInt("num_ctx"))
            assertEquals(6, body.getJSONObject("options").getInt("num_thread"))
            assertTrue(body.getJSONObject("format").getJSONArray("required").toString().contains("ok"))
            assertFalse(body.has("tools")); assertFalse(body.has("max_tokens")); assertFalse(body.has("response_format"))
            assertEquals(DiagnosticCheck.PASSED, result.tinyJsonResponse)
            assertEquals(DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED, result.formatCompatibility)
            assertEquals(42L, result.stats!!.promptTokens); assertEquals(5L, result.stats.outputTokens)
            assertNotNull(result.firstResponseLatencyMillis)
        }
    }

    @Test fun compatibleDiagnosticUsesMaxTokensAndNeverClaimsSchemaEnforcement() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"tiny\"}]}"))
            server.enqueue(MockResponse().setBody("""data: {"choices":[{"delta":{"content":"{\"ok\":true}"}}]}

data: [DONE]

"""))
            val provider = ProviderFactory.from("compat", "Compatible", server.url("/v1").toString(), "test-key")
            val result = ModelDiagnostic.run(provider, "tiny")
            assertEquals(2, server.requestCount)
            assertEquals("/v1/models", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val chat = server.takeRequest(2, TimeUnit.SECONDS)!!
            val body = JSONObject(chat.body.readUtf8())
            assertEquals("Bearer test-key", chat.getHeader("Authorization"))
            assertEquals(32, body.getInt("max_tokens"))
            assertFalse(body.has("options")); assertFalse(body.has("format")); assertFalse(body.has("response_format"))
            assertEquals(DiagnosticFormatCompatibility.PROMPT_JSON_SAMPLE_PASSED, result.formatCompatibility)
            assertNull(result.stats)
            assertTrue(result.summary.contains("unavailable"))
        }
    }

    @Test fun formatRejectionIsNotRetriedOrConvertedToPromptOnlySuccess() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"models\":[{\"name\":\"tiny\"}]}"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"format schema unsupported; private-server-detail\"}"))
            val provider = ProviderFactory.from("local", "Local", server.url("/").toString(), null)
            val result = ModelDiagnostic.run(provider, "tiny")
            assertEquals(2, server.requestCount)
            assertEquals(DiagnosticCheck.PASSED, result.modelAvailable)
            assertEquals(DiagnosticCheck.FAILED, result.modelAccessible)
            assertEquals(DiagnosticFormatCompatibility.NOT_TESTED, result.formatCompatibility)
            assertTrue(result.summary.contains("HTTP 400"))
            assertFalse(result.summary.contains("private-server-detail"))
        }
    }

    @Test fun missingModelMakesOnlyInventoryRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"models\":[]}"))
            val provider = ProviderFactory.from("local", "Local", server.url("/").toString(), null)
            val result = ModelDiagnostic.run(provider, "tiny")
            assertEquals(1, server.requestCount)
            assertEquals(DiagnosticCheck.FAILED, result.modelAvailable)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
        }
    }

    @Test fun truncatedStreamWithEvenValidJsonMustNotPassCompletionCheck() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"models\":[{\"name\":\"tiny\"}]}"))
            server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"done\":false}\n"))
            val result = ModelDiagnostic.run(ProviderFactory.from("local", "Local", server.url("/").toString(), null), "tiny")
            assertEquals(DiagnosticCheck.FAILED, result.tinyJsonResponse)
            assertEquals(DiagnosticFormatCompatibility.INVALID_RESPONSE, result.formatCompatibility)
        }
    }

    @Test fun malformedInventoryDoesNotPretendModelIsKnownMissing() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"unexpected\":[]}"))
            val result = ModelDiagnostic.run(ProviderFactory.from("local", "Local", server.url("/").toString(), null), "tiny")
            assertEquals(1, server.requestCount)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAvailable)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
            assertNull(result.stats)
        }
    }

    @Test fun oversizedHttpContentStopsInsteadOfAccumulatingUnboundedDiagnosticText() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"models\":[{\"name\":\"tiny\"}]}"))
            server.enqueue(MockResponse().setBody(JSONObject().put("message", JSONObject().put("content", "x".repeat(2049))).put("done", false).toString() + "\n"))
            val result = ModelDiagnostic.run(ProviderFactory.from("local", "Local", server.url("/").toString(), null), "tiny")
            assertEquals(2, server.requestCount)
            assertEquals(DiagnosticCheck.FAILED, result.tinyJsonResponse)
            assertTrue(result.summary.contains("size limit"))
        }
    }

    @Test fun uppercaseCompatiblePathAndQueryDoNotLeakOllamaFields() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
            val provider = ProviderFactory.from("compat", "Compatible", server.url("/V1///?routing=local").toString(), "key")
            provider.chat("m", emptyList(), null, InferenceOptions(maxOutputTokens = 12, responseSchema = "ignored"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/V1/chat/completions?routing=local", request.path)
            assertEquals("Bearer key", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(12, body.getInt("max_tokens"))
            assertFalse(body.has("options")); assertFalse(body.has("format"))
        }
    }
}

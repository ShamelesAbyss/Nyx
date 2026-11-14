package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class InferenceOptionsHttpTest {
    private val schema = """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}"""
    private fun ollamaResponse() = MockResponse().setBody("""{"message":{"content":"{\"ok\":true}"},"done":true}
""")

    @Test fun ordinaryOllamaChatOmitsAllNewOutputControls() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(ollamaResponse())
            val result = OllamaClient(server.url("/").toString()).chat("m", listOf(ApiMessage("user", "q")))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val body = JSONObject(request.body.readUtf8())
            assertEquals("/api/chat", request.path)
            assertTrue(body.getBoolean("stream"))
            assertFalse(body.has("format")); assertFalse(body.has("max_tokens")); assertFalse(body.has("response_format"))
            val options = body.getJSONObject("options")
            assertFalse(options.has("num_predict"))
            assertEquals(0.7, options.getDouble("temperature"), 0.0)
            assertEquals(4096, options.getInt("num_ctx")); assertEquals(4, options.getInt("num_thread"))
            assertNull(result.stats)
        }
    }

    @Test fun ollamaUsesSchemaObjectAndNestedNumPredictWithoutUnsupportedFields() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(ollamaResponse())
            OllamaClient(server.url("/").toString(), "must-not-leak").chat("m", emptyList(),
                options = InferenceOptions(temperature = 0.0, contextLength = 8192, cpuThreads = 7,
                    maxOutputTokens = 37, responseSchema = schema))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val body = JSONObject(request.body.readUtf8())
            assertEquals("object", body.getJSONObject("format").getString("type"))
            assertEquals(37, body.getJSONObject("options").getInt("num_predict"))
            assertEquals(8192, body.getJSONObject("options").getInt("num_ctx"))
            assertEquals(7, body.getJSONObject("options").getInt("num_thread"))
            assertFalse(body.has("num_predict")); assertFalse(body.has("max_tokens")); assertFalse(body.has("response_format"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun compatibleHttpUsesOnlyMaxTokensAndDoesNotParseOrLeakOllamaSchema() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
            OllamaClient(server.url("/v1").toString(), "key").chat("m", emptyList(), options = InferenceOptions(
                maxOutputTokens = 19, responseSchema = "intentionally ignored non-Ollama value"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val body = JSONObject(request.body.readUtf8())
            assertEquals("/v1/chat/completions", request.path)
            assertEquals(19, body.getInt("max_tokens"))
            assertEquals("Bearer key", request.getHeader("Authorization"))
            assertFalse(body.has("options")); assertFalse(body.has("format")); assertFalse(body.has("response_format"))
            assertFalse(body.has("num_predict")); assertFalse(body.has("num_ctx")); assertFalse(body.has("num_thread"))
        }
    }

    @Test fun ordinaryCompatibleChatOmitsTokenLimitAndFormatControls() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
            OllamaClient(server.url("/v1").toString()).chat("m", emptyList())
            val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(body.has("max_tokens")); assertFalse(body.has("format")); assertFalse(body.has("options"))
            assertEquals(0.7, body.getDouble("temperature"), 0.0)
        }
    }

    @Test fun malformedAndNonObjectOllamaSchemasFailBeforeAnyNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (invalid in listOf("[]", "\"json\"", "{} {}", "not-json", "{")) {
                try {
                    OllamaClient(server.url("/").toString()).chat("m", emptyList(), options = InferenceOptions(responseSchema = invalid))
                    fail("Expected local schema validation for $invalid")
                } catch (_: Exception) { /* Malformed/object-mismatch rejected locally. */ }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun nonPositiveOutputLimitsAreRejectedLocallyAndNullRemainsDefault() {
        assertNull(InferenceOptions().maxOutputTokens)
        assertNull(InferenceOptions().responseSchema)
        for (invalid in listOf(0, -1, -2)) {
            try { InferenceOptions(maxOutputTokens = invalid); fail("Expected positive limit") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, InferenceOptions(maxOutputTokens = 1).maxOutputTokens)
    }

    @Test fun ollamaFinalFrameReportsTokenizerCountsAndNanosecondDurations() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"message":{"content":"ok"},"done":false}
{"message":{"content":""},"done":true,"prompt_eval_count":12,"eval_count":3,"eval_duration":4000000,"total_duration":9000000,"load_duration":2000000}
"""))
            val result = OllamaClient(server.url("/").toString()).chat("m", emptyList())
            assertEquals("ok", result.content)
            assertEquals(InferenceTokenStats(12, 3, 4_000_000, 9_000_000, 2_000_000), result.stats)
        }
    }

    @Test fun absentNullMalformedAndNegativeStatisticsStayUnknownButZeroIsValid() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"message":{"content":"ok"},"done":true,"prompt_eval_count":null,"eval_count":0,"eval_duration":"123","total_duration":-1,"load_duration":1.5}
"""))
            val stats = OllamaClient(server.url("/").toString()).chat("m", emptyList()).stats!!
            assertNull(stats.promptTokens); assertEquals(0L, stats.outputTokens)
            assertNull(stats.generationDurationNanos); assertNull(stats.totalDurationNanos); assertNull(stats.loadDurationNanos)
        }
    }

    @Test fun compatibleUsageOnlyFrameIsReadWithoutRequestingUnsupportedStreamOptions() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""data: {"choices":[{"delta":{"content":"ok"}}]}

data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2}}

data: [DONE]

"""))
            val result = OllamaClient(server.url("/v1").toString()).chat("m", emptyList())
            assertEquals("ok", result.content)
            assertEquals(InferenceTokenStats(promptTokens = 5, outputTokens = 2), result.stats)
            val requestBody = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(requestBody.has("stream_options"))
        }
    }

    @Test fun ollamaStreamErrorDoesNotMasqueradeAsSuccessfulEmptyChat() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"error\":\"private server detail\"}\n"))
            try {
                OllamaClient(server.url("/").toString()).chat("m", emptyList()); fail("Expected API error")
            } catch (error: java.io.IOException) {
                assertFalse(error.message.orEmpty().contains("private server detail"))
            }
        }
    }
}

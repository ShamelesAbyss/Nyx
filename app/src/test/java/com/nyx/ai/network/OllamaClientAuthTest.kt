package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OllamaClientAuthTest {
    @Test
    fun openAiModelsRequestSendsBearerHeader() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"local-model\"}]}"))
            val base = server.url("/v1").toString().trimEnd('/')
            val models = OllamaClient(base, "test-secret").listModels()
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            assertEquals("local-model", models.single().name)
        }
    }

    @Test
    fun openAiChatSendsBearerHeaderWhileStreaming() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n"))
            val base = server.url("/v1").toString().trimEnd('/')
            val result = OllamaClient(base, "test-secret").chat("m", emptyList())
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            assertEquals("hello", result.content)
        }
    }

    @Test
    fun ollamaRequestNeverGetsBearerHeader() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"models\":[]}"))
            OllamaClient(server.url("/").toString().trimEnd('/'), "must-not-leak").listModels()
            val request = server.takeRequest()
            assertEquals("/api/tags", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test
    fun toolUnsupported400IsClassifiedForAgentFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"model does not support tools\"}"))
            val tool = org.json.JSONArray().put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject().put("name", "calculator")))
            try {
                OllamaClient(server.url("/").toString().trimEnd('/')).chat("no-tools-model", emptyList(), tool)
                throw AssertionError("Expected tool-calling capability error")
            } catch (e: ToolCallingUnsupportedException) {
                assertTrue(e.message.orEmpty().contains("model does not support tools"))
            }
            assertEquals("/api/chat", server.takeRequest().path)
        }
    }

    @Test
    fun http400DetailsAreBoundedAndRedactApiKey() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid model; key=test-secret\"}"))
            val base = server.url("/v1").toString().trimEnd('/')
            try {
                OllamaClient(base, "test-secret").chat("m", emptyList())
                throw AssertionError("Expected HTTP error")
            } catch (e: java.io.IOException) {
                assertTrue(e.message.orEmpty().contains("HTTP 400"))
                assertTrue(e.message.orEmpty().contains("invalid model"))
                assertTrue(e.message.orEmpty().contains("[REDACTED]"))
                assertTrue(!e.message.orEmpty().contains("test-secret"))
            }
        }
    }
}

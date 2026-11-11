package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
}

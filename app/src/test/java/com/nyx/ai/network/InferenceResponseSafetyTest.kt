package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class InferenceResponseSafetyTest {
    @Test fun finalOllamaJsonWithoutNewlineStillWorks() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}"))
            val result = OllamaClient(server.url("/").toString()).chat("m", emptyList())
            assertEquals("ok", result.content); assertTrue(result.done)
        }
    }

    @Test fun oversizedFrameFailsBeforeUnboundedJsonParsing() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1) + "\n"))
            try {
                OllamaClient(server.url("/").toString()).chat("m", emptyList()); fail("Expected frame size rejection")
            } catch (error: java.io.IOException) {
                assertTrue(error.message.orEmpty().contains("size limit"))
            }
        }
    }

    @Test fun tinyJsonDoesNotAcceptNonJsonWhitespace() {
        assertFalse(ModelDiagnostic.isExpectedTinyJson("{\u000c\"ok\":true}"))
        assertFalse(ModelDiagnostic.isExpectedTinyJson("\u00a0{\"ok\":true}"))
        assertTrue(ModelDiagnostic.isExpectedTinyJson("\t{\r\n\"ok\" : true}\n"))
    }
}

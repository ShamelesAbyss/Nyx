package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReasoningStreamTest {
    @Test
    fun `ollama reasoning is reported as progress and never appended to content`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"message":{"content":"","thinking":"plan "},"done":false}
                    {"message":{"content":"","thinking":"carefully"},"done":false}
                    {"message":{"content":"{\"ok\":true}"},"done":true,"prompt_eval_count":5,"eval_count":2,"eval_duration":1000000000}
                    """.trimIndent() + "\n",
                ),
            )
            val thinking = StringBuilder()
            val streamed = StringBuilder()
            val result = OllamaClient(server.url("/").toString()).chat(
                "m",
                listOf(ApiMessage("user", "q")),
                options = InferenceOptions(thinkingObserver = { thinking.append(it) }),
                onToken = { streamed.append(it) },
            )
            assertEquals("plan carefully", thinking.toString())
            assertEquals("{\"ok\":true}", result.content)
            assertEquals("{\"ok\":true}", streamed.toString())
            assertEquals(14, result.thinkingCharacters)
            assertFalse(result.content.contains("plan"))
            assertEquals(5L, result.stats?.promptTokens)
        }
    }

    @Test
    fun `openai compatible reasoning deltas are separated from answer text`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
            )
            val result = OllamaClient(server.url("/v1").toString()).chat("m", listOf(ApiMessage("user", "q")))
            assertEquals("ok", result.content)
            assertEquals(5, result.thinkingCharacters)
        }
    }

    @Test
    fun `think parameter is sent only when explicitly requested`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}\n"))
            server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"ok\"},\"done\":true}\n"))
            val client = OllamaClient(server.url("/").toString())
            client.chat("m", listOf(ApiMessage("user", "q")))
            client.chat("m", listOf(ApiMessage("user", "q")), options = InferenceOptions(think = false))
            val ordinary = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val disabled = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(ordinary.has("think"))
            assertTrue(disabled.has("think"))
            assertFalse(disabled.getBoolean("think"))
        }
    }
}

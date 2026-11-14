package com.nyx.ai.network

import com.nyx.ai.autonomy.ExecutionTimeoutException
import com.nyx.ai.autonomy.ExecutionTimeoutKind
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ExecutionTimeoutProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Actual local HTTP streams, not real Ollama or Android hardware. */
class BedrockDirectReasoningTimeoutTest {
    private fun policy() = ExecutionTimeoutPolicy(
        ExecutionTimeoutProfile.QUICK, 500, 800, 10_000, 10_000, 10_000, 10_000, 10_000,
    )

    @Test fun directClientsCountReasoningWithoutPublishingItAsAnswer() = runBlocking {
        for (openAi in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                val reasoning = "r".repeat(170)
                val frame = if (openAi) "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$reasoning\"}}]}\n\n"
                    else "{\"message\":{\"thinking\":\"$reasoning\"},\"done\":false}\n"
                val end = if (openAi) "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    else "{\"message\":{\"content\":\"ok\"},\"done\":true}\n"
                server.enqueue(MockResponse().setBody(frame.repeat(12) + end)
                    .throttleBody(240, 100, TimeUnit.MILLISECONDS))
                val output = StringBuilder()
                var observed = 0
                val result = OllamaClient(server.url(if (openAi) "/v1" else "/").toString()).chat(
                    "fixture", listOf(ApiMessage("user", "bounded question")),
                    options = InferenceOptions(executionTimeouts = policy(), thinkingObserver = { observed += it.length }),
                    onToken = { output.append(it) },
                )
                assertTrue(result.done)
                assertEquals(2040, observed)
                assertEquals(observed, result.thinkingCharacters)
                assertEquals("ok", result.content)
                assertEquals("ok", output.toString())
            }
        }
        Unit
    }

    @Test fun directClientWithoutActivityStillTimesOut() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"late\"},\"done\":true}\n")
                .setBodyDelay(2, TimeUnit.SECONDS))
            val failure = runCatching {
                OllamaClient(server.url("/").toString()).chat("fixture", listOf(ApiMessage("user", "q")),
                    options = InferenceOptions(executionTimeouts = policy()))
            }.exceptionOrNull()
            assertTrue(failure is ExecutionTimeoutException)
            assertEquals(ExecutionTimeoutKind.FIRST_OUTPUT, (failure as ExecutionTimeoutException).kind)
        }
        Unit
    }
}

package com.nyx.ai.network

import com.nyx.ai.autonomy.ExecutionTimeoutException
import com.nyx.ai.autonomy.ExecutionTimeoutKind
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExecutionTimeoutCancellationTest {
    private class BlockingProvider(private val text: String = "") : InferenceProvider {
        override val id = "timeout-test"
        override val displayName = "Timeout Test"
        override val kind = "test"
        override val local = true
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        override suspend fun listModels() = emptyList<LocalModel>()
        override suspend fun capabilities() = ProviderCapabilities(false, true, 2048, 1, true, true)
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            entered.complete(Unit)
            try { onToken(text); awaitCancellation() } finally { stopped.complete(Unit) }
        }
    }

    @Test fun userCancellationReleasesSelectedActiveInferenceAndDoesNotBecomeTimeout() = runBlocking {
        val provider = BlockingProvider()
        val job = launch { InferenceFabric.wrap(provider).chat("m", emptyList(), null,
            InferenceOptions(executionTimeouts = ExecutionTimeoutPolicy.forProfile())) }
        provider.entered.await()
        job.cancelAndJoin()
        assertTrue(provider.stopped.isCompleted)
        assertEquals(0, InferenceFabric.metrics.value.active)
        assertEquals(0, InferenceFabric.metrics.value.queued)
        assertTrue(job.isCancelled)
    }

    @Test fun cancellingQueuedSelectedRequestNeverCallsDelegateAndReleasesCount() = runBlocking {
        val provider = BlockingProvider()
        val queued = InferenceFabric.wrap(provider)
        val active = launch { queued.chat("m", emptyList(), null, InferenceOptions()) }
        provider.entered.await()
        val options = InferenceOptions(executionTimeouts = ExecutionTimeoutPolicy.forProfile())
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { queued.chat("m", emptyList(), null, options) }
        try {
            assertEquals(1, InferenceFabric.metrics.value.queued)
            waiting.cancelAndJoin()
            assertEquals(0, InferenceFabric.metrics.value.queued)
            assertEquals(1, InferenceFabric.metrics.value.active)
        } finally { active.cancelAndJoin(); waiting.cancelAndJoin() }
        assertEquals(0, InferenceFabric.metrics.value.active)
    }

    @Test fun missingFirstOutputIsTypedTimeoutAndCooperativeProviderIsCancelled() = runBlocking {
        val provider = BlockingProvider()
        val policy = ExecutionTimeoutPolicy.forProfile().copy(firstOutputMs = 50, requestMs = 4_000, inactivityMs = 2_000)
        try {
            InferenceFabric.wrap(provider).chat("m", emptyList(), null, InferenceOptions(executionTimeouts = policy))
            fail("Expected first-output timeout")
        } catch (timeout: ExecutionTimeoutException) { assertEquals(ExecutionTimeoutKind.FIRST_OUTPUT, timeout.kind) }
        assertTrue(provider.stopped.isCompleted)
        assertEquals(0, InferenceFabric.metrics.value.active)
        assertEquals(0, InferenceFabric.metrics.value.queued)
    }

    @Test fun textThenSilenceUsesSeparateInactivityDeadline() = runBlocking {
        val provider = BlockingProvider("genuine")
        val policy = ExecutionTimeoutPolicy.forProfile().copy(firstOutputMs = 2_000, requestMs = 4_000, inactivityMs = 50)
        try {
            InferenceFabric.wrap(provider).chat("m", emptyList(), null, InferenceOptions(executionTimeouts = policy))
            fail("Expected inactivity timeout")
        } catch (timeout: ExecutionTimeoutException) { assertEquals(ExecutionTimeoutKind.INACTIVITY, timeout.kind) }
        assertTrue(provider.stopped.isCompleted)
        assertEquals(0, InferenceFabric.metrics.value.active)
    }

    @Test fun selectedHttpCancellationAbortsCallerOwnedRequestWithoutClosingSharedClient() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OllamaClient(server.url("/").toString())
            val request = launch(Dispatchers.IO) { client.chat("m", emptyList(), options =
                InferenceOptions(executionTimeouts = ExecutionTimeoutPolicy.forProfile())) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            server.enqueue(MockResponse().setBody("{\"done\":true}\n"))
            assertTrue(client.chat("m", emptyList()).done)
        }
    }

    @Test fun compatibleSseCommentsAndMetadataNeverBecomeContentOrJsonParseErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(": keepalive\nevent: message\nid: 1\ndata: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n"))
            val chunks = mutableListOf<String>()
            val result = OllamaClient(server.url("/v1").toString()).chat("m", emptyList(), options =
                InferenceOptions(executionTimeouts = ExecutionTimeoutPolicy.forProfile()), onToken = { chunks += it })
            assertEquals(listOf("ok"), chunks)
            assertEquals("ok", result.content)
            assertTrue(result.done)
        }
    }
}

package com.nyx.ai.network

import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InferenceFabricTest {
    private class Fake : InferenceProvider {
        override val id = "test"
        override val displayName = "Test"
        override val kind = "test"
        override val local = true
        val active = AtomicInteger()
        var peak = 0
        override suspend fun capabilities() = ProviderCapabilities(false, true, 2048, 1, true, true)
        override suspend fun listModels() = emptyList<LocalModel>()
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            peak = maxOf(peak, active.incrementAndGet())
            try { delay(50); onToken("ok"); return ChatResult("ok") } finally { active.decrementAndGet() }
        }
    }
    @Test fun multipleTasksStillUseOneInferenceAtATime() = runBlocking {
        val fake = Fake(); val queued = InferenceFabric.wrap(fake)
        val tasks = (1..4).map { async { queued.chat("m", listOf(ApiMessage("user", "q")), null, InferenceOptions()) } }
        tasks.awaitAll()
        assertEquals(1, fake.peak); assertEquals(0, InferenceFabric.metrics.value.queued); assertEquals(0, InferenceFabric.metrics.value.active)
    }
    @Test fun waitingCancellationReleasesQueueCount() = runBlocking {
        val fake = Fake(); val queued = InferenceFabric.wrap(fake)
        val active = launch { queued.chat("m", listOf(ApiMessage("user", "q")), null, InferenceOptions()) }
        delay(10)
        val waiting = launch { queued.chat("m", listOf(ApiMessage("user", "q")), null, InferenceOptions()) }
        delay(5); waiting.cancelAndJoin(); active.join()
        assertEquals(0, InferenceFabric.metrics.value.queued)
    }
    @Test fun oversizedPromptRejectedBeforeCallingProvider() = runBlocking {
        val fake = Fake()
        try { InferenceFabric.wrap(fake).chat("m", listOf(ApiMessage("user", "x".repeat(200001))), null, InferenceOptions()); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, fake.active.get())
    }
}

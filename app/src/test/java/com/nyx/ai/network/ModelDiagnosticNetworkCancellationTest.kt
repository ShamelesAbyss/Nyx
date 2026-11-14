package com.nyx.ai.network

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelDiagnosticNetworkCancellationTest {
    @Test fun timeoutCancelsSlowInventoryBodyAfterHeadersWithoutBlockingCaller() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"models\":[{\"name\":\"m\"}]}").throttleBody(1, 1, TimeUnit.SECONDS))
            val provider = ProviderFactory.from("slow-list", "Slow list", server.url("/").toString(), null)
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                ModelDiagnostic.runBounded(provider, "m", InferenceOptions(), 100, System::nanoTime)
            }
            assertEquals("/api/tags", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val result = withTimeout(5_000) { task.await() }
            assertTrue(result.timedOut)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAvailable)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun timeoutCancelsHttpGenerationAndReleasesFabricGate() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"models\":[{\"name\":\"m\"}]}"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val provider = ProviderFactory.from("slow-chat", "Slow chat", server.url("/").toString(), null)
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                ModelDiagnostic.runBounded(provider, "m", InferenceOptions(), 100, System::nanoTime)
            }
            assertEquals("/api/tags", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            // Let the caller coroutine resume the completed inventory operation before waiting for chat.
            yield()
            val result = withTimeout(5_000) { task.await() }
            assertTrue(result.timedOut)
            assertEquals(DiagnosticCheck.PASSED, result.modelAvailable)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
            assertEquals(0, InferenceFabric.metrics.value.active)
            assertEquals(0, InferenceFabric.metrics.value.queued)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun inventoryBodyHasAHardSizeLimitAndDoesNotPretendModelIsMissing() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(" ".repeat(2 * 1024 * 1024 + 1)))
            val result = ModelDiagnostic.run(ProviderFactory.from("large-list", "Large list", server.url("/").toString(), null), "m")
            assertEquals(1, server.requestCount)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAvailable)
            assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
            assertFalse(result.timedOut)
        }
    }
}

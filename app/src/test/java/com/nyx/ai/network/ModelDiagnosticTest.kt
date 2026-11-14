package com.nyx.ai.network

import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ModelDiagnosticTest {
    private class FakeProvider(override val kind: String = "ollama") : InferenceProvider {
        override val id = "diagnostic-test"
        override val displayName = "Test"
        override val local = true
        var listings = 0
        var chats = 0
        var closes = 0
        var inventory = listOf(LocalModel("m"))
        var listFailure: Exception? = null
        var chatFailure: Exception? = null
        var lastOptions: InferenceOptions? = null
        var lastMessages: List<ApiMessage>? = null
        var result = ChatResult("{\"ok\":true}")
        var body: suspend ((String) -> Unit) -> Unit = { it(result.content) }
        override suspend fun capabilities(): ProviderCapabilities = error("Diagnostics must not duplicate model inventory through capabilities")
        override suspend fun listModels(): List<LocalModel> {
            listings++; listFailure?.let { throw it }; return inventory
        }
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            chats++; lastOptions = options; lastMessages = messages
            assertNull(tools); assertEquals("m", model)
            chatFailure?.let { throw it }; body(onToken); return result
        }
        override fun close() { closes++ }
    }

    @Test fun constructorAndConstantsPerformNoRequestsAndSuccessfulCheckIsTiny() = runBlocking {
        val provider = FakeProvider()
        assertEquals(0, provider.listings); assertEquals(0, provider.chats)
        val options = InferenceOptions(temperature = 1.4, contextLength = 8192, cpuThreads = 7,
            maxOutputTokens = 700, responseSchema = "caller schema")
        val result = ModelDiagnostic.run(provider, "m", options)
        assertEquals(1, provider.listings); assertEquals(1, provider.chats); assertEquals(0, provider.closes)
        assertEquals(DiagnosticCheck.PASSED, result.providerReachable)
        assertEquals(DiagnosticCheck.PASSED, result.modelAvailable)
        assertEquals(DiagnosticCheck.PASSED, result.modelAccessible)
        assertEquals(DiagnosticCheck.PASSED, result.tinyJsonResponse)
        assertEquals(DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED, result.formatCompatibility)
        val sent = provider.lastOptions!!
        assertEquals(0.0, sent.temperature, 0.0); assertEquals(32, sent.maxOutputTokens)
        assertEquals(8192, sent.contextLength); assertEquals(7, sent.cpuThreads)
        assertEquals(ModelDiagnostic.RESPONSE_SCHEMA, sent.responseSchema)
        assertEquals(700, options.maxOutputTokens); assertEquals("caller schema", options.responseSchema)
        assertEquals(1, provider.lastMessages!!.size)
        assertEquals("user", provider.lastMessages!!.single().role)
        assertTrue(provider.lastMessages!!.single().content.length < 512)
        assertNull(result.stats)
        assertTrue(result.summary.contains("output tokens: unavailable"))
        assertFalse(result.timedOut)
    }

    @Test fun injectedClockMeasuresFirstNonemptyTextRatherThanCompletion() = runBlocking {
        val clock = AtomicLong(1_000_000_000)
        val provider = FakeProvider()
        provider.result = ChatResult("{\"ok\":true}", stats = InferenceTokenStats(11, 5))
        provider.body = { token ->
            token("")
            clock.addAndGet(7_000_000); token("{\"ok\":")
            clock.addAndGet(13_000_000); token("true}")
        }
        val result = ModelDiagnostic.runBounded(provider, "m", InferenceOptions(), 30_000, clock::get)
        assertEquals(7L, result.firstResponseLatencyMillis)
        assertEquals(20L, result.totalLatencyMillis)
        assertEquals(InferenceTokenStats(11, 5), result.stats)
    }

    @Test fun missingInventoryEntrySkipsGenerationWithoutPretendingAccessWasTested() = runBlocking {
        val provider = FakeProvider(); provider.inventory = listOf(LocalModel("different"))
        val result = ModelDiagnostic.run(provider, "m")
        assertEquals(1, provider.listings); assertEquals(0, provider.chats)
        assertEquals(DiagnosticCheck.PASSED, result.providerReachable)
        assertEquals(DiagnosticCheck.FAILED, result.modelAvailable)
        assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
        assertEquals(DiagnosticFormatCompatibility.NOT_TESTED, result.formatCompatibility)
    }

    @Test fun inventoryFailureLeavesAvailabilityUnknownAndDoesNotExposeSecrets() = runBlocking {
        val provider = FakeProvider()
        provider.listFailure = InferenceHttpException(401, "private-token https://private-host/v1")
        val result = ModelDiagnostic.run(provider, "m")
        assertEquals(0, provider.chats)
        assertEquals(DiagnosticCheck.PASSED, result.providerReachable)
        assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAvailable)
        assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
        assertTrue(result.summary.contains("HTTP 401"))
        assertFalse(result.summary.contains("private-token")); assertFalse(result.summary.contains("private-host"))
    }

    @Test fun listingIsNotProofOfGenerationAccessAndFailureIsNotRetried() = runBlocking {
        val provider = FakeProvider(); provider.chatFailure = InferenceHttpException(403, "secret")
        val result = ModelDiagnostic.run(provider, "m")
        assertEquals(1, provider.listings); assertEquals(1, provider.chats)
        assertEquals(DiagnosticCheck.PASSED, result.modelAvailable)
        assertEquals(DiagnosticCheck.FAILED, result.modelAccessible)
        assertEquals(DiagnosticCheck.NOT_TESTED, result.tinyJsonResponse)
        assertEquals(DiagnosticFormatCompatibility.NOT_TESTED, result.formatCompatibility)
        assertFalse(result.summary.contains("secret"))
    }

    @Test fun compatibleAndNativeProvidersReceiveNoSchemaAndReportOnlyPromptJsonCompatibility() = runBlocking {
        for (kind in listOf("openai-compatible", "native-gguf")) {
            val provider = FakeProvider(kind)
            val result = ModelDiagnostic.run(provider, "m", InferenceOptions(responseSchema = "caller schema"))
            assertNull(provider.lastOptions!!.responseSchema)
            assertEquals(32, provider.lastOptions!!.maxOutputTokens)
            assertEquals(DiagnosticFormatCompatibility.PROMPT_JSON_SAMPLE_PASSED, result.formatCompatibility)
            assertTrue(result.summary.contains("enforced schema support was not tested"))
        }
    }

    @Test fun tinyJsonValidationRejectsFencesExtraFieldsWrongTypeAndLooseJson() {
        assertTrue(ModelDiagnostic.isExpectedTinyJson(" \n { \"ok\" : true } \n"))
        for (bad in listOf("```json\n{\"ok\":true}\n```", "{\"ok\":false}", "{\"ok\":\"true\"}",
            "{\"ok\":true,\"extra\":1}", "{ok:true}", "{'ok':true}", "{\"ok\":true} explanation", "[]", "")) {
            assertFalse(bad, ModelDiagnostic.isExpectedTinyJson(bad))
        }
    }

    @Test fun invalidButAccessibleModelDoesNotBecomeASchemaSuccess() = runBlocking {
        val provider = FakeProvider(); provider.result = ChatResult("{\"ok\":false}")
        val result = ModelDiagnostic.run(provider, "m")
        assertEquals(DiagnosticCheck.PASSED, result.modelAccessible)
        assertEquals(DiagnosticCheck.FAILED, result.tinyJsonResponse)
        assertEquals(DiagnosticFormatCompatibility.INVALID_RESPONSE, result.formatCompatibility)
    }

    @Test fun outputSizeLimitStopsAnIgnoringProviderWithoutRetriedGeneration() = runBlocking {
        val provider = FakeProvider()
        provider.body = { it("x".repeat(ModelDiagnostic.MAX_RESPONSE_CHARACTERS + 1)); fail("Must stop on oversized chunk") }
        val result = ModelDiagnostic.run(provider, "m")
        assertEquals(1, provider.chats)
        assertEquals(DiagnosticCheck.FAILED, result.tinyJsonResponse)
        assertEquals(DiagnosticFormatCompatibility.INVALID_RESPONSE, result.formatCompatibility)
        assertTrue(result.summary.contains("size limit"))
        assertFalse(result.summary.contains("xxxxx"))
    }

    @Test fun deadlineCancelsInFlightProviderAndLeavesUnfinishedChecksUnknown() = runBlocking {
        val provider = FakeProvider()
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        provider.body = { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val task = async { ModelDiagnostic.runBounded(provider, "m", InferenceOptions(), 100, System::nanoTime) }
        withTimeout(5_000) { entered.await() }
        val result = withTimeout(5_000) { task.await() }
        assertTrue(cancelled.isCompleted)
        assertTrue(result.timedOut)
        assertEquals(DiagnosticCheck.PASSED, result.modelAvailable)
        assertEquals(DiagnosticCheck.NOT_TESTED, result.modelAccessible)
        assertEquals(DiagnosticCheck.NOT_TESTED, result.tinyJsonResponse)
        assertNull(result.firstResponseLatencyMillis)
        assertEquals(0, InferenceFabric.metrics.value.active)
        assertEquals(0, InferenceFabric.metrics.value.queued)
    }

    @Test fun callerCancellationPropagatesRatherThanBecomingDiagnosticFailure() = runBlocking {
        val provider = FakeProvider(); val entered = CompletableDeferred<Unit>()
        provider.body = { entered.complete(Unit); awaitCancellation() }
        val task = async { ModelDiagnostic.run(provider, "m") }
        withTimeout(5_000) { entered.await() }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(0, provider.closes)
        assertEquals(0, InferenceFabric.metrics.value.active)
    }

    @Test fun diagnosticUsesFabricGateAndDoesNotOverlapOrdinaryInference() = runBlocking {
        val provider = FakeProvider(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        provider.body = { entered.complete(Unit); release.await(); it("{\"ok\":true}") }
        val ordinary = async { InferenceFabric.wrap(provider).chat("m", listOf(ApiMessage("user", "q")), null, InferenceOptions()) }
        withTimeout(5_000) { entered.await() }
        val diagnostic = async(start = CoroutineStart.UNDISPATCHED) { ModelDiagnostic.run(provider, "m") }
        assertEquals(1, provider.chats)
        assertEquals(1, InferenceFabric.metrics.value.queued)
        release.complete(Unit)
        withTimeout(5_000) { ordinary.await(); diagnostic.await() }
        assertEquals(2, provider.chats)
        assertEquals(0, InferenceFabric.metrics.value.queued)
    }
}

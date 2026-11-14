package com.nyx.ai.network

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Fake inference only. These tests neither contact Ollama nor execute Android/native models. */
class BedrockCapabilityEvidenceTest {
    private class FakeProvider(override val kind: String = "ollama") : InferenceProvider {
        override val id = "bedrock-fixture"
        override val displayName = "Fake"
        override val local = true
        override val capabilityRevision = "test-runtime-1"
        var answer = ChatResult("{\"ok\":true}")
        var failure: Exception? = null
        var sent: InferenceOptions? = null
        override suspend fun capabilities() = ProviderCapabilities(true, true, 8192, 1, true, true)
        override suspend fun listModels() = listOf(LocalModel("fixture-model"))
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            sent = options
            options.thinkingObserver?.invoke("actual reasoning delta")
            failure?.let { throw it }
            onToken(answer.content)
            return answer
        }
    }
    private fun id() = ModelIdentity("bedrock-fixture", "ollama", "fixture-model", 8192, 4)
    private fun result(format: DiagnosticFormatCompatibility = DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED) =
        ModelDiagnosticResult(id().providerId, id().model, formatCompatibility = format)

    @Test fun freshAndLegacyProfilesNeverAssumeTextActionsOrKeepConflatedHistoricalEvidence() {
        assertEquals(CapabilitySupport.UNKNOWN, ModelCapabilityProfile(id()).textActions)
        val old = ModelCapabilityProfile(id(), structuredOutput = CapabilitySupport.SUPPORTED,
            nativeTools = CapabilitySupport.SUPPORTED, textActions = CapabilitySupport.SUPPORTED).toJson()
        old.remove("cacheVersion")
        val restored = requireNotNull(ModelCapabilityProfile.fromJson(old))
        assertEquals(CapabilitySupport.UNKNOWN, restored.textActions)
        assertEquals(CapabilitySupport.UNKNOWN, restored.structuredOutput)
        assertEquals(CapabilitySupport.UNKNOWN, restored.nativeTools)
        for (format in listOf(DiagnosticFormatCompatibility.NOT_TESTED, DiagnosticFormatCompatibility.INVALID_RESPONSE,
            DiagnosticFormatCompatibility.INCOMPLETE_RESPONSE, DiagnosticFormatCompatibility.PROMPT_JSON_SAMPLE_PASSED)) {
            val observed = ModelCapabilityPolicy.fromDiagnostic(id(), result(format), 1000)
            assertEquals(CapabilitySupport.UNKNOWN, observed.structuredOutput)
            assertEquals(CapabilitySupport.UNKNOWN, observed.textActions)
        }
    }

    @Test fun exactRequestFingerprintIncludesAllNonsecretEffectiveConfigurationWithoutDelimiterCollisions() {
        val provider = FakeProvider()
        val options = InferenceOptions(contextLength = 8192, cpuThreads = 4)
        val first = ModelIdentity.fromRequest(provider, "fixture-model", options)
        val variants = listOf(first.copy(providerId = "other"), first.copy(providerKind = "native-gguf"), first.copy(model = "other"),
            first.copy(contextLength = 2048), first.copy(cpuThreads = 6), first.copy(runtimeRevision = "replaced"),
            first.copy(temperature = 0.0), first.copy(maxOutputTokens = 32), first.copy(responseSchemaHash = "schema"),
            first.copy(think = false), first.copy(toolSchemaHash = "different-tools"))
        assertEquals(variants.size + 1, (variants + first).map { it.fingerprint() }.toSet().size)
        assertNotEquals(id().copy(providerId = "a|b", providerKind = "c").fingerprint(), id().copy(providerId = "a", providerKind = "b|c").fingerprint())
        assertEquals(first, ModelIdentity.fromRequest(provider, "fixture-model", options.copy(thinkingObserver = {})))
        assertEquals(first.fingerprint(), requireNotNull(ModelCapabilityProfile.fromJson(ModelCapabilityProfile(first).toJson())).identity.fingerprint())
    }

    @Test fun unrelatedObservationsCannotRefreshSchemaTextActionMetricsOrFullGraphProof() {
        var now = 1000L
        val store = ModelCapabilityStore(clock = { now })
        val identity = id().copy(responseSchemaHash = "full-test-schema")
        store.put(ModelCapabilityPolicy.fromDiagnostic(identity, result(), now))
        store.recordValidatedFullPlan(identity)
        store.recordValidatedAction(identity, nativeTool = false)
        store.recordObservation(identity, false, InferenceTokenStats(outputTokens = 12, generationDurationNanos = 1_000_000_000), 25)
        now += ModelCapabilityPolicy.CACHE_TTL_MS - 1
        store.recordObservation(identity, true, null, null)
        now += 2
        val profile = requireNotNull(store.get(identity))
        assertEquals(CapabilitySupport.UNKNOWN, profile.structuredOutput)
        assertEquals(CapabilitySupport.UNKNOWN, profile.textActions)
        assertEquals(CapabilitySupport.SUPPORTED, profile.reasoningStream)
        assertEquals(CapabilitySupport.UNKNOWN, profile.stats)
        assertNull(profile.observedOutputTokensPerSecond)
        assertNull(profile.observedFirstTextMillis)
        assertEquals(0L, profile.fullPlanObservedAtEpochMs)
    }

    @Test fun failedAndStatsFreeDiagnosticsMergeRatherThanEraseIndependentFreshEvidence() {
        var now = 1000L
        var disk: String? = null
        val persistence = object : CapabilityPersistence {
            override fun read() = disk
            override fun write(value: String) { disk = value }
        }
        val store = ModelCapabilityStore(persistence, clock = { now })
        store.recordValidatedAction(id(), true)
        store.recordObservation(id(), true, InferenceTokenStats(outputTokens = 7, generationDurationNanos = 1_000_000_000), 10)
        now++
        store.put(ModelCapabilityPolicy.fromDiagnostic(id(), result(DiagnosticFormatCompatibility.NOT_TESTED), now))
        val restored = requireNotNull(ModelCapabilityStore(persistence, clock = { now }).get(id()))
        assertEquals(CapabilitySupport.SUPPORTED, restored.nativeTools)
        assertEquals(CapabilitySupport.SUPPORTED, restored.reasoningStream)
        assertEquals(CapabilitySupport.SUPPORTED, restored.stats)
        assertEquals(7.0, restored.observedOutputTokensPerSecond!!, 0.0)
        assertNull(store.get(id().copy(runtimeRevision = "replaced")))
        store.invalidateProvider(id().providerId)
        assertTrue(store.all().isEmpty())
    }

    @Test fun boundedTinySuccessDoesNotJustifyFullGraphAndReasoningNeverImpliesThinkingControl() {
        val tiny = ModelCapabilityPolicy.fromDiagnostic(id(), result(), 1000).copy(observedOutputTokensPerSecond = 20.0)
        val goal = "Create a Kotlin project with compilation and tests"
        assertEquals(PlanningStrategy.INCREMENTAL, ModelCapabilityPolicy.decide(tiny, goal, true).strategy)
        assertFalse(ModelCapabilityPolicy.decide(tiny.copy(reasoningStream = CapabilitySupport.SUPPORTED), goal, true).disableThinking)
        assertEquals(CapabilitySupport.UNKNOWN, tiny.thinkingControl)
        val full = tiny.copy(fullPlanObservedAtEpochMs = 1000)
        assertEquals(PlanningStrategy.FULL, ModelCapabilityPolicy.decide(full, goal, true).strategy)
        assertEquals(PlanningStrategy.INCREMENTAL, ModelCapabilityPolicy.decide(full.copy(observedOutputTokensPerSecond = null), goal, true).strategy)
        assertEquals(PlanningStrategy.INCREMENTAL, ModelCapabilityPolicy.decide(full.copy(identity = id().copy(contextLength = 2048)), goal, true).strategy)
        // Validated JSON planning is independent of the agent's text-action fallback protocol.
        assertEquals(PlanningStrategy.INCREMENTAL, ModelCapabilityPolicy.decide(tiny.copy(textActions = CapabilitySupport.UNSUPPORTED), goal, true).strategy)
    }

    @Test fun separateValidatedActionsAndExplicitRejectionsDoNotGuessThroughput() {
        val store = ModelCapabilityStore(clock = { 1000 })
        store.recordValidatedAction(id(), true)
        var profile = requireNotNull(store.get(id()))
        assertEquals(CapabilitySupport.SUPPORTED, profile.nativeTools)
        assertEquals(CapabilitySupport.UNKNOWN, profile.textActions)
        store.recordValidatedAction(id(), false)
        store.recordExplicitRejection(id(), ObservedCapability.THINKING_CONTROL)
        profile = requireNotNull(store.get(id()))
        assertEquals(CapabilitySupport.SUPPORTED, profile.textActions)
        assertEquals(CapabilitySupport.UNSUPPORTED, profile.thinkingControl)
        assertEquals(CapabilitySupport.UNKNOWN, profile.reasoningStream)
        for (stats in listOf(null, InferenceTokenStats(outputTokens = 2), InferenceTokenStats(outputTokens = -1, generationDurationNanos = 1),
            InferenceTokenStats(outputTokens = 2, generationDurationNanos = 0))) assertNull(ModelCapabilityPolicy.throughput(stats))
        assertEquals(0.0, ModelCapabilityPolicy.throughput(InferenceTokenStats(outputTokens = 0, generationDurationNanos = 1))!!, 0.0)
    }

    @Test fun incompleteValidJsonAndGenericTransportFailuresNeverProveUnsupported() = runBlocking {
        val provider = FakeProvider()
        provider.answer = ChatResult("{\"ok\":true}", done = false)
        var diagnostic = ModelDiagnostic.run(provider, "fixture-model")
        assertEquals(DiagnosticFormatCompatibility.INCOMPLETE_RESPONSE, diagnostic.formatCompatibility)
        assertEquals(DiagnosticCheck.NOT_TESTED, diagnostic.tinyJsonResponse)
        assertEquals(CapabilitySupport.UNKNOWN, ModelCapabilityPolicy.fromDiagnostic(id(), diagnostic, 1000).structuredOutput)
        for (failure in listOf(IOException("disconnect secret"), InferenceHttpException(401, "secret"),
            InferenceHttpException(403, "secret"), InferenceHttpException(500, "secret"), InferenceHttpException(400, "schema unsupported secret"))) {
            provider.failure = failure
            diagnostic = ModelDiagnostic.run(provider, "fixture-model")
            assertEquals(CapabilitySupport.UNKNOWN, ModelCapabilityPolicy.fromDiagnostic(id(), diagnostic, 1000).structuredOutput)
            assertFalse(diagnostic.summary.contains("secret"))
        }
        provider.failure = InferenceFeatureUnsupportedException(ObservedCapability.STRUCTURED_OUTPUT)
        diagnostic = ModelDiagnostic.run(provider, "fixture-model")
        assertEquals(DiagnosticFormatCompatibility.SCHEMA_EXPLICITLY_REJECTED, diagnostic.formatCompatibility)
        assertEquals(CapabilitySupport.UNSUPPORTED, ModelCapabilityPolicy.fromDiagnostic(id(), diagnostic, 1000).structuredOutput)
    }

    @Test fun diagnosticTracksEffectiveOptionsAndReasoningWithoutPromotingPromptOnlySchema() = runBlocking {
        for (kind in listOf("ollama", "openai-compatible", "native-gguf")) {
            val provider = FakeProvider(kind)
            provider.answer = ChatResult("{\"ok\":true}", thinkingCharacters = 40, stats = InferenceTokenStats(promptTokens = 8))
            val diagnostic = ModelDiagnostic.run(provider, "fixture-model", InferenceOptions(contextLength = 8192, cpuThreads = 7, temperature = 1.2, maxOutputTokens = 700))
            val actual = requireNotNull(diagnostic.requestIdentity)
            assertEquals(0.0, actual.temperature, 0.0)
            assertEquals(32, actual.maxOutputTokens)
            assertEquals(8192, actual.contextLength)
            assertEquals(7, actual.cpuThreads)
            assertEquals(provider.capabilityRevision, actual.runtimeRevision)
            assertEquals(40, diagnostic.thinkingCharacters)
            val profile = ModelCapabilityPolicy.fromDiagnostic(id().copy(providerKind = kind), diagnostic, 1000)
            assertEquals(actual, profile.identity)
            assertEquals(CapabilitySupport.SUPPORTED, profile.reasoningStream)
            assertEquals(CapabilitySupport.UNKNOWN, profile.thinkingControl)
            assertEquals(CapabilitySupport.UNKNOWN, profile.textActions)
            if (kind != "ollama") assertEquals(CapabilitySupport.UNKNOWN, profile.structuredOutput)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun mismatchedDiagnosticCannotBeStoredUnderAnotherModel() {
        ModelCapabilityPolicy.fromDiagnostic(id().copy(model = "wrong"), result(), 1000)
    }

    @Test fun clockRollbackExpiresEvidenceAndLegacyInformationalCache() {
        var now = 1000L
        val store = ModelCapabilityStore(clock = { now })
        val legacy = ProviderCapabilityCache(clock = { now })
        store.recordValidatedAction(id(), true)
        legacy.recordToolSupport("p", "m", false)
        now = 999
        assertNull(store.get(id()))
        assertNull(legacy.toolSupport("p", "m"))
    }
}

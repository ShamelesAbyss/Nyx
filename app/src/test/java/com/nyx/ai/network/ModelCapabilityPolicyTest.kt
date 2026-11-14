package com.nyx.ai.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityPolicyTest {
    private fun identity(
        model: String = "qwen2.5:7b",
        context: Int = 4_096,
        threads: Int = 4,
        provider: String = "local",
    ) = ModelIdentity(provider, "ollama", model, context, threads)

    private fun diagnostic(
        format: DiagnosticFormatCompatibility = DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED,
        outputTokens: Long? = 20,
        durationNanos: Long? = 2_000_000_000,
        firstTextMillis: Long? = 900,
    ) = ModelDiagnosticResult(
        providerId = "local",
        model = "qwen2.5:7b",
        providerReachable = DiagnosticCheck.PASSED,
        modelAvailable = DiagnosticCheck.PASSED,
        modelAccessible = DiagnosticCheck.PASSED,
        tinyJsonResponse = DiagnosticCheck.PASSED,
        formatCompatibility = format,
        firstResponseLatencyMillis = firstTextMillis,
        totalLatencyMillis = 1_500,
        stats = InferenceTokenStats(promptTokens = 12, outputTokens = outputTokens, generationDurationNanos = durationNanos),
    )

    @Test
    fun `schema probe maps to supported and computes measured throughput`() {
        val profile = ModelCapabilityPolicy.fromDiagnostic(identity(), diagnostic(), now = 1_000)
        assertEquals(CapabilitySupport.SUPPORTED, profile.structuredOutput)
        assertEquals(CapabilitySupport.SUPPORTED, profile.stats)
        assertEquals(10.0, profile.observedOutputTokensPerSecond!!, 0.01)
        assertEquals(900L, profile.observedFirstTextMillis)
        assertTrue(profile.summary().contains("schema-requested compatible output yes"))
    }

    @Test
    fun `prompt-only json success does not claim constrained schema support`() {
        val profile = ModelCapabilityPolicy.fromDiagnostic(
            identity(), diagnostic(format = DiagnosticFormatCompatibility.PROMPT_JSON_SAMPLE_PASSED), now = 1_000,
        )
        assertEquals(CapabilitySupport.UNKNOWN, profile.structuredOutput)
    }

    @Test
    fun `invalid sample does not establish constrained decoding unsupported`() {
        val profile = ModelCapabilityPolicy.fromDiagnostic(
            identity(), diagnostic(format = DiagnosticFormatCompatibility.INVALID_RESPONSE), now = 1_000,
        )
        assertEquals(CapabilitySupport.UNKNOWN, profile.structuredOutput)
        // Provider statistics are independent of the format sample.
        assertEquals(CapabilitySupport.SUPPORTED, profile.stats)
    }

    @Test
    fun `simple text objective starts directly without a planning call`() {
        val decision = ModelCapabilityPolicy.decide(ModelCapabilityProfile(identity()), "Explain photosynthesis in one paragraph", toolsAvailable = true)
        assertEquals(PlanningStrategy.DIRECT, decision.strategy)
        assertEquals(1, decision.maxInitialSteps)
        assertFalse(decision.allowExpansion)
    }

    @Test
    fun `resource or tool objective is never direct`() {
        val profile = ModelCapabilityProfile(identity())
        val fileGoal = ModelCapabilityPolicy.decide(profile, "Explain how to create a file in the workspace", toolsAvailable = true)
        assertTrue(fileGoal.strategy != PlanningStrategy.DIRECT)
        val commandGoal = ModelCapabilityPolicy.decide(profile, "Summarize this repository and run its tests", toolsAvailable = true)
        assertTrue(commandGoal.strategy != PlanningStrategy.DIRECT)
    }

    @Test
    fun `unsupported schema selects guided decisions`() {
        val profile = ModelCapabilityProfile(identity(), structuredOutput = CapabilitySupport.UNSUPPORTED)
        val decision = ModelCapabilityPolicy.decide(profile, "Build an Android application with a database and authentication", toolsAvailable = true)
        assertEquals(PlanningStrategy.GUIDED, decision.strategy)
        assertEquals(1, decision.maxInitialSteps)
        assertTrue(decision.reason.contains("JSON"))
    }

    @Test
    fun `complex fast schema model gets one full graph`() {
        val profile = ModelCapabilityProfile(
            identity(context = 8_192),
            structuredOutput = CapabilitySupport.SUPPORTED,
            observedOutputTokensPerSecond = 12.0,
            fullPlanObservedAtEpochMs = 1_000,
        )
        val decision = ModelCapabilityPolicy.decide(profile, "Build an Android application with a database and authentication", toolsAvailable = true)
        assertEquals(PlanningStrategy.FULL, decision.strategy)
        assertFalse(decision.allowExpansion)
    }

    @Test
    fun `slow model uses incremental planning without inferring thinking control`() {
        val profile = ModelCapabilityProfile(
            identity(context = 8_192),
            structuredOutput = CapabilitySupport.SUPPORTED,
            reasoningStream = CapabilitySupport.SUPPORTED,
            observedOutputTokensPerSecond = 2.0,
        )
        val decision = ModelCapabilityPolicy.decide(profile, "Build an Android application with a database and authentication", toolsAvailable = true)
        assertEquals(PlanningStrategy.INCREMENTAL, decision.strategy)
        assertTrue(decision.allowExpansion)
        assertFalse(decision.disableThinking)
        assertTrue(decision.reason.contains("throughput"))
    }

    @Test
    fun `observation records reasoning and throughput without guessing from the model name`() {
        val profile = ModelCapabilityPolicy.withObservation(
            ModelCapabilityProfile(identity()),
            thinkingObserved = true,
            stats = InferenceTokenStats(outputTokens = 8, generationDurationNanos = 4_000_000_000),
            firstTextMillis = 4_000,
            now = 2_000,
        )
        assertEquals(CapabilitySupport.SUPPORTED, profile.reasoningStream)
        assertEquals(2.0, profile.observedOutputTokensPerSecond!!, 0.01)
        assertEquals(4_000L, profile.observedFirstTextMillis)
        assertEquals("observed request", profile.source)
    }

    @Test
    fun `store persists, reloads, and invalidates on identity or expiry`() {
        var stored: String? = null
        val persistence = object : CapabilityPersistence {
            override fun read(): String? = stored
            override fun write(value: String) { stored = value }
        }
        val first = ModelCapabilityStore(persistence) { 1_000 }
        val id = identity()
        first.put(ModelCapabilityPolicy.fromDiagnostic(id, diagnostic(), now = 1_000))
        assertNotNull(first.get(id))

        val reloaded = ModelCapabilityStore(persistence) { 2_000 }
        assertNotNull(reloaded.get(id))
        assertNull(reloaded.get(identity(context = 8_192)))
        assertNull(reloaded.get(identity(model = "another")))

        val expired = ModelCapabilityStore(persistence) { 1_000 + ModelCapabilityPolicy.CACHE_TTL_MS + 1 }
        assertNull(expired.get(id))
        assertEquals(0, stored?.let { storedModels(it).size })
    }

    private fun storedModels(raw: String): List<String> =
        org.json.JSONArray(raw).let { array -> (0 until array.length()).map { array.optJSONObject(it)?.optString("model").orEmpty() } }
}

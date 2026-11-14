package com.nyx.ai.network

import com.nyx.ai.autonomy.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Deterministic clock/fake-provider evidence, not native cancellation or Android behavior. */
class BedrockInferenceActivityTest {
    private fun policy() = ExecutionTimeoutPolicy(ExecutionTimeoutProfile.QUICK,
        firstOutputMs = 10, inactivityMs = 20, requestMs = 100,
        buildMs = 100, terminalMs = 100, overallAllowanceMs = 100, serviceSegmentMs = 100)

    @Test fun actualReasoningKeepsActivityAliveWithoutAnswerTextButHardDeadlineAlwaysWins() {
        val activity = InferenceActivityTracker(0, policy())
        val legacyContentOnly = InferenceTimeoutTracker(0, policy())
        for (time in listOf(5L, 14L, 30L, 45L, 60L, 75L, 90L)) {
            activity.activity(time, "real reasoning delta")
            assertNull(activity.failure(time))
        }
        assertEquals(ExecutionTimeoutKind.FIRST_OUTPUT, legacyContentOnly.failure(14)!!.kind)
        assertEquals(ExecutionTimeoutKind.REQUEST, activity.failure(100)!!.kind)
        activity.activity(101, "late reasoning")
        assertEquals(ExecutionTimeoutKind.REQUEST, activity.failure(101)!!.kind)
    }

    @Test fun emptyDeltasAndKeepalivesDoNotAdvanceFirstActivityOrReviveABreachedInactivityDeadline() {
        val empty = InferenceActivityTracker(0, policy())
        empty.activity(5, "")
        assertEquals(ExecutionTimeoutKind.FIRST_OUTPUT, empty.failure(10)!!.kind)
        val active = InferenceActivityTracker(0, policy())
        active.activity(5, "reasoning")
        assertNull(active.failure(24))
        assertEquals(ExecutionTimeoutKind.INACTIVITY, active.failure(25)!!.kind)
        active.activity(26, "later answer")
        assertEquals(ExecutionTimeoutKind.INACTIVITY, active.failure(26)!!.kind)
    }

    @Test fun effectiveOptionsForwardReasoningSeparatelyAndNestedLegacyWatchdogCannotContradictOuterPolicy() = runBlocking {
        var clock = 0L
        val reasoning = mutableListOf<String>()
        val content = mutableListOf<String>()
        val callerObserver: (String) -> Unit = { reasoning += it }
        val original = InferenceOptions(executionTimeouts = policy(), thinkingObserver = callerObserver, think = true)
        val result = withInferenceActivityTimeouts(original, { content += it }, clock = { clock }) { outgoing, onToken ->
            assertNotSame(callerObserver, outgoing.thinkingObserver)
            assertEquals(true, outgoing.think) // Observing reasoning never changes caller preference.
            withExecutionTimeouts(outgoing, onToken) { observed ->
                clock = 5; outgoing.thinkingObserver!!("r1")
                clock = 14; outgoing.thinkingObserver!!("r2")
                clock = 30; outgoing.thinkingObserver!!("r3")
                clock = 31; observed("answer")
                ChatResult("answer", thinkingCharacters = 6)
            }
        }
        assertEquals("answer", result.content)
        assertEquals(listOf("answer"), content)
        assertEquals(listOf("r1", "r2", "r3"), reasoning)
        assertSame(callerObserver, original.thinkingObserver)
        assertEquals(true, original.think)
    }

    @Test fun actualReasoningCannotDefeatInjectedHardRequestDeadline() = runBlocking {
        var clock = 0L
        try {
            withInferenceActivityTimeouts(InferenceOptions(executionTimeouts = policy()), {}, clock = { clock }) { outgoing, _ ->
                clock = 5; outgoing.thinkingObserver!!("reasoning")
                clock = 100; outgoing.thinkingObserver!!("more reasoning")
                fail("Finite deadline must win")
                ChatResult("")
            }
            fail("Must fail request deadline")
        } catch (failure: ExecutionTimeoutException) {
            assertEquals(ExecutionTimeoutKind.REQUEST, failure.kind)
        }
    }

    @Test fun fabricMetricsAndAnswerStreamNeverCountReasoningAsGeneratedText() = runBlocking {
        val fake = object : InferenceProvider {
            override val id = "bedrock-reasoning-fake"
            override val displayName = "Fake"
            override val kind = "ollama"
            override val local = true
            override suspend fun capabilities() = ProviderCapabilities(true, true, 4096, 1, true, true)
            override suspend fun listModels() = listOf(LocalModel("fake"))
            override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
                options.thinkingObserver?.invoke("reasoning only")
                onToken("ok")
                return ChatResult("ok", thinkingCharacters = 14)
            }
        }
        val before = InferenceFabric.metrics.value.generatedCharacters
        val answer = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val result = InferenceFabric.wrap(fake).chat("fake", emptyList(), null,
            InferenceOptions(executionTimeouts = ExecutionTimeoutPolicy.forProfile(), thinkingObserver = { reasoning += it })) { answer += it }
        assertEquals(listOf("ok"), answer)
        assertEquals(listOf("reasoning only"), reasoning)
        assertEquals(2L, InferenceFabric.metrics.value.generatedCharacters - before)
        assertEquals(14, result.thinkingCharacters)
        assertEquals(0, InferenceFabric.metrics.value.active)
        assertEquals(0, InferenceFabric.metrics.value.queued)
    }

    @Test fun patientBudgetsRemainFiniteAndUnchanged() {
        val patient = ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.PATIENT)
        assertEquals(20 * 60_000L, patient.firstOutputMs)
        assertEquals(10 * 60_000L, patient.inactivityMs)
        assertEquals(60 * 60_000L, patient.requestMs)
        assertEquals(60 * 60_000L, patient.serviceSegmentMs)
    }
}

package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningReasoningLifecycleTest {
    @Test
    fun `reasoning activity prevents an unexplained first-text timeout`() {
        val lifecycle = PlanningLifecyclePolicy(0)
        // Only the provider reasoning channel is active; no plan text exists yet.
        for (time in 10_000L..170_000L step 10_000L) lifecycle.reasoning(time, "thinking ")
        val progress = lifecycle.progress(175_000)
        assertEquals(PlanningStage.REASONING, progress.stage)
        assertNull(progress.failure)
        assertEquals(0L, progress.characters)
        assertTrue(progress.reasoningCharacters > 0)
        assertTrue(progress.detail().contains("reasoning stream"))
    }

    @Test
    fun `reasoning is never counted as plan text`() {
        val lifecycle = PlanningLifecyclePolicy(0)
        lifecycle.reasoning(1_000, "deliberation")
        lifecycle.token(2_000, "{\"steps\":[]}")
        val progress = lifecycle.progress(3_000)
        assertEquals(PlanningStage.GENERATING, progress.stage)
        assertEquals(12L, progress.characters)
        assertEquals(12L, progress.reasoningCharacters)
        assertTrue(progress.detail().contains("separate from plan text"))
    }

    @Test
    fun `stall is reported when both text and reasoning stop`() {
        val lifecycle = PlanningLifecyclePolicy(0)
        lifecycle.reasoning(5_000, "thinking")
        assertNull(lifecycle.progress(64_999).failure)
        val stalled = lifecycle.progress(65_000)
        assertEquals(PlanningStage.STALLED, stalled.stage)
        assertTrue(stalled.failure!!.reason.contains("no text or reasoning"))
    }
}

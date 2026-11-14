package com.nyx.ai.autonomy

import org.junit.Assert.*
import org.junit.Test

class PlanningLifecyclePolicyTest {
    private val limits = PlanningLimits(queueWaitMs = 30, firstTokenMs = 50, stallMs = 40, activeGenerationMs = 200, hardLimitMs = 300)

    @Test fun unknownAdmissionDoesNotFabricateLoadPromptOrQueueDurations() {
        val state = PlanningLifecyclePolicy(100, limits).progress(120)
        assertEquals(PlanningStage.WAITING_FOR_PROVIDER, state.stage)
        assertNull(state.firstTokenAfterMs)
        assertNull(state.idleMs)
        assertTrue(state.detail(2, 1).contains("do not identify this request"))
        assertTrue(state.detail().contains("not separately observable"))
        assertFalse(state.detail().contains("model loaded"))
    }

    @Test fun explicitQueueHasItsOwnLimitAndAdmissionResetsFirstTokenWait() {
        val lifecycle = PlanningLifecyclePolicy(100, limits)
        lifecycle.queued()
        assertEquals(PlanningStage.QUEUED, lifecycle.progress(129).stage)
        assertNull(lifecycle.progress(129).failure)
        assertTrue(lifecycle.progress(130).failure!!.reason.contains("queue wait"))
        val admitted = PlanningLifecyclePolicy(100, limits)
        admitted.queued()
        admitted.admitted(129)
        assertEquals(PlanningStage.WAITING_FOR_FIRST_TOKEN, admitted.progress(178).stage)
        assertNull(admitted.progress(178).failure)
        assertTrue(admitted.progress(179).failure!!.reason.contains("observed admission"))
    }

    @Test fun unknownAdmissionHasBoundedFirstTextWaitWithHonestDiagnostic() {
        val lifecycle = PlanningLifecyclePolicy(100, limits)
        assertNull(lifecycle.progress(149).failure)
        val failure = lifecycle.progress(150).failure!!
        assertEquals(PlanningFailureKind.TIMEOUT, failure.kind)
        assertTrue(failure.reason.contains("times are unknown"))
    }

    @Test fun emptyCallbacksDoNotProveFirstTokenOrResetDeadline() {
        val lifecycle = PlanningLifecyclePolicy(0, limits)
        lifecycle.token(49, "")
        assertEquals(0L, lifecycle.progress(50).characters)
        assertNull(lifecycle.progress(50).firstTokenAfterMs)
        assertNotNull(lifecycle.progress(50).failure)
    }

    @Test fun threeObservedCharactersBecomeStalledInsteadOfAnUnexplained120SecondTimeout() {
        val lifecycle = PlanningLifecyclePolicy(0)
        lifecycle.token(5_000, "{\n ")
        assertEquals(PlanningStage.GENERATING, lifecycle.progress(64_999).stage)
        assertNull(lifecycle.progress(64_999).failure)
        val stalled = lifecycle.progress(65_000)
        assertEquals(PlanningStage.STALLED, stalled.stage)
        assertEquals(3L, stalled.characters)
        assertTrue(stalled.failure!!.reason.contains("3 streamed characters"))
        assertTrue(stalled.failure.reason.contains("60s"))
    }

    @Test fun activeSlowGenerationCanContinueBeyondOld120SecondDeadline() {
        val lifecycle = PlanningLifecyclePolicy(0)
        lifecycle.token(10_000, "{")
        for (time in 30_000L..150_000L step 20_000L) lifecycle.token(time, "x")
        val progress = lifecycle.progress(155_000)
        assertEquals(PlanningStage.GENERATING, progress.stage)
        assertNull(progress.failure)
        assertEquals(10_000L, progress.firstTokenAfterMs)
    }

    @Test fun tokenProgressCannotExtendActiveGenerationLimitForever() {
        val lifecycle = PlanningLifecyclePolicy(0, limits)
        lifecycle.token(10, "{")
        for (time in 20L..200L step 10L) lifecycle.token(time, "x")
        assertNull(lifecycle.progress(209).failure)
        lifecycle.token(210, "x")
        assertTrue(lifecycle.progress(210).failure!!.reason.contains("active generation limit"))
    }

    @Test fun hardLimitAppliesEvenWhenQueueAdmissionAndTokensWereLate() {
        val lifecycle = PlanningLifecyclePolicy(0, limits.copy(queueWaitMs = 150, firstTokenMs = 200, activeGenerationMs = 400))
        lifecycle.queued()
        lifecycle.admitted(120)
        lifecycle.token(160, "{")
        for (time in 170L..290L step 10L) lifecycle.token(time, "x")
        assertNull(lifecycle.progress(299).failure)
        assertTrue(lifecycle.progress(300).failure!!.reason.contains("hard request limit"))
    }

    @Test fun aLateChunkCannotHideAStallBetweenWatchdogSamples() {
        val lifecycle = PlanningLifecyclePolicy(0, limits)
        lifecycle.token(10, "{")
        lifecycle.token(51, "x")
        assertNotNull(lifecycle.progress(51).failure)
        assertTrue(lifecycle.progress(51).failure!!.reason.contains("stalled"))
        val lateFirst = PlanningLifecyclePolicy(0, limits)
        lateFirst.token(51, "{")
        assertNotNull(lateFirst.progress(51).failure)
    }

    @Test fun charactersAreNotReportedAsTokenCountsAndFirstChunkIsMeasuredFromSubmission() {
        val lifecycle = PlanningLifecyclePolicy(100, limits)
        lifecycle.token(105, "é🖤")
        lifecycle.token(106, "abc")
        val state = lifecycle.progress(110)
        assertEquals(6L, state.characters)
        assertEquals(5L, state.firstTokenAfterMs)
        assertEquals(4L, state.idleMs)
        assertTrue(state.detail().contains("characters observed"))
    }

    @Test fun nonMonotonicCallbackInputCannotMoveLastObservationBackwards() {
        val lifecycle = PlanningLifecyclePolicy(100, limits)
        lifecycle.token(120, "x")
        lifecycle.token(110, "y")
        assertEquals(5L, lifecycle.progress(125).idleMs)
        assertEquals(20L, lifecycle.progress(125).firstTokenAfterMs)
        assertNull(lifecycle.progress(90).failure)
    }

    @Test fun nonpositiveLimitsAreRejected() {
        try { PlanningLimits(stallMs = 0); fail("Expected invalid limit") }
        catch (_: IllegalArgumentException) { }
    }
}

package com.nyx.ai.autonomy

import org.junit.Assert.*
import org.junit.Test

class ExecutionTimeoutPolicyTest {
    @Test fun mobileDefaultIsFiniteStandardThirtyMinutes() {
        val policy = ExecutionTimeoutPolicy.forProfile()
        assertEquals(ExecutionTimeoutProfile.STANDARD, policy.profile)
        assertEquals(30 * 60_000L, policy.overallAllowanceMs)
        assertEquals(policy.requestMs, policy.planningLimits().hardLimitMs)
        assertEquals(policy.firstOutputMs, policy.planningLimits().firstTokenMs)
        assertEquals(policy.inactivityMs, policy.planningLimits().stallMs)
    }

    @Test fun presetAllowancesAreQuickStandardPatientExtended() {
        val expected = listOf(ExecutionTimeoutProfile.QUICK to 5, ExecutionTimeoutProfile.STANDARD to 30,
            ExecutionTimeoutProfile.PATIENT to 60, ExecutionTimeoutProfile.EXTENDED to 120)
        expected.forEach { (profile, minutes) ->
            val policy = ExecutionTimeoutPolicy.forProfile(profile, explicitLongRunApproval = profile == ExecutionTimeoutProfile.EXTENDED)
            assertEquals(minutes * 60_000L, policy.overallAllowanceMs)
            assertTrue(policy.firstOutputMs <= policy.requestMs)
            assertTrue(policy.inactivityMs <= policy.requestMs)
            assertTrue(policy.requestMs <= policy.serviceSegmentMs)
        }
    }

    @Test fun longProfilesRequireExplicitApprovalAndCustomIsNeverInfinite() {
        assertRejected { ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.EXTENDED) }
        assertRejected { ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.CUSTOM, 720) }
        assertRejected { ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.CUSTOM, 0, true) }
        assertRejected { ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.CUSTOM, 721, true) }
        assertRejected { ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.CUSTOM, explicitLongRunApproval = true) }
        assertRejected { ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.STANDARD, 60) }
    }

    @Test fun twelveHoursIsCheckpointedAllowanceNotAContinuousServiceOrRequest() {
        val policy = ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.CUSTOM, 720, true)
        assertEquals(12 * 60 * 60_000L, policy.overallAllowanceMs)
        assertEquals(2 * 60 * 60_000L, policy.serviceSegmentMs)
        assertEquals(policy.serviceSegmentMs, policy.requestMs)
        assertRejected { policy.copy(serviceSegmentMs = policy.overallAllowanceMs) }
        assertRejected { policy.copy(requestMs = policy.overallAllowanceMs) }
        assertRejected { policy.copy(firstOutputMs = 0) }
        assertRejected { policy.copy(explicitLongRunApproval = false) }
    }

    @Test fun reportedPixelTimingIsWithinStandardLimitsButDoesNotDiagnoseItsCause() {
        val policy = ExecutionTimeoutPolicy.forProfile()
        val lifecycle = PlanningLifecyclePolicy(0, policy.planningLimits())
        lifecycle.token(109_000, "{")
        lifecycle.token(199_000, "1234567890")
        val snapshot = lifecycle.progress(300_000)
        assertEquals(11L, snapshot.characters)
        assertEquals(109_000L, snapshot.firstTokenAfterMs)
        assertNull(snapshot.failure)
        assertTrue(snapshot.detail().contains("characters"))
    }

    @Test fun patientPlanningHasNoTenMinuteHiddenLifecycleCap() {
        val lifecycle = PlanningLifecyclePolicy(0, ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.PATIENT).planningLimits())
        lifecycle.token(1_000, "{")
        for (minute in 9L..54L step 9L) lifecycle.token(minute * 60_000, "x")
        assertNull(lifecycle.progress(59 * 60_000).failure)
        assertNotNull(lifecycle.progress(60 * 60_000).failure)
    }

    @Test fun firstOutputAndInactivityAreSeparateAndEmptyKeepalivesDoNotAdvanceEither() {
        val policy = tinyPolicy()
        val waiting = InferenceTimeoutTracker(100, policy)
        waiting.text(149, "")
        assertNull(waiting.failure(149))
        assertEquals(ExecutionTimeoutKind.FIRST_OUTPUT, waiting.failure(150)!!.kind)
        val active = InferenceTimeoutTracker(100, policy)
        active.text(110, "{")
        active.text(149, "")
        assertNull(active.failure(149))
        assertEquals(ExecutionTimeoutKind.INACTIVITY, active.failure(150)!!.kind)
    }

    @Test fun genuineTextExtendsOnlyInactivityNotRequestDeadline() {
        val tracker = InferenceTimeoutTracker(0, tinyPolicy())
        tracker.text(10, "x")
        for (time in 20L..290L step 10L) tracker.text(time, "x")
        assertNull(tracker.failure(299))
        assertEquals(ExecutionTimeoutKind.REQUEST, tracker.failure(300)!!.kind)
    }

    @Test fun lateTextAndReversedClocksCannotEraseAnObservedBreach() {
        val tracker = InferenceTimeoutTracker(0, tinyPolicy())
        tracker.text(10, "x")
        tracker.text(51, "late")
        assertEquals(ExecutionTimeoutKind.INACTIVITY, tracker.failure(51)!!.kind)
        tracker.text(52, "more")
        assertEquals(ExecutionTimeoutKind.INACTIVITY, tracker.failure(20)!!.kind)
        val planning = PlanningLifecyclePolicy(0, tinyPolicy().planningLimits())
        assertNotNull(planning.progress(50).failure)
        assertNotNull(planning.progress(1).failure)
    }

    @Test fun normalPauseSettlesOnlyObservedSegmentTimeAndResumeUsesRemainingAllowance() {
        val policy = ExecutionTimeoutPolicy.forProfile()
        val first = ExecutionAllowancePolicy.reserve(policy, 0)!!
        assertEquals(policy.overallAllowanceMs, first.reservedConsumedMs)
        val consumed = first.settledConsumedMs(5 * 60_000L)
        val next = ExecutionAllowancePolicy.reserve(policy, consumed)!!
        assertEquals(25 * 60_000L, next.segmentMs)
        assertNull(ExecutionAllowancePolicy.reserve(policy, next.reservedConsumedMs))
    }

    @Test fun processDeathConsumesReservationConservativelyAndNeverResetsBudget() {
        val policy = ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.CUSTOM, 720, true)
        var consumed = 0L
        repeat(6) { consumed = ExecutionAllowancePolicy.reserve(policy, consumed)!!.reservedConsumedMs }
        assertEquals(policy.overallAllowanceMs, consumed)
        assertNull(ExecutionAllowancePolicy.reserve(policy, consumed))
        assertNull(ExecutionAllowancePolicy.reserve(policy, Long.MAX_VALUE))
        assertRejected { ExecutionAllowancePolicy.reserve(policy, -1) }
    }

    @Test fun settlementCannotAddUnboundedNativeCancellationOverrunOrNegativeClockTime() {
        val segment = ExecutionAllowancePolicy.reserve(ExecutionTimeoutPolicy.forProfile(), 100)!!
        assertEquals(100L, segment.settledConsumedMs(-1))
        assertEquals(segment.reservedConsumedMs, segment.settledConsumedMs(Long.MAX_VALUE))
    }

    private fun tinyPolicy() = ExecutionTimeoutPolicy.forProfile().copy(
        firstOutputMs = 50, inactivityMs = 40, requestMs = 300, buildMs = 200,
        terminalMs = 100, overallAllowanceMs = 500, serviceSegmentMs = 300)

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("Expected finite policy rejection") } catch (_: IllegalArgumentException) { }
    }
}

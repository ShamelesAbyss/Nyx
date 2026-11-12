package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorPolicyTest {
    private val budget = SupervisorBudget(remainingInferenceCalls = 20, remainingToolCalls = 40, remainingMillis = 60_000)

    @Test
    fun `ready work continues`() {
        val decision = SupervisorPolicy.decide(
            SupervisorInput(totalTasks = 4, completedTasks = 1, pendingTasks = 3, budget = budget)
        )
        assertEquals(SupervisorAction.CONTINUE, decision.action)
    }

    @Test
    fun `first failure asks for a bounded replan with a diagnostic`() {
        val decision = SupervisorPolicy.decide(
            SupervisorInput(
                totalTasks = 4, completedTasks = 1, pendingTasks = 2, failedTasks = 1,
                attempt = 1, maxAttempts = 2, budget = budget,
            )
        )
        assertEquals(SupervisorAction.REPLAN, decision.action)
        assertTrue(decision.addDiagnosticTask)
        assertTrue(decision.reason.contains("1/2"))
    }

    @Test
    fun `second failure pauses rather than retrying again`() {
        val decision = SupervisorPolicy.decide(
            SupervisorInput(
                totalTasks = 2, completedTasks = 0, pendingTasks = 1, failedTasks = 1,
                attempt = 2, maxAttempts = 2, budget = budget,
            )
        )
        assertEquals(SupervisorAction.PAUSE, decision.action)
        assertFalse(decision.addDiagnosticTask)
    }

    @Test
    fun `repeated identical failure pauses even with attempts remaining`() {
        val decision = SupervisorPolicy.decide(
            SupervisorInput(
                totalTasks = 3, completedTasks = 0, pendingTasks = 2, failedTasks = 1,
                attempt = 1, maxAttempts = 3, consecutiveSameFailure = 2, budget = budget,
            )
        )
        assertEquals(SupervisorAction.PAUSE, decision.action)
    }

    @Test
    fun `exhausted inference budget pauses before any call`() {
        val decision = SupervisorPolicy.decide(
            SupervisorInput(
                totalTasks = 3, completedTasks = 0, pendingTasks = 3,
                budget = budget.copy(remainingInferenceCalls = 0),
            )
        )
        assertEquals(SupervisorAction.PAUSE, decision.action)
        assertTrue(decision.reason.contains("Inference budget"))
    }

    @Test
    fun `empty plan fails instead of looping`() {
        val decision = SupervisorPolicy.decide(SupervisorInput(totalTasks = 0, completedTasks = 0, pendingTasks = 0, budget = budget))
        assertEquals(SupervisorAction.FAIL, decision.action)
    }

    @Test
    fun `blocked graph with no ready work pauses`() {
        val decision = SupervisorPolicy.decide(
            SupervisorInput(totalTasks = 3, completedTasks = 1, pendingTasks = 0, runningTasks = 0, budget = budget)
        )
        assertEquals(SupervisorAction.PAUSE, decision.action)
    }
}

package com.nyx.ai.ollama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaLifecyclePolicyTest {

    private fun assertTransition(
        current: OllamaServerStatus,
        event: OllamaLifecycleEvent,
        expected: OllamaServerStatus,
    ) {
        assertEquals(
            "Expected $current + $event -> $expected",
            expected,
            OllamaLifecyclePolicy.nextState(current, event),
        )
    }

    // ------------------------------------------------------------------ start planning

    @Test
    fun `a healthy api is adopted instead of duplicated`() {
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = true,
            apiHealthy = true,
            installed = false,
            savedPid = null,
            ownership = OllamaOwnership.UNKNOWN,
        )
        assertEquals(OllamaStartDecision.USE_RUNNING_SERVER, plan.decision)
        assertTrue(plan.reason.contains("adopted"))
    }

    @Test
    fun `a healthy api wins even when a saved nyx record exists`() {
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = true,
            apiHealthy = true,
            installed = true,
            savedPid = 4321,
            ownership = OllamaOwnership.OWNED,
        )
        assertEquals(OllamaStartDecision.USE_RUNNING_SERVER, plan.decision)
    }

    @Test
    fun `a verified live nyx process refuses a second start`() {
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = true,
            apiHealthy = false,
            installed = true,
            savedPid = 4321,
            ownership = OllamaOwnership.OWNED,
        )
        assertEquals(OllamaStartDecision.REFUSE_DUPLICATE, plan.decision)
        assertTrue(plan.reason.contains("4321"))
    }

    @Test
    fun `an unverifiable saved pid refuses a second start`() {
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = true,
            apiHealthy = false,
            installed = true,
            savedPid = 4321,
            ownership = OllamaOwnership.UNKNOWN,
        )
        assertEquals(OllamaStartDecision.REFUSE_UNVERIFIED_OWNERSHIP, plan.decision)
    }

    @Test
    fun `stale records do not block a fresh start`() {
        for (stale in listOf(OllamaOwnership.MISSING, OllamaOwnership.PID_REUSED, OllamaOwnership.NOT_OLLAMA)) {
            val plan = OllamaLifecyclePolicy.planStart(
                endpointLoopback = true,
                apiHealthy = false,
                installed = true,
                savedPid = 4321,
                ownership = stale,
            )
            assertEquals("stale=$stale must not block start", OllamaStartDecision.START_NEW, plan.decision)
        }
    }

    @Test
    fun `starting requires an installed binary`() {
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = true,
            apiHealthy = false,
            installed = false,
            savedPid = null,
            ownership = OllamaOwnership.UNKNOWN,
        )
        assertEquals(OllamaStartDecision.INSTALL_REQUIRED, plan.decision)
    }

    @Test
    fun `a remote endpoint can never be started locally`() {
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = false,
            apiHealthy = false,
            installed = true,
            savedPid = null,
            ownership = OllamaOwnership.UNKNOWN,
        )
        assertEquals(OllamaStartDecision.REFUSE_REMOTE_ENDPOINT, plan.decision)
    }

    @Test
    fun `a healthy remote endpoint is adopted but never started`() {
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = false,
            apiHealthy = true,
            installed = true,
            savedPid = null,
            ownership = OllamaOwnership.UNKNOWN,
        )
        // Loopback is checked first because a remote server can never be a local process NYX manages.
        assertEquals(OllamaStartDecision.REFUSE_REMOTE_ENDPOINT, plan.decision)
    }

    // ------------------------------------------------------------------ stop planning

    @Test
    fun `adopted server is never stopped`() {
        val plan = OllamaLifecyclePolicy.planStop(
            startedByNyx = false,
            savedPid = 4321,
            ownership = OllamaOwnership.OWNED,
        )
        assertEquals(OllamaStopDecision.NOT_OWNED, plan.decision)
        assertTrue(plan.reason.contains("not started by NYX"))
    }

    @Test
    fun `owned verified server is stopped`() {
        val plan = OllamaLifecyclePolicy.planStop(
            startedByNyx = true,
            savedPid = 4321,
            ownership = OllamaOwnership.OWNED,
        )
        assertEquals(OllamaStopDecision.STOP_OWNED, plan.decision)
    }

    @Test
    fun `a recycled pid is never a stop target`() {
        for (verdict in listOf(OllamaOwnership.PID_REUSED, OllamaOwnership.NOT_OLLAMA, OllamaOwnership.UNKNOWN)) {
            val plan = OllamaLifecyclePolicy.planStop(
                startedByNyx = true,
                savedPid = 4321,
                ownership = verdict,
            )
            assertEquals("verdict=$verdict must be refused", OllamaStopDecision.NOT_OWNED, plan.decision)
        }
    }

    @Test
    fun `a missing process only clears the record`() {
        val plan = OllamaLifecyclePolicy.planStop(
            startedByNyx = true,
            savedPid = 4321,
            ownership = OllamaOwnership.MISSING,
        )
        assertEquals(OllamaStopDecision.NOT_RUNNING, plan.decision)
    }

    @Test
    fun `no record means no stop`() {
        val plan = OllamaLifecyclePolicy.planStop(
            startedByNyx = true,
            savedPid = null,
            ownership = OllamaOwnership.UNKNOWN,
        )
        assertEquals(OllamaStopDecision.NOT_OWNED, plan.decision)
    }

    // ------------------------------------------------------------------ restart planning

    @Test
    fun `restart refuses an adopted server`() {
        val decision = OllamaLifecyclePolicy.planRestart(
            running = true,
            startedByNyx = false,
            savedPid = null,
            ownership = OllamaOwnership.UNKNOWN,
        )
        assertEquals(OllamaRestartDecision.REFUSE_NOT_OWNED, decision)
    }

    @Test
    fun `restart refuses an owned flag whose pid cannot be verified`() {
        val decision = OllamaLifecyclePolicy.planRestart(
            running = true,
            startedByNyx = true,
            savedPid = 4321,
            ownership = OllamaOwnership.PID_REUSED,
        )
        assertEquals(OllamaRestartDecision.REFUSE_NOT_OWNED, decision)
    }

    @Test
    fun `restart stops and starts a verified nyx process`() {
        val decision = OllamaLifecyclePolicy.planRestart(
            running = true,
            startedByNyx = true,
            savedPid = 4321,
            ownership = OllamaOwnership.OWNED,
        )
        assertEquals(OllamaRestartDecision.STOP_THEN_START, decision)
    }

    @Test
    fun `restart simply starts a stopped server`() {
        assertEquals(
            OllamaRestartDecision.STOP_THEN_START,
            OllamaLifecyclePolicy.planRestart(
                running = false,
                startedByNyx = false,
                savedPid = null,
                ownership = OllamaOwnership.UNKNOWN,
            ),
        )
        assertEquals(
            OllamaRestartDecision.STOP_THEN_START,
            OllamaLifecyclePolicy.planRestart(
                running = false,
                startedByNyx = true,
                savedPid = 4321,
                ownership = OllamaOwnership.MISSING,
            ),
        )
    }

    // ------------------------------------------------------------------ state transitions

    @Test
    fun `detection moves only between installed and missing states`() {
        assertTransition(OllamaServerStatus.STOPPED, OllamaLifecycleEvent.DETECTED_MISSING, OllamaServerStatus.NOT_INSTALLED)
        assertTransition(OllamaServerStatus.NOT_INSTALLED, OllamaLifecycleEvent.DETECTED_INSTALLED, OllamaServerStatus.STOPPED)
        assertTransition(OllamaServerStatus.RUNNING, OllamaLifecycleEvent.DETECTED_MISSING, OllamaServerStatus.RUNNING)
        assertTransition(OllamaServerStatus.STARTING, OllamaLifecycleEvent.DETECTED_INSTALLED, OllamaServerStatus.STARTING)
    }

    @Test
    fun `start and adopt transitions reach running`() {
        assertTransition(OllamaServerStatus.STOPPED, OllamaLifecycleEvent.START_REQUESTED, OllamaServerStatus.STARTING)
        assertTransition(OllamaServerStatus.NOT_INSTALLED, OllamaLifecycleEvent.START_REQUESTED, OllamaServerStatus.STARTING)
        assertTransition(OllamaServerStatus.ERROR, OllamaLifecycleEvent.START_REQUESTED, OllamaServerStatus.STARTING)
        assertTransition(OllamaServerStatus.STARTING, OllamaLifecycleEvent.START_SUCCEEDED, OllamaServerStatus.RUNNING)
        assertTransition(OllamaServerStatus.STARTING, OllamaLifecycleEvent.ADOPTED, OllamaServerStatus.RUNNING)
        assertTransition(OllamaServerStatus.STOPPED, OllamaLifecycleEvent.ADOPTED, OllamaServerStatus.RUNNING)
    }

    @Test
    fun `a running server is never moved back to starting`() {
        assertTransition(OllamaServerStatus.RUNNING, OllamaLifecycleEvent.START_REQUESTED, OllamaServerStatus.RUNNING)
        assertTransition(OllamaServerStatus.STARTING, OllamaLifecycleEvent.START_REQUESTED, OllamaServerStatus.STARTING)
        assertTransition(OllamaServerStatus.STOPPING, OllamaLifecycleEvent.START_REQUESTED, OllamaServerStatus.STOPPING)
    }

    @Test
    fun `start failure is an error state`() {
        assertTransition(OllamaServerStatus.STARTING, OllamaLifecycleEvent.START_FAILED, OllamaServerStatus.ERROR)
        assertTransition(OllamaServerStatus.STOPPED, OllamaLifecycleEvent.START_FAILED, OllamaServerStatus.ERROR)
    }

    @Test
    fun `stop transitions and refusals are explicit`() {
        assertTransition(OllamaServerStatus.RUNNING, OllamaLifecycleEvent.STOP_REQUESTED, OllamaServerStatus.STOPPING)
        assertTransition(OllamaServerStatus.STARTING, OllamaLifecycleEvent.STOP_REQUESTED, OllamaServerStatus.STOPPING)
        assertTransition(OllamaServerStatus.ERROR, OllamaLifecycleEvent.STOP_REQUESTED, OllamaServerStatus.STOPPING)
        assertTransition(OllamaServerStatus.STOPPED, OllamaLifecycleEvent.STOP_REQUESTED, OllamaServerStatus.STOPPED)
        assertTransition(OllamaServerStatus.STOPPING, OllamaLifecycleEvent.STOP_SUCCEEDED, OllamaServerStatus.STOPPED)
        assertTransition(OllamaServerStatus.STOPPING, OllamaLifecycleEvent.STOP_FAILED, OllamaServerStatus.ERROR)
        assertTransition(OllamaServerStatus.STOPPING, OllamaLifecycleEvent.STOP_REFUSED, OllamaServerStatus.RUNNING)
        assertTransition(OllamaServerStatus.RUNNING, OllamaLifecycleEvent.STOP_REFUSED, OllamaServerStatus.RUNNING)
    }

    @Test
    fun `reset returns to stopped`() {
        assertTransition(OllamaServerStatus.ERROR, OllamaLifecycleEvent.RESET, OllamaServerStatus.STOPPED)
        assertTransition(OllamaServerStatus.RUNNING, OllamaLifecycleEvent.RESET, OllamaServerStatus.STOPPED)
    }

    @Test
    fun `a live but unreachable nyx process is starting then an error`() {
        assertEquals(OllamaServerStatus.STARTING, OllamaLifecyclePolicy.statusForOwnedButNotReady(null))
        assertEquals(OllamaServerStatus.STARTING, OllamaLifecyclePolicy.statusForOwnedButNotReady(5_000L))
        assertEquals(
            OllamaServerStatus.STARTING,
            OllamaLifecyclePolicy.statusForOwnedButNotReady(OllamaReadiness.STARTUP_GRACE_MILLIS - 1),
        )
        assertEquals(
            OllamaServerStatus.ERROR,
            OllamaLifecyclePolicy.statusForOwnedButNotReady(OllamaReadiness.STARTUP_GRACE_MILLIS),
        )
    }

    // ------------------------------------------------------------------ readiness budget

    @Test
    fun `readiness polls back off within a bounded budget`() {
        assertEquals(250L, OllamaReadiness.pollIntervalMillis(1))
        assertEquals(500L, OllamaReadiness.pollIntervalMillis(2))
        assertEquals(1_000L, OllamaReadiness.pollIntervalMillis(3))
        assertEquals(2_000L, OllamaReadiness.pollIntervalMillis(4))
        assertEquals(2_000L, OllamaReadiness.pollIntervalMillis(5))
        assertEquals(2_000L, OllamaReadiness.pollIntervalMillis(500))
        assertEquals(250L, OllamaReadiness.pollIntervalMillis(0))
        assertEquals(250L, OllamaReadiness.pollIntervalMillis(-10))
    }

    @Test
    fun `readiness timeout is inclusive and configurable`() {
        assertFalse(OllamaReadiness.isTimedOut(0))
        assertFalse(OllamaReadiness.isTimedOut(44_999))
        assertTrue(OllamaReadiness.isTimedOut(45_000))
        assertTrue(OllamaReadiness.isTimedOut(60_000))
        assertFalse(OllamaReadiness.isTimedOut(0, 0))
        assertTrue(OllamaReadiness.isTimedOut(1, 1))
    }

    @Test
    fun `readiness failure reports the timeout, attempts, and log tail`() {
        val message = OllamaReadiness.failureMessage(
            attempts = 9,
            detail = "connection refused",
            logTail = "error: port 11434 already in use\npanic: bind failed",
        )
        assertTrue(message.contains("45 s"))
        assertTrue(message.contains("9 API probe"))
        assertTrue(message.contains("connection refused"))
        assertTrue(message.contains("port 11434 already in use"))
        assertTrue(message.contains("panic: bind failed"))
    }

    @Test
    fun `readiness failure omits empty detail and log`() {
        val message = OllamaReadiness.failureMessage(attempts = 3)
        assertTrue(message.startsWith("Ollama did not become ready within 45 s"))
        assertFalse(message.contains("Last log lines"))
    }

    @Test
    fun `success messages distinguish adoption from a fresh start`() {
        assertTrue(OllamaReadiness.successMessage(adopted = true, pid = null).contains("Adopted"))
        assertTrue(OllamaReadiness.successMessage(adopted = false, pid = 4321).contains("4321"))
    }

    // ------------------------------------------------------------------ state model

    @Test
    fun `state exposes stop eligibility only for owned running processes`() {
        val running = OllamaServerState(
            status = OllamaServerStatus.RUNNING,
            pid = 4321,
            startedByNyx = true,
        )
        assertTrue(running.isRunning)
        assertTrue(running.canStop)
        assertFalse(running.isBusy)

        assertFalse(running.copy(startedByNyx = false).canStop)
        assertFalse(running.copy(pid = null).canStop)
        assertFalse(running.copy(status = OllamaServerStatus.STOPPED).canStop)
        assertTrue(running.copy(status = OllamaServerStatus.STARTING).canStop)
        assertTrue(running.copy(status = OllamaServerStatus.STOPPING).isBusy)
    }
}

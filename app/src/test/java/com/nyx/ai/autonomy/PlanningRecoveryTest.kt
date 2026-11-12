package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningRecoveryTest {
    @Test
    fun `validation failures distinguish malformed json from schema rejection`() {
        val invalid = PlanningRecovery.classify(ObjectivePlanValidationException("Plan is not valid strict JSON."))
        assertEquals(PlanningFailureKind.INVALID_JSON, invalid.kind)
        val schema = PlanningRecovery.classify(ObjectivePlanValidationException("Task 'a' must contain a 'title'."))
        assertEquals(PlanningFailureKind.SCHEMA_REJECTED, schema.kind)
        assertTrue(PlanningRecovery.shouldRepair(invalid.kind))
        assertTrue(PlanningRecovery.shouldRepair(schema.kind))
    }

    @Test
    fun `transport timeout and interruption are not repaired`() {
        assertEquals(PlanningFailureKind.TIMEOUT, PlanningRecovery.classify(PlanningTimeoutException("deadline")).kind)
        assertEquals(PlanningFailureKind.INTERRUPTED, PlanningRecovery.classify(kotlinx.coroutines.CancellationException("stop")).kind)
        assertFalse(PlanningRecovery.shouldRepair(PlanningFailureKind.TIMEOUT))
        assertFalse(PlanningRecovery.shouldRepair(PlanningFailureKind.INTERRUPTED))
    }

    @Test
    fun `context exhaustion is reported only from matching evidence`() {
        val context = PlanningRecovery.classify(IllegalStateException("maximum context length exceeded"))
        assertEquals(PlanningFailureKind.CONTEXT_EXHAUSTED, context.kind)
        val generic = PlanningRecovery.classify(IllegalStateException("provider failed"))
        assertEquals(PlanningFailureKind.PROVIDER_ERROR, generic.kind)
    }

    @Test
    fun `repair prompt is bounded and preserves the validation reason`() {
        val failure = PlanningFailure(PlanningFailureKind.SCHEMA_REJECTED, "Task 'a' must contain a 'title'.")
        val prompt = PlanningRecovery.repairPrompt("x".repeat(20_000), failure)
        assertTrue(prompt.contains("strict JSON"))
        assertTrue(prompt.contains("Task 'a' must contain a 'title'."))
        assertTrue(prompt.length < 20_000)
    }

    @Test
    fun `attempt limits are explicit and bounded`() {
        assertEquals(2, PlanningRecovery.MAX_PLAN_ATTEMPTS)
        assertTrue(PlanningRecovery.ATTEMPT_TIMEOUT_MS in 30_000..300_000)
    }

    @Test
    fun `failure diagnostics strip terminal controls and credentials`() {
        val failure = PlanningRecovery.classify(
            IllegalStateException("provider \u001B[31m failed Authorization: Bearer secret123 token=abc123"),
        )
        assertFalse(failure.reason.contains("\u001B"))
        assertFalse(failure.reason.contains("secret123"))
        assertFalse(failure.reason.contains("abc123"))
        assertTrue(failure.reason.contains("[redacted]"))
    }
}

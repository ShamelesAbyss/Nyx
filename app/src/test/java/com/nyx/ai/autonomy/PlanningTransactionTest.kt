package com.nyx.ai.autonomy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlanningTransactionTest {
    private val valid = """{"steps":[{"do":"Explain the answer","worker":"inference"}]}"""

    @Test fun completedMalformedResponseGetsExactlyOneCompactRepair() = runBlocking {
        var requests = 0
        var rejects = 0
        var repairs = 0
        val tasks = PlanningRecovery.validatedPlan("obj", "agent", request = { attempt, previous ->
            requests++
            if (attempt == 1) { assertNull(previous); "{\"steps\":" }
            else { assertNotNull(previous); valid }
        }, onRejected = { _, _ -> rejects++ }, onRepair = { repairs++ })
        assertEquals(2, requests)
        assertEquals(1, rejects)
        assertEquals(1, repairs)
        assertEquals(1, tasks.size)
    }

    @Test fun rejectedSecondResponseStopsWithoutReturningAnyPartialTasks() = runBlocking {
        var requests = 0
        var accepted = false
        try {
            PlanningRecovery.validatedPlan("obj", "agent", request = { _, _ ->
                requests++
                """{"steps":[{"do":"Valid first task","worker":"inference"},{"do":"Invalid later task","worker":"terminal"}]}"""
            }).also { accepted = true }
            fail("Expected plan rejection")
        } catch (failure: PlanningFailureException) {
            assertEquals(PlanningFailureKind.SCHEMA_REJECTED, failure.failure.kind)
        }
        assertEquals(2, requests)
        assertFalse(accepted)
    }

    @Test fun transportTimeoutDoesNotTriggerARepair() = runBlocking {
        var requests = 0
        try {
            PlanningRecovery.validatedPlan("obj", "agent", request = { _, _ -> requests++; throw PlanningTimeoutException("first text deadline") })
            fail("Expected timeout")
        } catch (_: PlanningTimeoutException) { }
        assertEquals(1, requests)
    }

    @Test fun callerCancellationPropagatesWithoutARepairOrResult() = runBlocking {
        var requests = 0
        try {
            PlanningRecovery.validatedPlan("obj", "agent", request = { _, _ -> requests++; throw CancellationException("stop") })
            fail("Expected cancellation")
        } catch (cancel: CancellationException) { assertEquals("stop", cancel.message) }
        assertEquals(1, requests)
    }

    @Test fun emptyResponseIsReportedWithoutARepair() = runBlocking {
        var requests = 0
        try {
            PlanningRecovery.validatedPlan("obj", "agent", request = { _, _ -> requests++; " " })
            fail("Expected empty-response rejection")
        } catch (failure: PlanningFailureException) { assertEquals(PlanningFailureKind.EMPTY_RESPONSE, failure.failure.kind) }
        assertEquals(1, requests)
    }

    @Test fun blankFieldIsSchemaRejectionAndCanBeRepaired() = runBlocking {
        var requests = 0
        val tasks = PlanningRecovery.validatedPlan("obj", "agent", request = { attempt, _ ->
            requests++
            if (attempt == 1) """{"steps":[{"do":" ","worker":"inference"}]}""" else valid
        })
        assertEquals(2, requests)
        assertEquals(1, tasks.size)
    }

    @Test fun additionalValidationRunsOnlyAfterAllTasksParseAndBeforeReturn() = runBlocking {
        var checks = 0
        val tasks = PlanningRecovery.validatedPlan("obj", "agent", request = { _, _ -> valid }, validate = { parsed ->
            checks++
            assertEquals("agent", parsed.single().agentId)
        })
        assertEquals(1, checks)
        assertEquals(1, tasks.size)
    }

    @Test fun compactRepairStaysSmallAndDoesNotRequestFullSchema() {
        val prompt = PlanningRecovery.repairPrompt("x".repeat(20_000), PlanningFailure(PlanningFailureKind.SCHEMA_REJECTED, "Missing worker"), compact = true)
        assertTrue(prompt.length < 3_000)
        assertTrue(prompt.contains("\"steps\""))
        assertFalse(prompt.contains("dependsOn"))
        assertTrue(prompt.contains("Missing worker"))
    }
}

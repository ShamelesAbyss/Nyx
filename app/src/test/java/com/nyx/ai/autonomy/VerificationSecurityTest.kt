package com.nyx.ai.autonomy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerificationSecurityTest {
    @Test fun modelClaimCannotProveBuildOutput() = runBlocking {
        val spec = EvidenceSpec(EvidenceKind.OUTPUT_CONTAINS, expected = "BUILD SUCCESSFUL")
        assertFalse(TaskVerifier.verify(listOf(spec), ObservationBundle(agentOutput = "BUILD SUCCESSFUL")).passed)
        assertTrue(TaskVerifier.verify(listOf(spec), ObservationBundle(stdout = "BUILD SUCCESSFUL")).passed)
    }
    @Test fun cancellationNotTurnedIntoFailure() = runBlocking {
        try {
            TaskVerifier.verify(listOf(EvidenceSpec(EvidenceKind.FILE_EXISTS, target = "a")), ObservationBundle(fileExists = { throw CancellationException() }))
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun fabricatedZeroExitWithoutObservationFails() = runBlocking {
        assertFalse(TaskVerifier.verify(listOf(EvidenceSpec(EvidenceKind.EXIT_CODE, expected = "0")), ObservationBundle(agentOutput = "exit 0")).passed)
    }
}

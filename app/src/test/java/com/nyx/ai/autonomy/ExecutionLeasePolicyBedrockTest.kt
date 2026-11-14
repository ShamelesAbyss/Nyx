package com.nyx.ai.autonomy

import org.junit.Assert.*
import org.junit.Test

class ExecutionLeasePolicyBedrockTest {
    @Test fun deniedPreAcquisitionAndLostLeaseNeverPermitCheckpointMutations() {
        assertFalse(ExecutionLeasePolicy.mayCheckpoint(false, false, "a", "a"))
        assertFalse(ExecutionLeasePolicy.mayCheckpoint(false, false, "a", "b"))
        assertFalse(ExecutionLeasePolicy.mayCheckpoint(true, true, "a", "a"))
        assertFalse(ExecutionLeasePolicy.mayCheckpoint(true, false, "a", "b"))
        assertFalse(ExecutionLeasePolicy.mayCheckpoint(true, false, "a", null))
        assertTrue(ExecutionLeasePolicy.mayCheckpoint(true, false, "a", "a"))
    }

    @Test fun expiryBoundariesAndNonrunningStatesStayConservative() {
        assertFalse(ExecutionLeasePolicy.needsRecovery("running", 100, 99))
        assertTrue(ExecutionLeasePolicy.needsRecovery("running", 100, 100))
        assertTrue(ExecutionLeasePolicy.needsRecovery("planning", null, 0))
        assertFalse(ExecutionLeasePolicy.needsRecovery("completed", null, 100))
        assertFalse(ExecutionLeasePolicy.needsRecovery("needs_attention", null, 100))
    }
}

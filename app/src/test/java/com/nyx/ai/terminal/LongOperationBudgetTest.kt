package com.nyx.ai.terminal

import com.nyx.ai.development.DevelopmentBudget
import org.junit.Assert.*
import org.junit.Test

class LongOperationBudgetTest {
    @Test fun trustedSelectedCeilingBoundsModelRequestedTimeout() {
        val base = ModelCommandRequest("echo hello", "/root/nyx-workspaces/fixture", timeoutSeconds = 7200)
        assertEquals(600L, ModelCommandPolicy.assess(base).timeoutSeconds)
        assertEquals(1800L, ModelCommandPolicy.assess(base.copy(maximumTimeoutSeconds = 1800)).timeoutSeconds)
        assertFalse(ModelCommandPolicy.assess(base.copy(maximumTimeoutSeconds = 7201)).allowed)
    }
    @Test fun longRepairElapsedBudgetRemainsFiniteAndOtherLimitsRemain() {
        assertEquals(7_200_000L, DevelopmentBudget(maxElapsedMillis = 7_200_000).maxElapsedMillis)
        assertThrows(IllegalArgumentException::class.java) { DevelopmentBudget(maxElapsedMillis = 7_200_001) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentBudget(maxElapsedMillis = 7_200_000, maxInferenceCalls = 7) }
    }
}

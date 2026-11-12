package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePolicyTest {
    private fun device(
        totalMemMb: Long = 8192,
        availMemMb: Long = 4096,
        lowMemory: Boolean = false,
        thermalStatus: Int = 0,
        batteryPercent: Int = 80,
        charging: Boolean = false,
    ) = DeviceResources(
        totalMemMb = totalMemMb,
        availMemMb = availMemMb,
        lowMemory = lowMemory,
        thermalStatus = thermalStatus,
        batteryPercent = batteryPercent,
        charging = charging,
        availableStorageMb = 100_000,
    )

    @Test fun constrainedTierUsesMinimumCapacityAndContext() {
        val profile = ResourcePolicy.profile(device(lowMemory = true))
        assertEquals("constrained", profile.tier)
        assertEquals(SchedulingPolicy(1, 1, 1, 1, 1, 1), profile.policy)
        assertEquals(6000, profile.maxContextChars)
        assertTrue(profile.reason.isNotBlank())
    }

    @Test fun eachConstrainedConditionTriggersTheConstrainedTier() {
        assertEquals("constrained", ResourcePolicy.profile(device(totalMemMb = 3071)).tier)
        assertEquals("constrained", ResourcePolicy.profile(device(availMemMb = 767)).tier)
        assertEquals("constrained", ResourcePolicy.profile(device(thermalStatus = 3)).tier)
        assertEquals("constrained", ResourcePolicy.profile(device(batteryPercent = 15, charging = false)).tier)
        assertEquals("capable", ResourcePolicy.profile(device(batteryPercent = 15, charging = true)).tier)
    }

    @Test fun standardTierUsesItsSpecifiedCapacitiesAndContext() {
        val byMemory = ResourcePolicy.profile(device(totalMemMb = 6143))
        assertEquals("standard", byMemory.tier)
        assertEquals(SchedulingPolicy(1, 2, 2, 2, 1, 2), byMemory.policy)
        assertEquals(10000, byMemory.maxContextChars)
        assertTrue(byMemory.reason.isNotBlank())

        assertEquals("standard", ResourcePolicy.profile(device(thermalStatus = 2)).tier)
        assertEquals("constrained", ResourcePolicy.profile(device(thermalStatus = 3)).tier)
    }

    @Test fun capableTierUsesItsSpecifiedCapacitiesAndContext() {
        val profile = ResourcePolicy.profile(device())
        assertEquals("capable", profile.tier)
        assertEquals(SchedulingPolicy(1, 3, 3, 2, 1, 4), profile.policy)
        assertEquals(16000, profile.maxContextChars)
        assertTrue(profile.reason.isNotBlank())
    }

    @Test fun requestedParallelismOnlyLowersTotalCapacity() {
        val constrained = ResourcePolicy.profile(device(lowMemory = true), requestedParallelism = 9)
        assertEquals(1, constrained.policy.maxTotal)

        val standardLowered = ResourcePolicy.profile(device(totalMemMb = 5000), requestedParallelism = 1)
        assertEquals(1, standardLowered.policy.maxTotal)
        assertEquals(2, ResourcePolicy.profile(device(totalMemMb = 5000)).policy.maxTotal)

        val capableLowered = ResourcePolicy.profile(device(), requestedParallelism = 2)
        assertEquals(2, capableLowered.policy.maxTotal)
        assertEquals(4, ResourcePolicy.profile(device(), requestedParallelism = 50).policy.maxTotal)
        assertEquals(4, ResourcePolicy.profile(device()).policy.maxTotal)
    }

    @Test fun allReturnedConcurrencyValuesAreAtLeastOne() {
        val profile = ResourcePolicy.profile(device(), requestedParallelism = 0)
        val limits = listOf(
            profile.policy.maxInference,
            profile.policy.maxTerminal,
            profile.policy.maxFilesystem,
            profile.policy.maxVerification,
            profile.policy.maxDevelopment,
            profile.policy.maxTotal,
        )
        assertTrue(limits.all { it >= 1 })
        assertTrue(ResourcePolicy.profile(device(), requestedParallelism = 1).policy.maxTotal >= 1)
    }
}

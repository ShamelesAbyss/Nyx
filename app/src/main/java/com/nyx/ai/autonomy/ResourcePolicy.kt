package com.nyx.ai.autonomy

data class DeviceResources(
    val totalMemMb: Long,
    val availMemMb: Long,
    val lowMemory: Boolean,
    val thermalStatus: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val availableStorageMb: Long,
)

data class ResourceProfile(
    val tier: String,
    val policy: SchedulingPolicy,
    val maxContextChars: Int,
    val reason: String,
)

object ResourcePolicy {
    fun profile(
        resources: DeviceResources,
        requestedParallelism: Int = 0,
        parallelismCap: Int = Int.MAX_VALUE,
    ): ResourceProfile {
        val base = when {
            resources.lowMemory ||
                resources.totalMemMb < 3072L ||
                resources.availMemMb < 768L ||
                resources.thermalStatus >= 3 ||
                (resources.batteryPercent <= 15 && !resources.charging) -> ResourceProfile(
                    tier = "constrained",
                    policy = SchedulingPolicy(
                        maxInference = 1,
                        maxTerminal = 1,
                        maxFilesystem = 1,
                        maxVerification = 1,
                        maxDevelopment = 1,
                        maxTotal = 1,
                    ),
                    maxContextChars = 6000,
                    reason = "Limited memory, heat, or battery; using a minimal workload.",
                )

            resources.totalMemMb < 6144L || resources.thermalStatus == 2 -> ResourceProfile(
                tier = "standard",
                policy = SchedulingPolicy(
                    maxInference = 1,
                    maxTerminal = 2,
                    maxFilesystem = 2,
                    maxVerification = 2,
                    maxDevelopment = 1,
                    maxTotal = 2,
                ),
                maxContextChars = 10000,
                reason = "Moderate device resources; limiting concurrent work.",
            )

            else -> ResourceProfile(
                tier = "capable",
                policy = SchedulingPolicy(
                    maxInference = 1,
                    maxTerminal = 3,
                    maxFilesystem = 3,
                    maxVerification = 2,
                    maxDevelopment = 1,
                    maxTotal = 4,
                ),
                maxContextChars = 16000,
                reason = "Device resources support a broader workload.",
            )
        }

        val adjustedPolicy = base.policy.copy(
            maxInference = base.policy.maxInference.coerceAtLeast(1),
            maxTerminal = base.policy.maxTerminal.coerceAtLeast(1),
            maxFilesystem = base.policy.maxFilesystem.coerceAtLeast(1),
            maxVerification = base.policy.maxVerification.coerceAtLeast(1),
            maxDevelopment = base.policy.maxDevelopment.coerceAtLeast(1),
            maxTotal = minOf(
                base.policy.maxTotal,
                if (requestedParallelism > 0) requestedParallelism else Int.MAX_VALUE,
                if (parallelismCap > 0) parallelismCap else Int.MAX_VALUE,
            ).coerceAtLeast(1),
        )
        return base.copy(policy = adjustedPolicy)
    }
}

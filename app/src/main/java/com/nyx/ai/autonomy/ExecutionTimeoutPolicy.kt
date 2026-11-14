package com.nyx.ai.autonomy

import java.util.concurrent.TimeoutException

/** User-selected ceilings, not estimates of model throughput or Android execution guarantees. */
enum class ExecutionTimeoutProfile { QUICK, STANDARD, PATIENT, EXTENDED, CUSTOM }

data class ExecutionTimeoutPolicy(
    val profile: ExecutionTimeoutProfile,
    val firstOutputMs: Long,
    val inactivityMs: Long,
    val requestMs: Long,
    val buildMs: Long,
    val terminalMs: Long,
    val overallAllowanceMs: Long,
    val serviceSegmentMs: Long,
    val explicitLongRunApproval: Boolean = false,
) {
    init {
        require(listOf(firstOutputMs, inactivityMs, requestMs, buildMs, terminalMs,
            overallAllowanceMs, serviceSegmentMs).all { it > 0 }) { "Timeouts must be finite and positive." }
        require(overallAllowanceMs <= MAX_OVERALL_ALLOWANCE_MS) { "Overall allowance cannot exceed 12 checkpointed hours." }
        require(serviceSegmentMs <= MAX_SERVICE_SEGMENT_MS && serviceSegmentMs <= overallAllowanceMs) { "Service segments cannot exceed two hours or the overall allowance." }
        require(firstOutputMs <= requestMs && inactivityMs <= requestMs && requestMs <= serviceSegmentMs) { "Request deadlines must fit the finite service segment." }
        require(buildMs <= serviceSegmentMs && terminalMs <= serviceSegmentMs) { "Tool deadlines must fit the finite service segment." }
        require(profile !in setOf(ExecutionTimeoutProfile.EXTENDED, ExecutionTimeoutProfile.CUSTOM) || explicitLongRunApproval) { "Extended and Custom require explicit user approval." }
    }

    fun planningLimits(): PlanningLimits = PlanningLimits(
        queueWaitMs = firstOutputMs, firstTokenMs = firstOutputMs, stallMs = inactivityMs,
        activeGenerationMs = requestMs, hardLimitMs = requestMs,
    )

    companion object {
        const val MAX_SERVICE_SEGMENT_MS = 120 * 60_000L
        const val MAX_OVERALL_ALLOWANCE_MS = 12 * 60 * 60_000L

        fun forProfile(
            profile: ExecutionTimeoutProfile = ExecutionTimeoutProfile.STANDARD,
            customMinutes: Int? = null,
            explicitLongRunApproval: Boolean = false,
        ): ExecutionTimeoutPolicy {
            val minutes = when (profile) {
                ExecutionTimeoutProfile.QUICK -> 5
                ExecutionTimeoutProfile.STANDARD -> 30
                ExecutionTimeoutProfile.PATIENT -> 60
                ExecutionTimeoutProfile.EXTENDED -> 120
                ExecutionTimeoutProfile.CUSTOM -> requireNotNull(customMinutes) { "Custom requires finite minutes." }.also {
                    require(it in 1..720) { "Custom must be between 1 and 720 minutes." }
                }
            }
            require(profile == ExecutionTimeoutProfile.CUSTOM || customMinutes == null) { "Custom minutes require the Custom profile." }
            val overall = minutes * 60_000L
            val segment = minOf(overall, MAX_SERVICE_SEGMENT_MS)
            val first = when (profile) {
                ExecutionTimeoutProfile.QUICK -> 2 * 60_000L
                ExecutionTimeoutProfile.STANDARD -> 10 * 60_000L
                ExecutionTimeoutProfile.PATIENT -> 20 * 60_000L
                else -> 30 * 60_000L
            }
            val idle = when (profile) {
                ExecutionTimeoutProfile.QUICK -> 60_000L
                ExecutionTimeoutProfile.STANDARD -> 5 * 60_000L
                ExecutionTimeoutProfile.PATIENT -> 10 * 60_000L
                else -> 15 * 60_000L
            }
            val build = when (profile) {
                ExecutionTimeoutProfile.QUICK -> 3 * 60_000L
                ExecutionTimeoutProfile.STANDARD -> 10 * 60_000L
                ExecutionTimeoutProfile.PATIENT -> 30 * 60_000L
                else -> MAX_SERVICE_SEGMENT_MS
            }
            val terminal = if (explicitLongRunApproval && profile in setOf(ExecutionTimeoutProfile.EXTENDED, ExecutionTimeoutProfile.CUSTOM)) segment else minOf(build / 2, segment)
            return ExecutionTimeoutPolicy(profile, minOf(first, segment), minOf(idle, segment), segment,
                minOf(build, segment), terminal, overall, segment, explicitLongRunApproval)
        }
    }
}

enum class ExecutionTimeoutKind { FIRST_OUTPUT, INACTIVITY, REQUEST }
class ExecutionTimeoutException(val kind: ExecutionTimeoutKind, message: String) : TimeoutException(message)

/** Only actual nonempty content deltas advance this clock; transport keepalive is not output. */
class InferenceTimeoutTracker(private val submittedAtMs: Long, private val policy: ExecutionTimeoutPolicy) {
    private var firstAtMs: Long? = null
    private var lastAtMs: Long? = null
    private var breached: ExecutionTimeoutException? = null

    @Synchronized fun text(atMs: Long, content: String) {
        if (content.isEmpty()) return
        val observed = maxOf(submittedAtMs, atMs, lastAtMs ?: submittedAtMs)
        failure(observed)?.let { breached = it }
        if (firstAtMs == null) firstAtMs = observed
        lastAtMs = observed
    }

    @Synchronized fun failure(atMs: Long): ExecutionTimeoutException? {
        breached?.let { return it }
        val elapsed = (atMs - submittedAtMs).coerceAtLeast(0)
        val failure = when {
            elapsed >= policy.requestMs -> ExecutionTimeoutException(ExecutionTimeoutKind.REQUEST,
                "Inference reached its ${policy.requestMs / 1_000}s finite request limit.")
            firstAtMs == null && elapsed >= policy.firstOutputMs -> ExecutionTimeoutException(ExecutionTimeoutKind.FIRST_OUTPUT,
                "No inference text arrived within ${policy.firstOutputMs / 1_000}s of submission; queue, load and prompt processing are not separately observable.")
            lastAtMs != null && atMs - lastAtMs!! >= policy.inactivityMs -> ExecutionTimeoutException(ExecutionTimeoutKind.INACTIVITY,
                "Inference text was inactive for ${policy.inactivityMs / 1_000}s; keepalive frames do not count as progress.")
            else -> null
        }
        if (failure != null) breached = failure
        return failure
    }
}

/** Reserve before dispatch: process death conservatively consumes the reserved segment, never resets it. */
data class ExecutionAllowanceReservation(val consumedBeforeMs: Long, val segmentMs: Long) {
    val reservedConsumedMs: Long get() = consumedBeforeMs + segmentMs
    fun settledConsumedMs(observedElapsedMs: Long): Long = consumedBeforeMs + observedElapsedMs.coerceIn(0, segmentMs)
}

object ExecutionAllowancePolicy {
    fun reserve(policy: ExecutionTimeoutPolicy, consumedMs: Long): ExecutionAllowanceReservation? {
        require(consumedMs >= 0) { "Invalid persisted allowance." }
        val remaining = (policy.overallAllowanceMs - consumedMs).coerceAtLeast(0)
        return if (remaining == 0L) null else ExecutionAllowanceReservation(consumedMs, minOf(remaining, policy.serviceSegmentMs))
    }
}

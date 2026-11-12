package com.nyx.ai.autonomy

enum class RecoveryAction {
    /** Retry automatically after a bounded delay. */
    RETRY,
    /** Stop and require a human decision because side effects are uncertain. */
    NEEDS_REVIEW,
    /** Stop permanently: the retry budget or progress check failed. */
    FAIL_PERMANENTLY,
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val delayMillis: Long,
    val reason: String,
)

/**
 * Conservative recovery policy. NYX never repeats a non-idempotent operation automatically, never
 * retries the same failure signature more than once, and never exceeds the task's attempt budget.
 */
object RecoveryPolicy {
    const val MAX_REPEATED_SIGNATURES = 1
    private const val BASE_DELAY_MILLIS = 1_500L
    private const val MAX_DELAY_MILLIS = 30_000L

    fun decide(
        attemptCount: Int,
        maxAttempts: Int,
        idempotent: Boolean,
        failureSignature: String?,
        previousFailureSignatures: List<String>,
        risk: String = "mutating",
    ): RecoveryDecision {
        if (attemptCount >= maxAttempts) {
            return RecoveryDecision(
                if (idempotent) RecoveryAction.FAIL_PERMANENTLY else RecoveryAction.NEEDS_REVIEW,
                delayMillis = 0,
                reason = "Retry budget exhausted after $attemptCount attempt(s).",
            )
        }
        val signature = failureSignature
        if (signature != null && previousFailureSignatures.count { it == signature } >= MAX_REPEATED_SIGNATURES) {
            return RecoveryDecision(
                RecoveryAction.FAIL_PERMANENTLY,
                0,
                "The same failure repeated without progress; automatic retry would loop.",
            )
        }
        if (!idempotent || risk.equals("destructive", ignoreCase = true) || risk.equals("external", ignoreCase = true)) {
            return RecoveryDecision(
                RecoveryAction.NEEDS_REVIEW,
                0,
                "The operation may already have changed external state; review before retrying.",
            )
        }
        val delay = (BASE_DELAY_MILLIS shl (attemptCount.coerceIn(0, 5) - 1).coerceAtLeast(0)).coerceAtMost(MAX_DELAY_MILLIS)
        return RecoveryDecision(RecoveryAction.RETRY, delay, "Bounded retry ${attemptCount + 1}/$maxAttempts after a recoverable failure.")
    }
}

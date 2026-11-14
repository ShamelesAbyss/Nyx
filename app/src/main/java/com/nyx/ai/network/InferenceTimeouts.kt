package com.nyx.ai.network

import com.nyx.ai.autonomy.ExecutionTimeoutException
import com.nyx.ai.autonomy.ExecutionTimeoutKind
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.InferenceTimeoutTracker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Actual reasoning is activity, NOT answer text or proof of accepted thinking controls. */
internal class InferenceActivityTracker(private val submittedAtMs: Long, private val policy: ExecutionTimeoutPolicy) {
    private var firstActivityMs: Long? = null
    private var lastActivityMs: Long? = null
    private var breached: ExecutionTimeoutException? = null

    @Synchronized fun activity(atMs: Long, delta: String) {
        if (delta.isEmpty()) return
        val at = maxOf(submittedAtMs, atMs, lastActivityMs ?: submittedAtMs)
        failure(at)?.let { breached = it }
        if (firstActivityMs == null) firstActivityMs = at
        lastActivityMs = at
    }
    @Synchronized fun failure(atMs: Long): ExecutionTimeoutException? {
        breached?.let { return it }
        val elapsed = (atMs - submittedAtMs).coerceAtLeast(0)
        val failure = when {
            elapsed >= policy.requestMs -> ExecutionTimeoutException(ExecutionTimeoutKind.REQUEST,
                "Inference reached its ${policy.requestMs / 1_000}s finite request limit.")
            firstActivityMs == null && elapsed >= policy.firstOutputMs -> ExecutionTimeoutException(ExecutionTimeoutKind.FIRST_OUTPUT,
                "No inference text or reasoning activity arrived within ${policy.firstOutputMs / 1_000}s; queue/load/prompt processing remain unseparated.")
            lastActivityMs != null && atMs - lastActivityMs!! >= policy.inactivityMs -> ExecutionTimeoutException(ExecutionTimeoutKind.INACTIVITY,
                "Inference text/reasoning was inactive for ${policy.inactivityMs / 1_000}s; keepalive frames are not activity.")
            else -> null
        }
        if (failure != null) breached = failure
        return failure
    }
}

/** Private marker prevents a nested legacy client watchdog contradicting the enclosing deadline. */
private class DeadlineReasoningObserver(
    val policy: ExecutionTimeoutPolicy,
    private val tracker: InferenceActivityTracker,
    private val clock: () -> Long,
    private val observer: ((String) -> Unit)?,
) : (String) -> Unit {
    @Volatile var active = true
    override fun invoke(delta: String) {
        if (active) {
            tracker.activity(clock(), delta)
            tracker.failure(clock())?.let { throw it }
        }
        observer?.invoke(delta)
    }
}

/**
 * Effective-options overload. The request MUST use outgoingOptions to receive reasoning activity.
 * The hard deadline includes queue wait; neither reasoning nor keepalive extends requestMs.
 * A coroutine deadline is not proof that a blocking native kernel or external child was stopped.
 */
internal suspend fun <T : Any> withInferenceActivityTimeouts(
    options: InferenceOptions,
    onToken: (String) -> Unit,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    request: suspend (InferenceOptions, (String) -> Unit) -> T,
): T {
    val policy = options.executionTimeouts ?: return request(options, onToken)
    val tracker = InferenceActivityTracker(clock(), policy)
    val reasoning = DeadlineReasoningObserver(policy, tracker, clock, options.thinkingObserver)
    try {
        return withTimeoutOrNull(policy.requestMs) {
            coroutineScope {
                val pending = async {
                    request(options.copy(thinkingObserver = reasoning)) { text ->
                        tracker.activity(clock(), text)
                        tracker.failure(clock())?.let { throw it }
                        onToken(text) // Reasoning NEVER flows through this callback.
                    }
                }
                try {
                    while (!pending.isCompleted) {
                        tracker.failure(clock())?.let { throw it }
                        delay(minOf(250L, policy.firstOutputMs, policy.inactivityMs, policy.requestMs))
                    }
                    val result = pending.await()
                    tracker.failure(clock())?.let { throw it }
                    currentCoroutineContext().ensureActive()
                    result
                } finally { pending.cancel() }
            }
        } ?: throw ExecutionTimeoutException(ExecutionTimeoutKind.REQUEST,
            "Inference reached its ${policy.requestMs / 1_000}s finite request limit.")
    } finally { reasoning.active = false }
}

/**
 * Legacy content-callback contract retained for direct clients. When Fabric has already supplied an
 * active reasoning-aware deadline for these SAME options, do not create a conflicting text-only
 * watchdog. Direct callers not using Fabric must migrate to withInferenceActivityTimeouts and use
 * its outgoingOptions; otherwise reasoning cannot be observed by this legacy callback signature.
 */
internal suspend fun <T : Any> withExecutionTimeouts(
    options: InferenceOptions,
    onToken: (String) -> Unit,
    request: suspend ((String) -> Unit) -> T,
): T {
    val policy = options.executionTimeouts ?: return request(onToken)
    val enclosing = options.thinkingObserver as? DeadlineReasoningObserver
    if (enclosing?.active == true && enclosing.policy == policy) return request(onToken)
    val clock = { System.nanoTime() / 1_000_000 }
    val tracker = InferenceTimeoutTracker(clock(), policy)
    return withTimeoutOrNull(policy.requestMs) {
        coroutineScope {
            val pending = async {
                request { text ->
                    tracker.text(clock(), text)
                    tracker.failure(clock())?.let { throw it }
                    onToken(text)
                }
            }
            try {
                while (!pending.isCompleted) {
                    tracker.failure(clock())?.let { throw it }
                    delay(minOf(250L, policy.firstOutputMs, policy.inactivityMs, policy.requestMs))
                }
                val result = pending.await()
                tracker.failure(clock())?.let { throw it }
                currentCoroutineContext().ensureActive()
                result
            } finally { pending.cancel() }
        }
    } ?: throw ExecutionTimeoutException(ExecutionTimeoutKind.REQUEST,
        "Inference reached its ${policy.requestMs / 1_000}s finite request limit.")
}

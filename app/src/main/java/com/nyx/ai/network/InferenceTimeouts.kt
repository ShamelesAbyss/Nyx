package com.nyx.ai.network

import com.nyx.ai.autonomy.ExecutionTimeoutException
import com.nyx.ai.autonomy.ExecutionTimeoutKind
import com.nyx.ai.autonomy.InferenceTimeoutTracker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opt-in only: ordinary chat retains provider defaults. Native work must cooperate with cancellation;
 * a Kotlin deadline is not a claim that a blocking native kernel can be forcibly interrupted.
 */
internal suspend fun <T : Any> withExecutionTimeouts(
    options: InferenceOptions,
    onToken: (String) -> Unit,
    request: suspend ((String) -> Unit) -> T,
): T {
    val policy = options.executionTimeouts ?: return request(onToken)
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
            } finally {
                pending.cancel()
            }
        }
    } ?: throw ExecutionTimeoutException(ExecutionTimeoutKind.REQUEST,
        "Inference reached its ${policy.requestMs / 1_000}s finite request limit.")
}

package com.nyx.ai.autonomy

/** Limits use observed wall time and streamed characters, never estimated tokenizer counts. */
data class PlanningLimits(
    val queueWaitMs: Long = 90_000,
    val firstTokenMs: Long = 120_000,
    val stallMs: Long = 60_000,
    val activeGenerationMs: Long = 240_000,
    val hardLimitMs: Long = 300_000,
) {
    init {
        require(listOf(queueWaitMs, firstTokenMs, stallMs, activeGenerationMs, hardLimitMs).all { it > 0 })
    }
}

enum class PlanningStage { WAITING_FOR_PROVIDER, QUEUED, WAITING_FOR_FIRST_TOKEN, GENERATING, STALLED }

data class PlanningProgress(
    val stage: PlanningStage,
    val elapsedMs: Long,
    val characters: Long,
    val firstTokenAfterMs: Long?,
    val idleMs: Long?,
    val failure: PlanningFailure? = null,
) {
    fun detail(sharedQueued: Int = 0, sharedActive: Int = 0): String = buildString {
        when (stage) {
            PlanningStage.QUEUED -> append("Planning is queued; waiting for request admission.")
            PlanningStage.WAITING_FOR_PROVIDER -> append("Waiting for the planning provider; request admission, model load and prompt processing are not separately observable.")
            PlanningStage.WAITING_FOR_FIRST_TOKEN -> append("Planning request admitted; waiting for its first text chunk. Model load and prompt processing are not separately observable.")
            PlanningStage.GENERATING -> append("Planning text is streaming: $characters characters observed; last chunk ${idleMs ?: 0}ms ago.")
            PlanningStage.STALLED -> append("Planning stream stalled: $characters characters observed; no new text for ${idleMs ?: 0}ms.")
        }
        append(" Elapsed ${elapsedMs / 1_000}s; no task has run.")
        if (stage == PlanningStage.WAITING_FOR_PROVIDER && (sharedQueued > 0 || sharedActive > 0)) {
            append(" Shared inference fabric: $sharedQueued queued, $sharedActive active; these counts do not identify this request.")
        }
    }
}

/**
 * Request-local lifecycle. Calls are synchronized because token callbacks may run on HTTP threads.
 * Admission is recorded ONLY when the provider supplies an explicit request-scoped observation.
 * Current providers expose chunks, not admission: the engine deliberately leaves admission unknown.
 */
class PlanningLifecyclePolicy(
    private val submittedAtMs: Long,
    private val limits: PlanningLimits = PlanningLimits(),
) {
    private var queueObserved = false
    private var admittedAtMs: Long? = null
    private var firstTokenAtMs: Long? = null
    private var lastTokenAtMs: Long? = null
    private var characters = 0L
    private var breached: PlanningFailure? = null

    @Synchronized fun queued() { if (firstTokenAtMs == null && admittedAtMs == null) queueObserved = true }
    @Synchronized fun admitted(atMs: Long) {
        if (admittedAtMs == null) {
            progress(atMs).failure?.let { breached = it }
            admittedAtMs = maxOf(submittedAtMs, atMs)
        }
    }
    @Synchronized fun token(atMs: Long, chunk: String) {
        if (chunk.isEmpty()) return
        val observedAt = maxOf(submittedAtMs, atMs, lastTokenAtMs ?: submittedAtMs)
        progress(observedAt).failure?.let { breached = it }
        if (firstTokenAtMs == null) firstTokenAtMs = observedAt
        lastTokenAtMs = observedAt
        characters += chunk.length
    }

    @Synchronized fun progress(atMs: Long): PlanningProgress {
        val elapsed = (atMs - submittedAtMs).coerceAtLeast(0)
        val first = firstTokenAtMs
        val idle = lastTokenAtMs?.let { (atMs - it).coerceAtLeast(0) }
        var stage = when {
            first != null && idle != null && idle >= limits.stallMs -> PlanningStage.STALLED
            first != null -> PlanningStage.GENERATING
            admittedAtMs != null -> PlanningStage.WAITING_FOR_FIRST_TOKEN
            queueObserved -> PlanningStage.QUEUED
            else -> PlanningStage.WAITING_FOR_PROVIDER
        }
        val reason = when {
            elapsed >= limits.hardLimitMs -> "Planning reached the ${limits.hardLimitMs / 1_000}s hard request limit ($characters streamed characters)."
            first != null && idle != null && idle >= limits.stallMs -> "Planning stalled after $characters streamed characters; no text arrived for ${idle / 1_000}s."
            first != null && atMs - first >= limits.activeGenerationMs -> "Planning reached the ${limits.activeGenerationMs / 1_000}s active generation limit ($characters streamed characters)."
            first == null && admittedAtMs != null && atMs - admittedAtMs!! >= limits.firstTokenMs -> "No first planning text chunk arrived within ${limits.firstTokenMs / 1_000}s of observed admission."
            first == null && admittedAtMs == null && queueObserved && elapsed >= limits.queueWaitMs -> "Planning exceeded the ${limits.queueWaitMs / 1_000}s observed queue wait limit."
            first == null && admittedAtMs == null && !queueObserved && elapsed >= limits.firstTokenMs -> "No planning text arrived within ${limits.firstTokenMs / 1_000}s of submission; queue, load and prompt-processing times are unknown."
            else -> null
        }
        if (first != null && idle != null && idle >= limits.stallMs) stage = PlanningStage.STALLED
        if (breached == null && reason != null) breached = PlanningFailure(PlanningFailureKind.TIMEOUT, reason)
        return PlanningProgress(stage, elapsed, characters, first?.let { it - submittedAtMs }, idle,
            breached)
    }
}

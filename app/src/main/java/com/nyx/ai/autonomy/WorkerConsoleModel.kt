package com.nyx.ai.autonomy

/** A distinct execution run, keyed by objective plus the engine-generated run id. */
data class ConsoleRun(
    val objectiveId: String?,
    val runId: String?,
    val startedAt: Long,
    val endedAt: Long,
    val events: List<WorkerActivity>,
)

/** Related observable events, normally one objective task or one planning attempt. */
data class ConsoleOperation(
    val key: String,
    val title: String,
    val taskId: String?,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val events: List<WorkerActivity>,
)

/**
 * Pure grouping and filtering rules for the Live Worker Console. It never invents a duration or
 * status: an operation is completed only when a terminal event is present, and elapsed time is
 * calculated from real event timestamps (or the wall clock for a still-running operation).
 */
object WorkerConsoleModel {
    fun runs(activities: List<WorkerActivity>): List<ConsoleRun> =
        activities.groupBy { it.objectiveId to it.runId }
            .map { (key, events) ->
                val ordered = events.sortedBy { it.at }
                ConsoleRun(
                    objectiveId = key.first,
                    runId = key.second,
                    startedAt = ordered.first().at,
                    endedAt = ordered.last().at,
                    events = ordered,
                )
            }
            .sortedWith(compareByDescending<ConsoleRun> { it.startedAt }.thenByDescending { it.endedAt })

    fun runsForObjective(activities: List<WorkerActivity>, objectiveId: String?): List<ConsoleRun> {
        if (objectiveId == null) return emptyList()
        return runs(activities.filter { it.objectiveId == objectiveId })
    }

    fun currentRun(activities: List<WorkerActivity>, objectiveId: String?): ConsoleRun? =
        // The newest run is the observable present. A stale "running" event in an older run must
        // not pull the console back into history.
        runsForObjective(activities, objectiveId).firstOrNull()

    fun forRun(activities: List<WorkerActivity>, objectiveId: String?, runId: String?): List<WorkerActivity> =
        activities.filter { it.objectiveId == objectiveId && it.runId == runId }
            .sortedBy { it.at }

    fun operations(events: List<WorkerActivity>): List<ConsoleOperation> {
        if (events.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<WorkerActivity>>()
        for (event in events.sortedBy { it.at }) {
            val key = event.taskId?.let { "task:$it" }
                ?: if (event.kind == WorkerActivityKind.PLAN ||
                    event.kind == WorkerActivityKind.INFO && event.title.contains("plan", ignoreCase = true)
                ) "planning" else "event:${event.id}"
            grouped.getOrPut(key) { mutableListOf() }.add(event)
        }
        return grouped.map { (key, items) ->
            val first = items.first()
            val last = items.last()
            val status = operationStatus(items)
            ConsoleOperation(
                key = key,
                title = operationTitle(items),
                taskId = first.taskId,
                status = status,
                startedAt = first.at,
                endedAt = if (status in TERMINAL_STATES) last.at else null,
                events = items,
            )
        }.sortedByDescending { it.startedAt }
    }

    fun operationStatus(events: List<WorkerActivity>): String {
        val observed = events.mapNotNull { it.status?.lowercase() }
        if (observed.isNotEmpty()) return observed.last()
        val text = events.joinToString(" ") { "${it.title} ${it.detail}" }.lowercase()
        return when {
            "planning_failed" in text || "task_error" in text || "verification_failed" in text ||
                "needs_attention" in text || "failed" in text -> "failed"
            "cancelled" in text || "canceled" in text -> "cancelled"
            "paused" in text -> "paused"
            "interrupted" in text || "needs_review" in text || "recovery_needs_review" in text -> "interrupted"
            "blocked" in text || "waiting" in text || "recovery_retry" in text -> "waiting"
            "task_completed" in text || "verification_passed" in text || "plan_created" in text ||
                "completed" in text -> "completed"
            "task_started" in text || "running" in text || "verifying" in text -> "running"
            else -> "observed"
        }
    }

    fun elapsedMillis(operation: ConsoleOperation, now: Long = System.currentTimeMillis()): Long? {
        val end = operation.endedAt ?: when (operation.status) {
            "running", "waiting", "paused", "interrupted" -> now
            else -> null
        } ?: return null
        return (end - operation.startedAt).coerceAtLeast(0)
    }

    fun formatDuration(millis: Long?): String {
        if (millis == null) return "—"
        val seconds = millis / 1_000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
        }
    }

    private fun operationTitle(events: List<WorkerActivity>): String {
        val started = events.firstOrNull { it.title.contains("task_started", ignoreCase = true) }
        val fromDetail = started?.detail?.substringBefore(" · ")?.trim().orEmpty()
        if (fromDetail.isNotBlank()) return fromDetail.take(160)
        val first = events.firstOrNull { it.title.isNotBlank() } ?: events.first()
        return first.title.replace('_', ' ').trim().ifBlank { "Observed operation" }.take(160)
    }

    private val TERMINAL_STATES = setOf("completed", "failed", "cancelled", "interrupted")
}

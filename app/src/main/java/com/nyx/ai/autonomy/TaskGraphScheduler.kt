package com.nyx.ai.autonomy

/** Small, deterministic scheduler policy kept independent from Android and Room for testing. */
data class TaskSnapshot(
    val id: String,
    val ordinal: Int,
    val dependsOn: Set<String>,
    val status: String
)

object TaskGraphScheduler {
    private val terminalFailures = setOf("failed", "blocked", "needs_review", "cancelled")

    /** Legacy coordinator remains serial; terminal admission is independently bounded. */
    fun nextReady(tasks: List<TaskSnapshot>): TaskSnapshot? = ready(tasks, 1).firstOrNull()

    /** Only already-completed dependencies count. Selected tasks do not unblock their children. */
    fun ready(tasks: List<TaskSnapshot>, capacity: Int): List<TaskSnapshot> {
        val byId = tasks.associateBy { it.id }
        return tasks.asSequence().filter { task ->
            task.status == "pending" && task.dependsOn.all { dep -> byId[dep]?.status == "completed" }
        }.sortedWith(compareBy<TaskSnapshot> { it.ordinal }.thenBy { it.id })
            .take(capacity.coerceAtLeast(0)).toList()
    }

    /** Identifies tasks that can never become ready without repairing/replanning a prerequisite. */
    fun blocked(tasks: List<TaskSnapshot>): List<TaskSnapshot> {
        val byId = tasks.associateBy { it.id }
        return tasks.filter { task ->
            task.status == "pending" && task.dependsOn.any { dep ->
                val prerequisite = byId[dep]
                prerequisite == null || prerequisite.status in terminalFailures
            }
        }.sortedBy { it.ordinal }
    }

    fun hasVerifiedLocalOutcome(output: String, observations: List<String>): Boolean =
        output.isNotBlank() && observations.none { item ->
            item.contains("Tool error:", ignoreCase = true) || item.contains("Tool denied:", ignoreCase = true)
        }
}

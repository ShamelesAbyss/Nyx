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

    /** Returns only one task, intentionally serializing model/tool work on resource-limited phones. */
    fun nextReady(tasks: List<TaskSnapshot>): TaskSnapshot? {
        val byId = tasks.associateBy { it.id }
        return tasks.asSequence().filter { task ->
            task.status == "pending" && task.dependsOn.all { dep -> byId[dep]?.status == "completed" }
        }.sortedBy { it.ordinal }.firstOrNull()
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

package com.nyx.ai.terminal

/** The executable parts of a persisted objective task, used to select only relevant toolchains. */
data class PlannedDependencyTask(
    val workerKind: String,
    val title: String,
    val instructions: String,
    val verification: String,
)

/** Selects dependencies only from tasks that actually invoke the authorized environment. */
object ObjectiveDependencyScope {
    private val ENVIRONMENT_WORKERS = setOf("development", "terminal")

    /** Never join unrelated tasks: a device mention in one task cannot change another's target. */
    fun requiredProjectKinds(tasks: List<PlannedDependencyTask>): List<ProjectKind> = tasks
        .filter { it.workerKind.trim().lowercase() in ENVIRONMENT_WORKERS }
        .map { task ->
            val instructions = DependencyPlanner.inferProjectKind(task.instructions)
            val explicitShell = Regex("(?i)\\b(?:posix\\s+shell|shell\\s+(?:script|only)|bash\\s+script)\\b").containsMatchIn(task.instructions)
            val target = DependencyPlanner.inferProjectKind(listOf(task.title, task.instructions).joinToString("\n"))
            when {
                explicitShell && instructions == ProjectKind.GENERIC -> ProjectKind.GENERIC
                target != ProjectKind.GENERIC -> target
                else -> DependencyPlanner.inferProjectKind(task.verification)
            }
        }
        .filter { it != ProjectKind.GENERIC }
        .distinct()

    /** Compatibility accessor for callers displaying a single task's primary toolchain. */
    fun requiredProjectKind(tasks: List<PlannedDependencyTask>): ProjectKind? = requiredProjectKinds(tasks).firstOrNull()

    /** Preparation retains the existing approval/install gate and stops at the first blocked plan. */
    suspend fun beforeExecution(
        tasks: List<PlannedDependencyTask>,
        prepare: suspend (ProjectKind) -> Boolean,
    ): Boolean {
        for (kind in requiredProjectKinds(tasks)) if (!prepare(kind)) return false
        return true
    }
}

package com.nyx.ai.terminal

/** The executable parts of a persisted objective task, used to select only relevant toolchains. */
data class PlannedDependencyTask(
    val workerKind: String,
    val title: String,
    val instructions: String,
    val verification: String,
)

/**
 * Selects a toolchain from the tasks that will actually invoke the authorized environment.
 * Objective prose alone is not sufficient: planning, filesystem, and verification-only tasks do
 * not trigger development package checks. Task text is used so explicit negations are respected by
 * [DependencyPlanner.inferProjectKind].
 */
object ObjectiveDependencyScope {
    private val ENVIRONMENT_WORKERS = setOf("development", "terminal")

    fun requiredProjectKind(tasks: List<PlannedDependencyTask>): ProjectKind? {
        val executableTasks = tasks.filter { it.workerKind.trim().lowercase() in ENVIRONMENT_WORKERS }
        if (executableTasks.isEmpty()) return null
        val taskContext = executableTasks.joinToString("\n") {
            listOf(it.title, it.instructions, it.verification).joinToString("\n")
        }
        return DependencyPlanner.inferProjectKind(taskContext)
            .takeUnless { it == ProjectKind.GENERIC }
    }

    /**
     * Runs dependency preparation only when the persisted tasks actually require a toolchain.
     * The execution engine calls this gate after first planning and again before resuming tasks.
     */
    suspend fun beforeExecution(
        tasks: List<PlannedDependencyTask>,
        prepare: suspend (ProjectKind) -> Boolean,
    ): Boolean {
        val kind = requiredProjectKind(tasks) ?: return true
        return prepare(kind)
    }
}

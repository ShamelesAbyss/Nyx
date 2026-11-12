package com.nyx.ai.autonomy

/** The supervisor's next move after observing a task result. */
enum class SupervisorAction {
    /** Ready work remains; keep executing the current plan. */
    CONTINUE,

    /** The plan needs a bounded revision, such as a diagnostic task or revised instructions. */
    REPLAN,

    /** Stop safely and wait for the user or a later session. */
    PAUSE,

    /** The objective cannot proceed with the available environment or authorization. */
    FAIL,
}

/** Remaining execution budget for one objective session. */
data class SupervisorBudget(
    val remainingInferenceCalls: Int,
    val remainingToolCalls: Int,
    val remainingMillis: Long,
)

/** Observed state of the task graph, not a model's description of it. */
data class SupervisorInput(
    val totalTasks: Int,
    val completedTasks: Int,
    val pendingTasks: Int,
    val runningTasks: Int = 0,
    val failedTasks: Int = 0,
    val attempt: Int = 0,
    val maxAttempts: Int = 2,
    val consecutiveSameFailure: Int = 0,
    val budget: SupervisorBudget = SupervisorBudget(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE),
)

data class SupervisorDecision(val action: SupervisorAction, val reason: String, val addDiagnosticTask: Boolean = false)

/**
 * Deterministic supervision policy. It never retries an identical failure indefinitely, never spends
 * budget that does not exist, and distinguishes a recoverable obstacle from a graph that is finished.
 */
object SupervisorPolicy {
    fun decide(input: SupervisorInput): SupervisorDecision {
        if (input.budget.remainingInferenceCalls <= 0) {
            return SupervisorDecision(SupervisorAction.PAUSE, "Inference budget exhausted; checkpoint and wait.")
        }
        if (input.budget.remainingToolCalls <= 0) {
            return SupervisorDecision(SupervisorAction.PAUSE, "Tool-call budget exhausted; checkpoint and wait.")
        }
        if (input.budget.remainingMillis <= 0) {
            return SupervisorDecision(SupervisorAction.PAUSE, "Time budget exhausted; checkpoint and wait.")
        }
        if (input.totalTasks == 0) {
            return SupervisorDecision(SupervisorAction.FAIL, "The plan contains no executable tasks.")
        }
        if (input.consecutiveSameFailure >= 2) {
            return SupervisorDecision(SupervisorAction.PAUSE, "The same failure repeated twice; automatic retries stopped.")
        }
        if (input.failedTasks > 0) {
            return when {
                input.attempt < input.maxAttempts -> SupervisorDecision(
                    SupervisorAction.REPLAN,
                    "Attempt ${input.attempt}/${input.maxAttempts} failed; revise the approach before retrying.",
                    addDiagnosticTask = input.attempt == 1,
                )

                else -> SupervisorDecision(SupervisorAction.PAUSE, "Retry limit reached; the failure needs review.")
            }
        }
        if (input.pendingTasks > 0 || input.runningTasks > 0) {
            return SupervisorDecision(SupervisorAction.CONTINUE, "Dependency-ready work remains.")
        }
        if (input.completedTasks >= input.totalTasks) {
            return SupervisorDecision(SupervisorAction.CONTINUE, "All tasks completed; the objective can close.")
        }
        return SupervisorDecision(SupervisorAction.PAUSE, "No runnable task remains; the graph is blocked.")
    }
}

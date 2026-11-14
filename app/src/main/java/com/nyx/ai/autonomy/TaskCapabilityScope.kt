package com.nyx.ai.autonomy

import com.nyx.ai.agent.AgentTools
import com.nyx.ai.workbench.WorkbenchAgentTools

/**
 * Deterministic, least-authority tool scope for one planned task. The model never chooses its own
 * scope: the task kind and the tools actually available on this run decide it.
 */
data class TaskToolScope(
    val allowed: Set<String>,
    val readOnly: Boolean,
    val reason: String,
)

object TaskCapabilityScope {
    private val builtIns = AgentTools.DEFAULT_TOOLS
    private val readOnlyFiles = setOf(
        "file_read", "file_list", "file_search", "source_search", "project_detect", "project_inspect",
        "repo_status", "repo_diff",
    )
    private val mutatingFiles = setOf("file_write")
    private val commands = setOf("terminal_run")
    private val projectRuns = setOf("project_run", "build_run", "test_run")

    fun forTask(
        workerKind: String,
        configured: Set<String>,
        available: Set<String>,
        terminalAvailable: Boolean = true,
    ): TaskToolScope {
        val kind = workerKind.trim().lowercase()
        val actual = (available + builtIns).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val base = configured.map { it.trim() }.filter { it.isNotEmpty() }.toSet() intersect actual
        val terminalCapabilities = if (terminalAvailable) actual else emptySet()
        val fileCapabilities = base intersect terminalCapabilities
        val allowedBase = when (kind) {
            "inference" -> (base - mutatingFiles - commands - projectRuns) +
                (fileCapabilities intersect readOnlyFiles)
            "filesystem" -> (base - commands - projectRuns) + (fileCapabilities intersect (readOnlyFiles + mutatingFiles))
            "verification" -> (base - mutatingFiles - commands - projectRuns) +
                (fileCapabilities intersect (readOnlyFiles + setOf("test_run")))
            "terminal" -> base + (fileCapabilities intersect (readOnlyFiles + mutatingFiles + commands))
            "development" -> base + (fileCapabilities intersect (readOnlyFiles + mutatingFiles + commands + projectRuns))
            else -> base - mutatingFiles - commands - projectRuns
        }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val allowed = if (kind in setOf("filesystem", "terminal", "development")) allowedBase
            else allowedBase - WorkbenchAgentTools.MUTATING
        val readOnly = allowed.none { it in mutatingFiles || it in WorkbenchAgentTools.MUTATING || it in commands || it in projectRuns }
        val reason = when (kind) {
            "inference" -> "Inference tasks may read and use enabled non-mutating tools; command and project execution are excluded."
            "filesystem" -> "Filesystem tasks may inspect and edit files, but may not run shell commands or project builds."
            "verification" -> "Verification tasks may inspect files and run the fixed test command, but may not write files or run arbitrary shell commands."
            "terminal" -> "Terminal tasks may use file tools and policy-gated shell commands inside the authorized workspace."
            "development" -> "Development tasks may use the full approved development capability set."
            else -> "Unknown task kinds receive no mutating terminal capability."
        }
        return TaskToolScope(allowed, readOnly, reason)
    }
}

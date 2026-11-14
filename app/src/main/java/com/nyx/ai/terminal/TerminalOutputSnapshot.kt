package com.nyx.ai.terminal

import com.nyx.ai.development.DevelopmentPaths
import com.nyx.ai.development.ProjectRunKind

/** Caller/user-owned exact selection. Not a model tool argument or implicit workspace grant. */
data class TerminalOutputSelection(val id: String, val relativePath: String) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))); require(TerminalOutputPaths.isSelectedOutput(relativePath)) }
}

class TerminalOutputApproval(val objectiveId: String, val environment: TerminalEnvironment, val guestAlias: String,
                             val workspaceRoot: String, selections: List<TerminalOutputSelection>, val maxBytes: Int = 128_000) {
    val selections: List<TerminalOutputSelection> = selections.toList()
    init {
        require(objectiveId.isNotBlank() && objectiveId.length <= 128 && '\u0000' !in objectiveId)
        require(DevelopmentPaths.isAbsoluteNormalizedWorkspace(workspaceRoot) && HostShell.isSafeAlias(guestAlias))
        require(maxBytes in 1..128_000 && selections.size in 1..8)
        require(selections.map { it.id }.distinct().size == selections.size && selections.map { it.relativePath }.distinct().size == selections.size)
    }
}

object TerminalOutputPaths {
    fun isSelectedOutput(path: String): Boolean {
        val parts = path.split('/')
        if (parts.size < 2 || parts.first() !in setOf("build", "dist", "target") || path.length > 512) return false
        val remaining = parts.drop(1).joinToString("/")
        return WorkspaceSourcePaths.isSafeRelative(remaining) && parts.all { it.matches(Regex("[A-Za-z0-9._+@-]+")) && !it.startsWith('.') } &&
            parts.last().substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "csv", "tsv", "html")
    }
}

/** Constructor is terminal-internal; consumer receives copies and must validate format in Workbench. */
class TerminalOutputSnapshot internal constructor(val objectiveId: String, val taskId: String, val environment: TerminalEnvironment,
    val guestAlias: String, val workspaceRoot: String, val selectionId: String, val relativePath: String,
    val operationId: String, val sha256: String, bytes: ByteArray) {
    private val retained = bytes.copyOf()
    val sizeBytes: Int get() = retained.size
    fun copyBytes(): ByteArray = retained.copyOf()
}

/** Only this typed evidence, not generic tool exit codes, can prove a fixed project process ran. */
data class FixedProjectExecution(val objectiveId: String?, val taskId: String?, val environment: TerminalEnvironment,
    val guestAlias: String, val workspaceRoot: String, val kind: ProjectRunKind, val operationId: String,
    val command: String, val resultKind: TerminalResultKind, val exitCode: Int?, val startedAt: Long, val finishedAt: Long) {
    val completedSuccessfully: Boolean get() = resultKind == TerminalResultKind.SUCCEEDED && exitCode == 0
    val infrastructureOk: Boolean get() = resultKind in setOf(TerminalResultKind.SUCCEEDED, TerminalResultKind.PROCESS_EXIT) && exitCode != null && exitCode !in setOf(124, 137)
}

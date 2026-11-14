package com.nyx.ai.terminal

import java.security.MessageDigest

/** Validates ownership and command witness before any journal admission or external dispatch. */
internal object TerminalDispatchBinding {
    fun bind(request: TerminalCommandRequest, operation: TerminalOperationContext,
             scope: TerminalExecutionScope?, identity: TerminalCommandIdentity?): TerminalOperationContext {
        require(operation.objectiveId == null || scope == null || operation.objectiveId == scope.objectiveId) { "Foreign objective operation context" }
        require(operation.taskId == null || scope == null || operation.taskId == scope.taskId) { "Foreign task operation context" }
        if (identity != null) {
            require(request.commandPath == ControlledShell.executable(request.environment, "env")) { "Audit identity requires controlled dispatch" }
            require(request.arguments.firstOrNull() == "-i" && request.arguments.takeLast(4).take(3) == listOf("--noprofile", "--norc", "-c")) { "Audit identity requires sanitized non-login shell" }
            require(identity.workingDirectory == operation.workspace) { "Audit working-directory mismatch" }
            require(request.arguments.lastOrNull()?.let(TerminalCommandIdentity::digest) == identity.scriptSha256) { "Audit script digest mismatch" }
        }
        return operation.copy(objectiveId = operation.objectiveId ?: scope?.objectiveId,
            taskId = operation.taskId ?: scope?.taskId, commandPath = request.commandPath,
            argumentDigest = MessageDigest.getInstance("SHA-256").digest(
                (listOf(request.commandPath) + request.arguments + listOf(request.stdin.orEmpty())).joinToString("\u0000").toByteArray(Charsets.UTF_8)
            ).joinToString("") { "%02x".format(it.toInt() and 255) }, commandIdentity = identity)
    }
}

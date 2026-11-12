package com.nyx.ai.terminal

import android.content.Context
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.data.TerminalCommandEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.util.UUID

/**
 * Single audited path for terminal execution. Every attempt is written to Room before dispatch and
 * updated with the real result afterwards, so history survives process death.
 */
class TerminalRepository(
    context: Context,
    private val database: NyxDatabase,
    private val bridge: TerminalBridge = TerminalBridge(context),
) {
    fun readiness(): TermuxReadiness = bridge.readiness()

    fun observeHistory(limit: Int = 50): Flow<List<TerminalCommandEntity>> =
        database.terminalCommandDao().observeRecent(limit)

    fun observeForObjective(objectiveId: String): Flow<List<TerminalCommandEntity>> =
        database.terminalCommandDao().observeForObjective(objectiveId)

    suspend fun discover(prootPathHint: String? = null): EnvironmentProfile =
        EnvironmentDiscovery(bridge).discover(prootPathHint)

    suspend fun execute(
        command: String,
        environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
        workingDirectory: String? = null,
        timeoutSeconds: Long = 120,
        approvedByUser: Boolean = false,
        objectiveId: String? = null,
        taskId: String? = null,
        useTimeoutBinary: Boolean = true,
    ): TerminalCommandResult {
        val assessment = CommandPolicy.assess(command)
        val id = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val row = TerminalCommandEntity(
            id = id,
            objectiveId = objectiveId,
            taskId = taskId,
            environment = environment.name,
            commandPath = command.take(CommandPolicy.MAX_COMMAND_CHARS),
            argumentsJson = JSONArray().put(command.take(CommandPolicy.MAX_COMMAND_CHARS)).toString(),
            workingDirectory = workingDirectory,
            status = "running",
            exitCode = null,
            stdout = "",
            stderr = "",
            timeoutMillis = timeoutSeconds * 1000L,
            approvedByUser = approvedByUser,
            startedAt = startedAt,
            finishedAt = null,
            error = null,
        )
        database.terminalCommandDao().upsert(row)

        val result = if (assessment.blocked) {
            TerminalCommandResult(null, TerminalResultKind.POLICY_BLOCKED, message = assessment.reasons.joinToString("; "))
        } else if (assessment.requiresConfirmation && !approvedByUser) {
            TerminalCommandResult(null, TerminalResultKind.CONFIRMATION_REQUIRED, message = assessment.reasons.joinToString("; "))
        } else {
            bridge.runShell(command, environment, workingDirectory, timeoutSeconds, useTimeoutBinary, approvedByUser)
        }

        database.terminalCommandDao().upsert(
            row.copy(
                status = when (result.kind) {
                    TerminalResultKind.SUCCEEDED -> "succeeded"
                    TerminalResultKind.PROCESS_EXIT -> "process_exit"
                    TerminalResultKind.TIMEOUT -> "timeout"
                    TerminalResultKind.CANCELLED -> "cancelled"
                    TerminalResultKind.CONFIRMATION_REQUIRED -> "confirmation_required"
                    TerminalResultKind.POLICY_BLOCKED, TerminalResultKind.DENIED -> "denied"
                    else -> "failed"
                },
                exitCode = result.exitCode,
                stdout = result.stdout.take(64_000),
                stderr = result.stderr.take(16_000),
                finishedAt = System.currentTimeMillis(),
                error = result.message?.take(600),
            )
        )
        return result
    }

    suspend fun clearHistory() {
        database.terminalCommandDao().deleteAll()
    }

    fun cancelAll() = bridge.cancelAll()
}

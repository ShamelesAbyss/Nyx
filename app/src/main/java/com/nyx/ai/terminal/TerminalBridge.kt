package com.nyx.ai.terminal

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Application-facing terminal bridge. It adds policy enforcement, safe shell construction, and
 * coroutine cancellation on top of the Termux RUN_COMMAND adapter, and it never grants privileges.
 *
 * Cancellation stops NYX from waiting and marks the record cancelled. Because Termux does not expose
 * the child PID through RUN_COMMAND, a hard timeout is enforced inside the command itself with
 * coreutils `timeout` whenever that binary is available.
 */
class TerminalBridge(
    context: Context,
    private val prootProbe: ProotDistroProbe? = null,
) {
    private val app = context.applicationContext
    @Volatile private var discoveredProotPath: String? = null
    private val adapter = TermuxIpcAdapter(app, prootProbe ?: ProotDistroProbe { discoveredProotPath })
    private val active = ConcurrentHashMap<String, TerminalCommandHandle>()

    internal fun configureDiscoveredProot(path: String?) {
        discoveredProotPath = path?.takeIf { it.startsWith("/") && it.length <= 4096 && '\u0000' !in it }
    }

    fun readiness(): TermuxReadiness = adapter.readiness()

    fun assess(command: String): CommandAssessment = CommandPolicy.assess(command)

    suspend fun execute(request: TerminalCommandRequest): TerminalCommandResult =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val handle = adapter.execute(request) { result ->
                if (completed.compareAndSet(false, true)) {
                    result.requestId?.let { active.remove(it) }
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            if (handle == null) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(
                        TerminalCommandResult(
                            requestId = null,
                            kind = TerminalResultKind.DISPATCH_ERROR,
                            message = "The command could not be dispatched to Termux.",
                        )
                    )
                }
                return@suspendCancellableCoroutine
            }
            if (!completed.get()) active[handle.requestId] = handle
            continuation.invokeOnCancellation {
                active.remove(handle.requestId)
                handle.cancel()
            }
        }

    /**
     * Runs a shell command after risk assessment. A command that needs confirmation is refused unless
     * [approved] is true; a blocked command is always refused.
     */
    suspend fun runShell(
        command: String,
        environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
        workingDirectory: String? = null,
        timeoutSeconds: Long = 120,
        useTimeoutBinary: Boolean = true,
        approved: Boolean = false,
    ): TerminalCommandResult {
        val assessment = CommandPolicy.assess(command)
        if (assessment.blocked) {
            return TerminalCommandResult(
                requestId = null,
                kind = TerminalResultKind.POLICY_BLOCKED,
                message = assessment.reasons.joinToString("; ").ifBlank { "Command is blocked by policy." },
            )
        }
        if (assessment.requiresConfirmation && !approved) {
            return TerminalCommandResult(
                requestId = null,
                kind = TerminalResultKind.CONFIRMATION_REQUIRED,
                message = assessment.reasons.joinToString("; ").ifBlank { "This command can modify the environment." },
            )
        }
        if (workingDirectory != null && !ShellCommandBuilder.isValidWorkingDirectory(workingDirectory)) {
            return TerminalCommandResult(null, TerminalResultKind.INVALID_REQUEST, message = "Working directory is not an allowed absolute path.")
        }
        val request = when (environment) {
            TerminalEnvironment.TERMUX_HOST -> ShellCommandBuilder.hostRequest(command, workingDirectory, timeoutSeconds, useTimeoutBinary)
            TerminalEnvironment.DEBIAN_PROOT -> ShellCommandBuilder.prootRequest(command, workingDirectory, timeoutSeconds, useTimeoutBinary)
        }
        return try {
            execute(request)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            TerminalCommandResult(null, TerminalResultKind.DISPATCH_ERROR, message = e.message?.take(300) ?: "Terminal execution failed")
        }
    }

    /** Stops waiting on every in-flight command; Termux may still finish the underlying process. */
    fun cancelAll() {
        active.keys.toList().forEach { id ->
            active.remove(id)?.cancel()
        }
    }

    private object NoProotProbeAccessor {
        val probe = ProotDistroProbe { null }
    }
}

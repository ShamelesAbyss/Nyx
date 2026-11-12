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
    private val adapter = TermuxIpcAdapter(
        app,
        prootProbe ?: ProotDistroProbe { ProotExecutableStore.resolve(app) },
    )
    private val active = ConcurrentHashMap<String, TerminalCommandHandle>()

    internal fun configureDiscoveredProot(path: String?) {
        // Persist the verified host path so a manager, service, or bridge created later in this
        // process (or after an app restart) can dispatch Debian commands without re-discovery.
        ProotExecutableStore.remember(app, path)
    }

    fun readiness(): TermuxReadiness = adapter.readiness()

    fun assess(command: String): CommandAssessment = CommandPolicy.assess(command)

    /** The last host-verified proot-distro path, shared by every bridge instance in this process. */
    fun prootExecutablePath(): String? = ProotExecutableStore.resolve(app)

    /**
     * Starts a PRoot session with `proot-distro login --detach`, which daemonizes the whole proot
     * process rather than a child inside it. The command returns after the session is created.
     */
    suspend fun runProotDetached(
        distroAlias: String,
        guestScript: String,
        timeoutSeconds: Long = 30,
    ): TerminalCommandResult {
        val command = prootHostCommand(distroAlias, guestScript, detach = true)
            ?: return TerminalCommandResult(null, TerminalResultKind.PROOT_DISTRO_UNAVAILABLE, message = "A verified proot-distro path is required.")
        return runHostIsolated(command, timeoutSeconds)
    }

    /**
     * Starts one long-lived PRoot login without waiting for Termux's result callback. The login
     * process remains alive because the guest script ends in `exec ollama serve`. This is the
     * fallback for older proot-distro versions without `--detach`.
     */
    fun dispatchPersistentProot(
        distroAlias: String,
        guestScript: String,
    ): TerminalCommandResult {
        val command = prootHostCommand(distroAlias, guestScript, detach = false)
            ?: return TerminalCommandResult(null, TerminalResultKind.PROOT_DISTRO_UNAVAILABLE, message = "A verified proot-distro path is required.")
        val assessment = CommandPolicy.assess(command)
        if (assessment.blocked) {
            return TerminalCommandResult(
                null,
                TerminalResultKind.POLICY_BLOCKED,
                message = assessment.reasons.joinToString("; ").ifBlank { "The PRoot session command is blocked by policy." },
            )
        }
        return adapter.dispatchPersistent(HostShell.isolatedRequest(command, timeoutSeconds = 30))
    }

    private fun prootHostCommand(distroAlias: String, guestScript: String, detach: Boolean): String? {
        if (!HostShell.isSafeAlias(distroAlias)) return null
        if (guestScript.isBlank() || '\u0000' in guestScript || guestScript.length > HostShell.MAX_SCRIPT_CHARS) return null
        val prootPath = ProotExecutableStore.resolve(app) ?: return null
        val detachArgument = if (detach) " --detach" else ""
        return "${ShellCommandBuilder.quote(prootPath)} login$detachArgument $distroAlias -- /bin/bash -lc ${ShellCommandBuilder.quote(guestScript)}"
    }

    /**
     * Runs a fixed, read-only probe on the Termux host, bypassing startup profile scripts that can
     * divert an ordinary shell into a PRoot guest. Policy blocking still applies; these scripts are
     * constructed by NYX rather than supplied by a model or the user.
     */
    suspend fun runHostIsolated(script: String, timeoutSeconds: Long = 45): TerminalCommandResult {
        val assessment = CommandPolicy.assess(script)
        if (assessment.blocked) {
            return TerminalCommandResult(
                requestId = null,
                kind = TerminalResultKind.POLICY_BLOCKED,
                message = assessment.reasons.joinToString("; ").ifBlank { "Discovery probe is blocked by policy." },
            )
        }
        return try {
            execute(HostShell.isolatedRequest(script, timeoutSeconds))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            TerminalCommandResult(null, TerminalResultKind.DISPATCH_ERROR, message = e.message?.take(300) ?: "Isolated host execution failed")
        }
    }

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
        guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS,
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
            TerminalEnvironment.DEBIAN_PROOT -> ShellCommandBuilder.prootRequest(
                command, workingDirectory, timeoutSeconds, useTimeoutBinary, distroAlias = guestAlias,
            )
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

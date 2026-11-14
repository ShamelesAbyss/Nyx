package com.nyx.ai.terminal

import android.content.Context
import com.nyx.ai.autonomy.DeviceResourceReader
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ResourcePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
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
    private val cancellationEpoch = AtomicLong()
    private val registry get() = sharedRegistry(app)

    /** Device/performance guards still apply. Changes affect admission, never kill active work. */
    fun configureCommandContexts(maxContexts: Int) {
        app.getSharedPreferences("terminal_execution", Context.MODE_PRIVATE).edit()
            .putInt("max_contexts", maxContexts.coerceIn(1, TerminalOperationRegistry.MAX_CONTEXTS)).apply()
    }

    suspend fun operationEvidence(): List<TerminalOperationEvidence> = withContext(Dispatchers.IO) { registry.snapshot() }
    suspend fun operationHistory(): List<TerminalOperationEvidence> = withContext(Dispatchers.IO) { registry.history() }

    suspend fun acknowledgeReviewedOperation(id: String, confirmedNoLongerRunning: Boolean): Boolean =
        withContext(Dispatchers.IO) { registry.acknowledgeReviewed(id, confirmedNoLongerRunning) }

    private fun capacity(): Int = runCatching {
        ResourcePolicy.terminalCapacity(
            DeviceResourceReader.read(app),
            app.getSharedPreferences("terminal_execution", Context.MODE_PRIVATE).getInt("max_contexts", 3),
            com.nyx.ai.performance.PerformanceController.tuning().autonomyParallelism,
        )
    }.getOrDefault(1)

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

    suspend fun execute(
        request: TerminalCommandRequest,
        operation: TerminalOperationContext = TerminalOperationContext(
            namespace = if (request.environment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:${request.distroAlias}",
            workspace = request.workingDirectory,
        ),
    ): TerminalCommandResult {
        val scope = coroutineContext[TerminalExecutionScope]
        return executeOwned(request, operation.copy(objectiveId = operation.objectiveId ?: scope?.objectiveId,
            taskId = operation.taskId ?: scope?.taskId))
    }

    private suspend fun executeOwned(request: TerminalCommandRequest, operation: TerminalOperationContext): TerminalCommandResult = withContext(Dispatchers.IO) {
        val epoch = cancellationEpoch.get()
        registry.enqueue(operation)
        var dispatched = false
        try {
            val queuedAt = System.nanoTime()
            while (true) {
                if (epoch != cancellationEpoch.get()) {
                    val result = TerminalCommandResult(operation.operationId, TerminalResultKind.CANCELLED,
                        message = "Cancelled before dispatch; no command started.")
                    registry.finish(operation.operationId, result.kind.name, null, "", "", result.message, uncertain = false)
                    return@withContext result
                }
                if (registry.tryStart(operation.operationId, capacity())) break
                if ((System.nanoTime() - queuedAt) / 1_000_000L >= request.timeoutMillis) {
                    val result = TerminalCommandResult(operation.operationId, TerminalResultKind.TIMEOUT,
                        message = "Terminal admission timed out before dispatch; no command started. Review resource/conflict reservations.")
                    registry.finish(operation.operationId, result.kind.name, null, "", "", result.message, uncertain = false)
                    return@withContext result
                }
                delay(100)
            }
            dispatched = true
            val remainingMs = (request.timeoutMillis - (System.nanoTime() - queuedAt) / 1_000_000L).coerceAtLeast(1L)
            val result = awaitResult(request.copy(timeoutMillis = remainingMs), operation.operationId, epoch)
            registry.finish(operation.operationId, result.kind.name, result.exitCode, result.stdout, result.stderr,
                result.message, uncertain = result.kind in setOf(TerminalResultKind.TIMEOUT, TerminalResultKind.CANCELLED,
                    TerminalResultKind.UNKNOWN_RESULT, TerminalResultKind.TERMUX_INFRASTRUCTURE_ERROR))
            result
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                registry.finish(operation.operationId, TerminalResultKind.CANCELLED.name, null, "", "",
                    if (dispatched) "Result wait cancelled; Termux command may still run. Review before replay."
                    else "Cancelled before dispatch; no command started.", uncertain = dispatched)
            }
            throw cancel
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { registry.finish(operation.operationId, TerminalResultKind.DISPATCH_ERROR.name, null, "", "",
                    error.message?.take(600), uncertain = dispatched) }
            }
            TerminalCommandResult(operation.operationId, TerminalResultKind.DISPATCH_ERROR, message = error.message?.take(300))
        }
    }

    private suspend fun awaitResult(request: TerminalCommandRequest, operationId: String, epoch: Long): TerminalCommandResult =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val handle = synchronized(active) {
                if (epoch != cancellationEpoch.get() || !continuation.isActive) {
                    if (continuation.isActive) continuation.resume(TerminalCommandResult(operationId, TerminalResultKind.CANCELLED,
                        message = "Cancelled at dispatch boundary; no command started."))
                    return@suspendCancellableCoroutine
                }
                adapter.execute(request, operationId) { result ->
                if (completed.compareAndSet(false, true)) {
                    synchronized(active) { result.requestId?.let { active.remove(it) } }
                    if (continuation.isActive) continuation.resume(result)
                }
            }.also { handle -> if (handle != null && !completed.get()) active[handle.requestId] = handle } }
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
            continuation.invokeOnCancellation {
                synchronized(active) { active.remove(handle.requestId) }
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
        operation: TerminalOperationContext? = null,
        executionTimeouts: ExecutionTimeoutPolicy? = null,
        buildOperation: Boolean = false,
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
        val ceiling = executionTimeouts?.let { if (buildOperation) it.buildMs else it.terminalMs }
            ?: TerminalCommandRequest.MAX_TIMEOUT_MILLIS
        val boundedSeconds = minOf(timeoutSeconds.coerceAtLeast(1L), (ceiling / 1000L).coerceAtLeast(1L))
        val request = when (environment) {
            TerminalEnvironment.TERMUX_HOST -> ShellCommandBuilder.hostRequest(command, workingDirectory, boundedSeconds, useTimeoutBinary)
            TerminalEnvironment.DEBIAN_PROOT -> ShellCommandBuilder.prootRequest(
                command, workingDirectory, boundedSeconds, useTimeoutBinary, distroAlias = guestAlias,
            )
        }
        return try {
            val scope = coroutineContext[TerminalExecutionScope]
            val context = operation ?: TerminalOperationContext(
                objectiveId = scope?.objectiveId,
                taskId = scope?.taskId,
                namespace = if (environment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:$guestAlias",
                workspace = workingDirectory,
                readOnly = assessment.risk == CommandRisk.READ_ONLY && TerminalConcurrencyPolicy.canShareWorkspace(command),
            )
            // Caller identity cannot change the actual environment, workspace or assessed risk.
            execute(request, context.copy(
                namespace = if (environment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:$guestAlias",
                workspace = workingDirectory,
                readOnly = assessment.risk == CommandRisk.READ_ONLY && TerminalConcurrencyPolicy.canShareWorkspace(command),
            ))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            TerminalCommandResult(null, TerminalResultKind.DISPATCH_ERROR, message = e.message?.take(300) ?: "Terminal execution failed")
        }
    }

    /** Stops waiting on every in-flight command; Termux may still finish the underlying process. */
    fun cancelAll() {
        val handles = synchronized(active) {
            cancellationEpoch.incrementAndGet()
            active.values.toList().also { active.clear() }
        }
        handles.forEach { it.cancel() }
    }

    companion object {
        @Volatile private var processRegistry: TerminalOperationRegistry? = null
        private fun sharedRegistry(context: Context): TerminalOperationRegistry = processRegistry ?: synchronized(this) {
            processRegistry ?: TerminalOperationRegistry(TerminalOperationJournal(File(context.filesDir, "terminal-operations")))
                .also { processRegistry = it }
        }
    }
}

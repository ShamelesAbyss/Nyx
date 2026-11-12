package com.nyx.ai.ollama

import android.content.Context
import android.content.SharedPreferences
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.OllamaClient
import com.nyx.ai.terminal.TerminalBridge
import com.nyx.ai.terminal.TerminalCommandResult
import com.nyx.ai.terminal.TerminalResultKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the lifecycle of an Ollama server running on the Termux host.
 *
 * Why a dedicated manager:
 * - **The interactive profile is untrusted.** The user's shell may auto-`exec` `proot-distro login`,
 *   so every probe and every lifecycle command is dispatched through
 *   [TerminalBridge.runHostIsolated], which builds `bash --noprofile --norc -c` and unsets
 *   `BASH_ENV`/`ENV`. `runShell(TermuxEnvironment.TERMUX_HOST)` is never used here.
 * - **A PID is not an identity.** NYX records `/proc/<pid>/stat` field 22 (the kernel start token)
 *   next to the PID, then re-verifies command line *and* token inside the shell immediately before
 *   signalling. A recycled PID is refused, never killed.
 * - **NYX stops only what NYX started.** Servers discovered through the API are adopted with
 *   `startedByNyx = false` and are never signalled. There is no `pkill`, `killall`, or process-group
 *   kill anywhere in this subsystem.
 * - **Duplicate prevention is layered.** The API is probed first; the start script independently
 *   refuses when a verified record is still alive, so two concurrent callers cannot both spawn a
 *   server.
 *
 * Lifecycle operations are serialized by [mutex]; [state] is safe to collect from the UI.
 */
class OllamaServerManager(
    context: Context,
    private val bridge: TerminalBridge,
    endpoint: String? = null,
    client: OllamaClient? = null,
    autoRefresh: Boolean = true,
) {
    /** Normalized endpoint used for API probes. An invalid value falls back to loopback:11434. */
    val endpoint: String = OllamaProcess.normalizeEndpoint(endpoint)

    private val app: Context = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client: OllamaClient = client ?: OllamaClient(this.endpoint)
    private val mutex = Mutex()

    /** Manager-owned scope for the optional startup reconciliation only. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    private val _state = MutableStateFlow(
        OllamaServerState(
            status = OllamaServerStatus.STOPPED,
            endpoint = this.endpoint,
            message = "Ollama has not been checked yet.",
        )
    )
    val state: StateFlow<OllamaServerState> = _state.asStateFlow()

    init {
        // After a process restart, adopt a server that is still running before the UI offers Start.
        if (autoRefresh) {
            scope.launch {
                runCatching { refresh() }
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /** Read-only installation probe on the Termux host. Never starts or stops anything. */
    suspend fun detect(): OllamaServerState = mutex.withLock {
        if (closed) return@withLock state.value
        val detection = probeInstallation()
        val installed = detection.installed
        val moved = OllamaLifecyclePolicy.nextState(
            _state.value.status,
            if (installed) OllamaLifecycleEvent.DETECTED_INSTALLED else OllamaLifecycleEvent.DETECTED_MISSING,
        )
        val message = if (detection.error.isNotBlank() && !installed) {
            "The Termux host could not be queried: ${detection.error}"
        } else if (installed) {
            buildString {
                append("Ollama")
                if (detection.version.isNotBlank()) append(' ').append(detection.version)
                append(" is installed")
                detection.path?.let { append(" at ").append(it) }
                append('.')
            }
        } else {
            "Ollama is not installed on the Termux host. Install it in Termux before starting a local server."
        }
        update { current ->
            current.copy(
                status = moved,
                pid = if (moved == OllamaServerStatus.RUNNING) current.pid else null,
                startedByNyx = if (moved == OllamaServerStatus.RUNNING) current.startedByNyx else false,
                models = if (moved == OllamaServerStatus.RUNNING) current.models else emptyList(),
                message = message,
            )
        }
        state.value
    }

    /**
     * Reconciles NYX's view with the host: reads the saved record, verifies ownership, probes
     * `GET /api/tags`, and adopts a running server. Also the method to call on app start.
     */
    suspend fun refresh(): OllamaServerState = mutex.withLock {
        if (closed) return@withLock state.value
        doRefresh()
    }

    /**
     * Starts the server unless one is already healthy. A verified NYX-owned process is never
     * duplicated; an already-answering API is adopted instead.
     */
    suspend fun start(): OllamaServerState = mutex.withLock {
        if (closed) return@withLock state.value
        doStart()
    }

    /**
     * Stops only the verified NYX-owned PID. An adopted server is left running and reported as such.
     */
    suspend fun stop(): OllamaServerState = mutex.withLock {
        if (closed) return@withLock state.value
        doStop()
    }

    /**
     * Restarts only a server NYX owns. Restarting an adopted server would require terminating a
     * process the user started, so it is refused with an explanatory message.
     */
    suspend fun restart(): OllamaServerState = mutex.withLock {
        if (closed) return@withLock state.value
        doRestart()
    }

    /**
     * Refreshes RSS and uptime for the recorded PID. Bounded, read-only, and non-fatal: a failure
     * leaves the previous values in place instead of turning a healthy server into an error.
     */
    suspend fun resourceUsage(): OllamaServerState = mutex.withLock {
        if (closed) return@withLock state.value
        val snapshot = readSnapshot()
        update { current ->
            if (current.pid == null && snapshot.record == null) {
                current.copy(logTail = snapshot.logTail.ifBlank { current.logTail })
            } else {
                current.copy(
                    rssKb = snapshot.rssKb ?: current.rssKb,
                    uptimeMillis = snapshot.uptimeMillis ?: current.uptimeMillis,
                    logTail = snapshot.logTail.ifBlank { current.logTail },
                )
            }
        }
        state.value
    }

    /** Stops future work. The running server is intentionally left untouched. */
    fun close() {
        closed = true
        scope.cancel()
    }

    // ------------------------------------------------------------------ reconcile

    private suspend fun doRefresh(): OllamaServerState {
        val snapshot = readSnapshot()
        val saved = snapshot.record
        val owned = snapshot.ownership == OllamaOwnership.OWNED && readStartedByNyxFlag()
        if (saved != null && isDefinitelyStale(snapshot.ownership)) {
            // The record no longer names a live server NYX started; drop it rather than keep a
            // stale kill target around.
            clearSaved()
        }
        val healthy = probeApi()
        if (healthy) {
            val models = fetchModels()
            val message = when {
                owned && saved != null -> "Ollama is running with the NYX-started process (PID ${saved.pid})."
                else -> "Adopted an Ollama server that was already running outside NYX."
            }.let { base ->
                if (models.isEmpty()) "$base No models are installed yet." else base
            }
            update {
                it.copy(
                    status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.ADOPTED),
                    pid = if (owned) saved?.pid else null,
                    models = models,
                    startedByNyx = owned,
                    message = message,
                    logTail = snapshot.logTail,
                    rssKb = if (owned) snapshot.rssKb else null,
                    uptimeMillis = if (owned) snapshot.uptimeMillis else null,
                )
            }
            return state.value
        }
        if (owned && saved != null) {
            val status = OllamaLifecyclePolicy.statusForOwnedButNotReady(snapshot.uptimeMillis)
            update {
                it.copy(
                    status = status,
                    pid = saved.pid,
                    models = emptyList(),
                    startedByNyx = true,
                    message = if (status == OllamaServerStatus.STARTING) {
                        "The NYX-started Ollama process (PID ${saved.pid}) is alive but the API is not ready yet."
                    } else {
                        "The NYX-started Ollama process (PID ${saved.pid}) is alive but the API is not answering. Check the log."
                    },
                    logTail = snapshot.logTail,
                    rssKb = snapshot.rssKb,
                    uptimeMillis = snapshot.uptimeMillis,
                )
            }
            return state.value
        }
        val detection = probeInstallation()
        val moved = OllamaLifecyclePolicy.nextState(
            OllamaServerStatus.STOPPED,
            if (detection.installed) OllamaLifecycleEvent.DETECTED_INSTALLED else OllamaLifecycleEvent.DETECTED_MISSING,
        )
        update {
            it.copy(
                status = if (detection.error.isNotBlank() && !detection.installed) currentOrErrorStatus() else moved,
                pid = null,
                models = emptyList(),
                startedByNyx = false,
                message = if (detection.error.isNotBlank() && !detection.installed) {
                    "The Termux host could not be queried: ${detection.error}"
                } else if (detection.installed) {
                    "Ollama is installed but the server is not running."
                } else {
                    "Ollama is not installed on the Termux host."
                },
                logTail = snapshot.logTail,
                rssKb = null,
                uptimeMillis = null,
            )
        }
        return state.value
    }

    // ------------------------------------------------------------------ start

    private suspend fun doStart(): OllamaServerState {
        currentCoroutineContext().ensureActive()
        val snapshot = readSnapshot()
        val saved = snapshot.record
        val apiHealthy = probeApi()
        // Installation is only relevant when a new process may actually be launched.
        val detection = if (apiHealthy || (saved != null && snapshot.ownership == OllamaOwnership.OWNED)) {
            OllamaDetection(installed = true, path = null)
        } else {
            probeInstallation()
        }
        val plan = OllamaLifecyclePolicy.planStart(
            endpointLoopback = OllamaProcess.isLoopbackEndpoint(endpoint),
            apiHealthy = apiHealthy,
            installed = detection.installed,
            savedPid = saved?.pid,
            ownership = snapshot.ownership,
        )
        return when (plan.decision) {
            OllamaStartDecision.USE_RUNNING_SERVER -> adopt(apiHealthy = true, snapshot = snapshot)

            OllamaStartDecision.REFUSE_DUPLICATE -> {
                val status = OllamaLifecyclePolicy.statusForOwnedButNotReady(snapshot.uptimeMillis)
                update {
                    it.copy(
                        status = status,
                        pid = saved?.pid,
                        models = if (apiHealthy) it.models else emptyList(),
                        startedByNyx = readStartedByNyxFlag(),
                        message = plan.reason,
                        logTail = snapshot.logTail,
                        rssKb = snapshot.rssKb,
                        uptimeMillis = snapshot.uptimeMillis,
                    )
                }
                state.value
            }

            OllamaStartDecision.REFUSE_UNVERIFIED_OWNERSHIP -> fail(plan.reason, snapshot.logTail)

            OllamaStartDecision.REFUSE_REMOTE_ENDPOINT -> fail(plan.reason, snapshot.logTail)

            OllamaStartDecision.INSTALL_REQUIRED -> {
                val hostFailed = detection.error.isNotBlank()
                update {
                    it.copy(
                        status = if (hostFailed) {
                            OllamaServerStatus.ERROR
                        } else {
                            OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.DETECTED_MISSING)
                        },
                        pid = null,
                        models = emptyList(),
                        startedByNyx = false,
                        message = if (hostFailed) "The Termux host could not be queried: ${detection.error}" else plan.reason,
                        logTail = snapshot.logTail,
                    )
                }
                state.value
            }

            OllamaStartDecision.START_NEW -> launch(detection, snapshot)
        }
    }

    private suspend fun adopt(apiHealthy: Boolean, snapshot: OllamaHostSnapshot): OllamaServerState {
        val models = if (apiHealthy) fetchModels() else emptyList()
        val owned = snapshot.ownership == OllamaOwnership.OWNED && readStartedByNyxFlag()
        update {
            it.copy(
                status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.ADOPTED),
                pid = if (owned) snapshot.record?.pid else null,
                models = models,
                startedByNyx = owned,
                message = if (owned) {
                    "Ollama is already running with the NYX-started process; it was adopted instead of started again."
                } else {
                    "An Ollama server was already answering; it was adopted and will not be stopped by NYX."
                },
                logTail = snapshot.logTail,
                rssKb = if (owned) snapshot.rssKb else null,
                uptimeMillis = if (owned) snapshot.uptimeMillis else null,
            )
        }
        return state.value
    }

    private suspend fun launch(detection: OllamaDetection, snapshot: OllamaHostSnapshot): OllamaServerState {
        val binary = detection.path
            ?: return fail("The ollama binary path could not be resolved on the Termux host.", snapshot.logTail)
        update {
            it.copy(
                status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.START_REQUESTED),
                pid = null,
                models = emptyList(),
                startedByNyx = false,
                message = "Starting Ollama on the Termux host…",
                logTail = "",
                rssKb = null,
                uptimeMillis = null,
            )
        }
        val result = host(OllamaProcess.startScript(binary), START_TIMEOUT_SECONDS)
        val outcome = OllamaProcess.parseStartOutcome(result.stdout)
        if (outcome.duplicate) {
            // Another caller won the race. The script verified that PID, so wait for it rather than
            // spawning a second server.
            val existingPid = outcome.pid
            val existingStart = outcome.startTimeTicks
            if (existingPid != null && existingStart != null) {
                saveRecord(SavedOllamaProcess(existingPid, existingStart, System.currentTimeMillis()), startedByNyx = true)
                update { it.copy(status = OllamaServerStatus.STARTING, pid = existingPid, startedByNyx = true, message = outcome.error) }
                return awaitReady(existingPid)
            }
            return fail(outcome.error.ifBlank { "A verified Ollama process is already running." }, snapshot.logTail)
        }
        if (!outcome.started) {
            val detail = outcome.error.ifBlank { failureDetail(result) }
            return fail("Ollama could not be started: $detail", readSnapshot().logTail.ifBlank { snapshot.logTail })
        }
        val pid = outcome.pid
        val startTicks = outcome.startTimeTicks
        if (pid == null || startTicks == null) {
            return fail("Ollama was launched but its PID or start token could not be read.", readSnapshot().logTail)
        }
        saveRecord(SavedOllamaProcess(pid, startTicks, System.currentTimeMillis()), startedByNyx = true)
        update {
            it.copy(
                status = OllamaServerStatus.STARTING,
                pid = pid,
                startedByNyx = true,
                message = "Started Ollama with PID $pid; waiting for the API.",
            )
        }
        return awaitReady(pid)
    }

    private suspend fun awaitReady(pid: Int): OllamaServerState {
        val startedAt = System.currentTimeMillis()
        var attempts = 0
        var lastDetail = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            attempts++
            if (probeApi()) {
                val models = fetchModels()
                val snapshot = readSnapshot()
                update {
                    it.copy(
                        status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.START_SUCCEEDED),
                        pid = pid,
                        models = models,
                        startedByNyx = true,
                        message = OllamaReadiness.successMessage(adopted = false, pid = pid).let { base ->
                            if (models.isEmpty()) "$base No models are installed yet." else base
                        },
                        logTail = snapshot.logTail,
                        rssKb = snapshot.rssKb,
                        uptimeMillis = snapshot.uptimeMillis,
                    )
                }
                return state.value
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (OllamaReadiness.isTimedOut(elapsed)) break
            delay(OllamaReadiness.pollIntervalMillis(attempts))
        }
        // Startup failed. Only the verified PID is stopped, so a partially initialized server does
        // not linger; the log tail is carried into the error state.
        lastDetail = failureDetailFromLog()
        val snapshot = readSnapshot()
        val stoppedNote = stopVerifiedRecord()
        val message = OllamaReadiness.failureMessage(
            timeoutMillis = OllamaReadiness.DEFAULT_TIMEOUT_MILLIS,
            attempts = attempts,
            detail = if (lastDetail.isNotBlank()) lastDetail else "The API did not answer on $endpoint.",
            logTail = snapshot.logTail,
        ) + stoppedNote
        update {
            it.copy(
                status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.START_FAILED),
                pid = null,
                models = emptyList(),
                startedByNyx = false,
                message = message,
                logTail = snapshot.logTail.ifBlank { it.logTail },
                rssKb = null,
                uptimeMillis = null,
            )
        }
        return state.value
    }

    // ------------------------------------------------------------------ stop

    private suspend fun doStop(): OllamaServerState {
        val snapshot = readSnapshot()
        val saved = snapshot.record
        val ownedRecord = snapshot.ownership == OllamaOwnership.OWNED && readStartedByNyxFlag()
        val plan = OllamaLifecyclePolicy.planStop(
            startedByNyx = ownedRecord,
            savedPid = saved?.pid,
            ownership = snapshot.ownership,
        )
        return when (plan.decision) {
            OllamaStopDecision.STOP_OWNED -> {
                val record = saved ?: return fail("The saved PID record is missing.", snapshot.logTail)
                update {
                    it.copy(
                        status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.STOP_REQUESTED),
                        pid = record.pid,
                        startedByNyx = true,
                        message = "Stopping the NYX-started Ollama process (PID ${record.pid})…",
                    )
                }
                val result = host(OllamaProcess.stopScript(record), STOP_TIMEOUT_SECONDS)
                val outcome = OllamaProcess.parseStopOutcome(result.stdout)
                when {
                    outcome.stopped -> {
                        clearSaved()
                        val apiStillUp = probeApi()
                        update {
                            it.copy(
                                status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.STOP_SUCCEEDED),
                                pid = null,
                                models = emptyList(),
                                startedByNyx = false,
                                message = when {
                                    apiStillUp -> "The NYX-started process ended, but another Ollama server is already answering on $endpoint."
                                    outcome.killed -> "Ollama stopped after ignoring a graceful TERM and was terminated."
                                    else -> "Ollama stopped."
                                },
                                logTail = snapshot.logTail,
                                rssKb = null,
                                uptimeMillis = null,
                            )
                        }
                        state.value
                    }

                    outcome.refused -> {
                        // The record no longer matches the process: never signal it, and drop the
                        // stale target so a later stop cannot act on it either.
                        clearSaved()
                        val apiStillUp = probeApi()
                        val failedLogTail = readSnapshot().logTail
                        update {
                            it.copy(
                                status = if (apiStillUp) {
                                    OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.STOP_REFUSED)
                                } else {
                                    OllamaServerStatus.STOPPED
                                },
                                pid = null,
                                models = if (apiStillUp) it.models else emptyList(),
                                startedByNyx = false,
                                message = "Ollama was left running: ${outcome.reason.ifBlank { "ownership could not be verified" }}.",
                                logTail = failedLogTail,
                                rssKb = null,
                                uptimeMillis = null,
                            )
                        }
                        state.value
                    }

                    else -> fail("Ollama could not be stopped: ${failureDetail(result)}", readSnapshot().logTail)
                }
            }

            OllamaStopDecision.NOT_RUNNING -> {
                clearSaved()
                update {
                    it.copy(
                        status = OllamaServerStatus.STOPPED,
                        pid = null,
                        models = emptyList(),
                        startedByNyx = false,
                        message = plan.reason,
                        logTail = snapshot.logTail,
                        rssKb = null,
                        uptimeMillis = null,
                    )
                }
                state.value
            }

            OllamaStopDecision.NOT_OWNED -> {
                val apiHealthy = probeApi()
                if (saved != null && isDefinitelyStale(snapshot.ownership)) clearSaved()
                update {
                    it.copy(
                        status = if (apiHealthy) OllamaServerStatus.RUNNING else OllamaServerStatus.STOPPED,
                        pid = null,
                        models = if (apiHealthy) it.models else emptyList(),
                        startedByNyx = false,
                        message = plan.reason,
                        logTail = snapshot.logTail,
                    )
                }
                state.value
            }
        }
    }

    /** Stops the verified record if there is one; returns a short note for the caller's message. */
    private suspend fun stopVerifiedRecord(): String {
        val snapshot = readSnapshot()
        val record = snapshot.record ?: return ""
        if (snapshot.ownership != OllamaOwnership.OWNED) {
            if (snapshot.ownership != OllamaOwnership.MISSING) clearSaved()
            return ""
        }
        if (!readStartedByNyxFlag()) return ""
        val result = host(OllamaProcess.stopScript(record), STOP_TIMEOUT_SECONDS)
        val outcome = OllamaProcess.parseStopOutcome(result.stdout)
        if (outcome.stopped) {
            clearSaved()
            return " The failed startup process was stopped."
        }
        return ""
    }

    // ------------------------------------------------------------------ restart

    private suspend fun doRestart(): OllamaServerState {
        val snapshot = readSnapshot()
        val saved = snapshot.record
        val owned = snapshot.ownership == OllamaOwnership.OWNED && readStartedByNyxFlag()
        val decision = OllamaLifecyclePolicy.planRestart(
            running = state.value.isRunning || snapshot.ownership == OllamaOwnership.OWNED,
            startedByNyx = owned,
            savedPid = saved?.pid,
            ownership = snapshot.ownership,
        )
        if (decision == OllamaRestartDecision.REFUSE_NOT_OWNED) {
            val apiHealthy = probeApi()
            update {
                it.copy(
                    status = if (apiHealthy) OllamaServerStatus.RUNNING else it.status,
                    startedByNyx = false,
                    message = "Restart refused: the running Ollama server was not started by NYX, so it will not be terminated.",
                    logTail = snapshot.logTail,
                )
            }
            return state.value
        }
        if (saved != null && owned && snapshot.ownership == OllamaOwnership.OWNED) {
            doStop()
            if (state.value.status == OllamaServerStatus.ERROR) return state.value
        }
        return doStart()
    }

    // ------------------------------------------------------------------ host + network

    private suspend fun host(script: String, timeoutSeconds: Long): TerminalCommandResult = try {
        // Host-isolated on purpose: the interactive profile may `exec` into Debian.
        bridge.runHostIsolated(script, timeoutSeconds)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        TerminalCommandResult(
            requestId = null,
            kind = TerminalResultKind.DISPATCH_ERROR,
            message = e.message?.take(300) ?: "The host command could not be dispatched.",
        )
    }

    private suspend fun readSnapshot(): OllamaHostSnapshot {
        val result = host(OllamaProcess.stateScript(), STATE_TIMEOUT_SECONDS)
        val parsed = OllamaProcess.parseHostSnapshot(result.stdout)
        return if (parsed.logTail.isNotBlank() || parsed.record != null) {
            parsed
        } else {
            parsed.copy(logTail = failureDetail(result).takeIf { it.isNotBlank() && result.kind != TerminalResultKind.SUCCEEDED }.orEmpty())
        }
    }

    private suspend fun probeInstallation(): OllamaDetection {
        val result = host(OllamaProcess.detectScript(), DETECT_TIMEOUT_SECONDS)
        val detection = OllamaProcess.parseDetection(result.stdout)
        if (!detection.installed && !result.isSuccess && result.kind != TerminalResultKind.PROCESS_EXIT) {
            return OllamaDetection(installed = false, error = failureDetail(result).take(300))
        }
        return detection
    }

    private suspend fun probeApi(): Boolean = try {
        currentCoroutineContext().ensureActive()
        val healthy = client.healthCheck()
        currentCoroutineContext().ensureActive()
        healthy
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        false
    }

    private suspend fun fetchModels(): List<LocalModel> = try {
        currentCoroutineContext().ensureActive()
        client.listModels()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun failureDetailFromLog(): String {
        val snapshot = readSnapshot()
        if (snapshot.logTail.isBlank()) return ""
        return snapshot.logTail.lineSequence().lastOrNull { it.isNotBlank() }?.take(300).orEmpty()
    }

    private fun failureDetail(result: TerminalCommandResult): String {
        OllamaProcess.markerValue(result.stdout, "NYX_OLLAMA_ERROR")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.take(300) }
        result.stderr.lineSequence().lastOrNull { it.isNotBlank() }
            ?.let { return it.trim().take(300) }
        result.message?.takeIf { it.isNotBlank() }?.let { return it.take(300) }
        return result.kind.name.lowercase().replace('_', ' ')
    }

    // ------------------------------------------------------------------ persistence

    private fun readStartedByNyxFlag(): Boolean = prefs.getBoolean(KEY_STARTED_BY_NYX, false)

    /** Ownership verdicts that prove the saved record can never be signalled again. */
    private fun isDefinitelyStale(ownership: OllamaOwnership): Boolean = ownership == OllamaOwnership.MISSING ||
        ownership == OllamaOwnership.PID_REUSED ||
        ownership == OllamaOwnership.NOT_OLLAMA

    private fun currentOrErrorStatus(): OllamaServerStatus =
        if (_state.value.status == OllamaServerStatus.ERROR) OllamaServerStatus.ERROR else OllamaServerStatus.STOPPED

    private fun saveRecord(record: SavedOllamaProcess, startedByNyx: Boolean) {
        prefs.edit()
            .putString(KEY_RECORD, OllamaProcess.encodeRecord(record))
            .putBoolean(KEY_STARTED_BY_NYX, startedByNyx)
            .putLong(KEY_STARTED_AT, record.startedAtMillis)
            .putString(KEY_ENDPOINT, endpoint)
            .apply()
    }

    private fun clearSaved() {
        prefs.edit()
            .remove(KEY_RECORD)
            .putBoolean(KEY_STARTED_BY_NYX, false)
            .remove(KEY_STARTED_AT)
            .apply()
    }

    private fun fail(message: String, logTail: String): OllamaServerState {
        update {
            it.copy(
                status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.START_FAILED),
                pid = null,
                models = emptyList(),
                startedByNyx = false,
                message = message,
                logTail = OllamaProcess.sanitizeLogTail(logTail.ifBlank { it.logTail }),
                rssKb = null,
                uptimeMillis = null,
            )
        }
        return state.value
    }

    private fun update(transform: (OllamaServerState) -> OllamaServerState) {
        _state.update { current ->
            transform(current).copy(endpoint = endpoint, lastCheckedAt = System.currentTimeMillis())
        }
    }

    companion object {
        const val PREFS_NAME = "nyx_ollama_server"
        const val KEY_RECORD = "record"
        const val KEY_STARTED_BY_NYX = "started_by_nyx"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ENDPOINT = "endpoint"

        private const val DETECT_TIMEOUT_SECONDS = 20L
        private const val STATE_TIMEOUT_SECONDS = 20L
        private const val START_TIMEOUT_SECONDS = 30L
        private const val STOP_TIMEOUT_SECONDS = 30L
    }
}

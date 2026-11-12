package com.nyx.ai.ollama

import android.content.Context
import android.content.SharedPreferences
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.OllamaClient
import com.nyx.ai.terminal.HostShell
import com.nyx.ai.terminal.TerminalBridge
import com.nyx.ai.terminal.TerminalCommandResult
import com.nyx.ai.terminal.TerminalEnvironment
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
import java.util.UUID

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

    /** The exact filesystem/process namespace whose Ollama installation is being managed. */
    @Volatile
    private var target: OllamaTarget = readSavedTarget() ?: OllamaTarget.HOST

    @Volatile
    private var targetResolved: Boolean = prefs.contains(KEY_TARGET_ENV)

    @Volatile
    private var guestAliases: List<String> = emptyList()

    @Volatile
    private var preferredGuestAlias: String? = null

    @Volatile
    private var preferredEnvironment: TerminalEnvironment = target.environment

    /** Cached, read-only capabilities of the installed proot-distro for the current guest target. */
    @Volatile
    private var sessionCapabilities: ProotSessionCapabilities? = null

    /** The exact PRoot session and guest binary last launched by NYX. */
    @Volatile
    private var savedSession: SavedOllamaSession? = readSavedSession()

    @Volatile
    private var lastBinaryPath: String? = null

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

    /**
     * Supplies the guests that were really verified by environment discovery. An unverified alias
     * is never added to the candidate list, so a typo cannot become a probe or a lifecycle target.
     */
    fun configureGuests(
        verifiedAliases: List<String>,
        selectedAlias: String?,
        environment: TerminalEnvironment = preferredEnvironment,
    ) {
        val safe = verifiedAliases.filter { HostShell.isSafeAlias(it) }.distinct().take(MAX_GUESTS)
        guestAliases = safe
        preferredGuestAlias = selectedAlias?.takeIf { it in safe }
        preferredEnvironment = environment
        sessionCapabilities = null
        if (!target.isHost && target.guestAlias !in safe) {
            // The saved guest disappeared. Keep the selection for diagnostics, but force discovery
            // before any lifecycle command can use a stale target.
            targetResolved = false
        }
    }

    // ------------------------------------------------------------------ public API

    /** Read-only discovery across the isolated host and every verified PRoot guest. */
    suspend fun detect(): OllamaServerState = mutex.withLock {
        if (closed) return@withLock state.value
        val plan = discoverInstallations()
        plan.selected?.let { selected ->
            target = selected.target
            saveTarget(target)
            targetResolved = true
        }
        savedSession = readSavedSession()
        val snapshot = readSnapshot()
        val owned = snapshot.ownership == OllamaOwnership.OWNED && readStartedByNyxFlag()
        applyDiscovery(plan, probeApi(), snapshot, owned)
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
        if (!targetResolved) {
            val discovery = discoverInstallations()
            discovery.selected?.let { selected ->
                target = selected.target
                saveTarget(target)
                targetResolved = true
            }
        }
        savedSession = readSavedSession()
        if (!target.isHost && savedSession?.mode == OllamaLaunchMode.PROOT_DETACHED && !verifySavedSession()) {
            clearSession()
        }
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
                    target = target,
                    architecture = state.value.architecture,
                    version = state.value.version,
                )
            }
            return state.value
        }
        if (owned && saved != null) {
            val status = ownedNotReadyStatus(snapshot.uptimeMillis)
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
        val discovery = discoverInstallations()
        val selected = discovery.selected
        if (selected != null) {
            target = selected.target
            saveTarget(target)
            targetResolved = true
        }
        val verifiedDetachedSession = !target.isHost &&
            savedSession?.mode == OllamaLaunchMode.PROOT_DETACHED &&
            verifySavedSession()
        val status = when {
            verifiedDetachedSession -> if (ownedNotReadyStatus(snapshot.uptimeMillis) == OllamaServerStatus.ERROR) {
                OllamaServerStatus.UNREACHABLE
            } else {
                OllamaServerStatus.STARTING
            }
            selected == null -> OllamaLifecyclePolicy.nextState(
                OllamaServerStatus.STOPPED,
                OllamaLifecycleEvent.DETECTED_MISSING,
            )
            !selected.detection.compatible -> OllamaServerStatus.INCOMPATIBLE
            else -> OllamaLifecyclePolicy.nextState(
                OllamaServerStatus.STOPPED,
                OllamaLifecycleEvent.DETECTED_INSTALLED,
            )
        }
        update {
            it.copy(
                status = status,
                pid = null,
                models = emptyList(),
                startedByNyx = false,
                message = if (verifiedDetachedSession) {
                    "A verified PRoot session is alive, but its guest Ollama API is not ready yet. NYX will not start a second server."
                } else {
                    discovery.detail
                },
                logTail = snapshot.logTail,
                rssKb = null,
                uptimeMillis = null,
                target = selected?.target ?: target,
                installations = discovery.installations,
                architecture = selected?.detection?.architecture.orEmpty(),
                version = selected?.detection?.version.orEmpty(),
            )
        }
        return state.value
    }

    // ------------------------------------------------------------------ start

    private suspend fun doStart(): OllamaServerState {
        currentCoroutineContext().ensureActive()
        val discovery = discoverInstallations()
        val selected = discovery.selected
        if (selected != null) {
            target = selected.target
            saveTarget(target)
            targetResolved = true
        }
        savedSession = readSavedSession()
        val snapshot = readSnapshot()
        val saved = snapshot.record
        val apiHealthy = probeApi()
        if (!target.isHost && !apiHealthy && savedSession?.mode == OllamaLaunchMode.PROOT_DETACHED &&
            verifySavedSession()
        ) {
            update {
                it.copy(
                    status = OllamaServerStatus.STARTING,
                    pid = saved?.pid,
                    startedByNyx = true,
                    message = "A verified PRoot session is already alive; NYX will not start a second server. Use Stop to end it or Refresh to adopt it.",
                    logTail = snapshot.logTail,
                )
            }
            return state.value
        }
        val detection = selected?.detection ?: OllamaDetection(
            installed = false,
            error = discovery.detail,
            target = target,
        )
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
                val status = ownedNotReadyStatus(snapshot.uptimeMillis)
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
                val incompatible = selected != null && !selected.detection.compatible
                val probeFailed = selected == null && detection.error.isNotBlank()
                update {
                    it.copy(
                        status = if (incompatible) {
                            OllamaServerStatus.INCOMPATIBLE
                        } else if (probeFailed) {
                            OllamaServerStatus.ERROR
                        } else {
                            OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.DETECTED_MISSING)
                        },
                        pid = null,
                        models = emptyList(),
                        startedByNyx = false,
                        message = if (probeFailed || incompatible) discovery.detail else plan.reason,
                        logTail = snapshot.logTail,
                        target = selected?.target ?: target,
                        installations = discovery.installations,
                        architecture = selected?.detection?.architecture.orEmpty(),
                        version = selected?.detection?.version.orEmpty(),
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
                target = target,
            )
        }
        return state.value
    }

    private suspend fun launch(detection: OllamaDetection, snapshot: OllamaHostSnapshot): OllamaServerState {
        val binary = detection.path
            ?: return fail("The ollama binary path could not be resolved in ${target.label}.", snapshot.logTail)
        update {
            it.copy(
                status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.START_REQUESTED),
                pid = null,
                models = emptyList(),
                startedByNyx = false,
                message = "Starting Ollama in ${target.label}…",
                logTail = "",
                rssKb = null,
                uptimeMillis = null,
                target = target,
                architecture = detection.architecture,
                version = detection.version,
            )
        }
        if (!target.isHost) return launchGuest(detection, snapshot)
        var result = host(OllamaProcess.startScript(binary, useSetsid = true), START_TIMEOUT_SECONDS)
        var outcome = OllamaProcess.parseStartOutcome(result.stdout)
        if (!outcome.started && !outcome.duplicate) {
            // PRoot may expose setsid but refuse the session call. The script's nohup branch closes
            // stdin and redirects both streams, so a retry is still detached and duplicate-safe.
            result = host(OllamaProcess.startScript(binary, useSetsid = false), START_TIMEOUT_SECONDS)
            outcome = OllamaProcess.parseStartOutcome(result.stdout)
        }
        if (outcome.duplicate) {
            // Another caller won the race. The script verified that PID, so wait for it rather than
            // spawning a second server.
            val existingPid = outcome.pid
            val existingStart = outcome.startTimeTicks
            if (existingPid != null && existingStart != null) {
                saveRecord(SavedOllamaProcess(existingPid, existingStart, System.currentTimeMillis()), startedByNyx = true)
                update { it.copy(status = OllamaServerStatus.STARTING, pid = existingPid, startedByNyx = true, message = outcome.error) }
                return awaitReady(existingPid, detection.path)
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
        savedSession = SavedOllamaSession(
            token = "host-${pid}",
            mode = OllamaLaunchMode.HOST_DETACHED,
            container = "",
        )
        saveSession(savedSession!!)
        return awaitReady(pid, binary)
    }

    /**
     * Starts a guest server in a session that outlives the Termux RUN_COMMAND call. Newer
     * proot-distro versions daemonize the whole PRoot process with `--detach`; older versions get
     * one long-lived foreground login whose child is the server. A `setsid` child inside a
     * short-lived login is never used because PRoot's `--kill-on-exit` tears it down.
     */
    private suspend fun launchGuest(detection: OllamaDetection, snapshot: OllamaHostSnapshot): OllamaServerState {
        val binary = detection.path
            ?: return fail("The ollama binary path could not be resolved in ${target.label}.", snapshot.logTail)
        val alias = target.guestAlias
            ?: return fail("The selected PRoot guest has no valid distribution alias.", snapshot.logTail)
        val prootPath = bridge.prootExecutablePath()
            ?: return fail("proot-distro is not available. Run Detect after granting Termux access.", snapshot.logTail)
        lastBinaryPath = binary
        val capabilities = ensureSessionCapabilities(prootPath)
        val before = if (capabilities.ps) readSessions(prootPath) else emptyList()
        val token = "nyx-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val script = OllamaGuestRuntime.guestStartScript(binary, token)
        var mode = OllamaLaunchMode.PROOT_FOREGROUND
        var launched: TerminalCommandResult
        if (capabilities.supportsDetachedSession) {
            val detached = bridge.runProotDetached(alias, script, timeoutSeconds = SESSION_TIMEOUT_SECONDS)
            if (detached.isSuccess) {
                mode = OllamaLaunchMode.PROOT_DETACHED
                launched = detached
            } else {
                val detail = failureDetail(detached)
                val unsupported = detail.contains("unrecognized", ignoreCase = true) ||
                    detail.contains("unknown option", ignoreCase = true) ||
                    detail.contains("invalid option", ignoreCase = true)
                if (!unsupported) {
                    return fail("The PRoot session could not be started: $detail", readSnapshot().logTail)
                }
                // The capability probe was optimistic; the foreground fallback is still safe because
                // no session was created and the start script refuses a live NYX PID record.
                launched = bridge.dispatchPersistentProot(alias, script)
            }
        } else {
            launched = bridge.dispatchPersistentProot(alias, script)
        }
        if (launched.kind != TerminalResultKind.SUCCEEDED) {
            return fail("The PRoot session could not be dispatched: ${failureDetail(launched)}", readSnapshot().logTail)
        }
        var sessionPid: Int? = null
        var sessionStart: Long? = null
        if (mode == OllamaLaunchMode.PROOT_DETACHED && capabilities.ps) {
            val after = readSessions(prootPath)
            val session = OllamaGuestRuntime.newSession(before, after, alias)
            if (session != null) {
                val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(session.pid), STATE_TIMEOUT_SECONDS)
                if (OllamaGuestRuntime.parseSessionAlive(identity.stdout)) {
                    sessionPid = session.pid
                    sessionStart = OllamaGuestRuntime.parseSessionStartTicks(identity.stdout)
                }
            }
        }
        savedSession = SavedOllamaSession(
            token = token,
            mode = mode,
            sessionPid = sessionPid,
            sessionStartTicks = sessionStart,
            container = alias,
        )
        saveSession(savedSession!!)
        update {
            it.copy(
                status = OllamaServerStatus.STARTING,
                pid = null,
                startedByNyx = true,
                message = when (mode) {
                    OllamaLaunchMode.PROOT_DETACHED -> "Started a detached PRoot session; waiting for the guest API."
                    else -> "Started one foreground PRoot session; waiting for the guest API."
                },
            )
        }
        if (mode == OllamaLaunchMode.PROOT_DETACHED && sessionPid == null && capabilities.ps) {
            val late = OllamaGuestRuntime.newSession(before, readSessions(prootPath), alias)
            if (late != null) {
                val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(late.pid), STATE_TIMEOUT_SECONDS)
                if (OllamaGuestRuntime.parseSessionAlive(identity.stdout)) {
                    sessionPid = late.pid
                    sessionStart = OllamaGuestRuntime.parseSessionStartTicks(identity.stdout)
                    savedSession = SavedOllamaSession(token, mode, sessionPid, sessionStart, alias)
                    saveSession(savedSession!!)
                }
            }
        }
        val record = waitForGuestPid()
        if (record == null) {
            val cleaned = stopOwnedSessionIfPossible()
            if (cleaned) clearSession()
            return fail(
                if (cleaned) {
                    "The PRoot session was created, but the guest Ollama PID record did not appear. " +
                        "The managed session was stopped; check the guest log."
                } else {
                    "The PRoot session was created, but the guest Ollama PID record did not appear and the session could not be stopped. " +
                        "Use Stop to retry; the session remains visible."
                },
                readSnapshot().logTail,
            )
        }
        saveRecord(record, startedByNyx = true)
        update {
            it.copy(
                status = OllamaServerStatus.STARTING,
                pid = record.pid,
                startedByNyx = true,
                message = "Started Ollama in ${target.label} with PID ${record.pid}; waiting for the API.",
            )
        }
        return awaitReady(record.pid, binary)
    }

    private suspend fun waitForGuestPid(): SavedOllamaProcess? {
        val deadline = System.currentTimeMillis() + GUEST_PID_TIMEOUT_MILLIS
        var last: OllamaHostSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            last = readSnapshot()
            val record = last.record
            if (record != null && last.ownership == OllamaOwnership.OWNED) return record
            delay(750L)
        }
        return last?.record?.takeIf { last.ownership == OllamaOwnership.OWNED }
    }

    private suspend fun ensureSessionCapabilities(prootPath: String): ProotSessionCapabilities {
        sessionCapabilities?.let { return it }
        val result = hostIsolated(OllamaGuestRuntime.sessionCapabilityScript(prootPath), STATE_TIMEOUT_SECONDS)
        val capabilities = OllamaGuestRuntime.parseSessionCapabilities(result.stdout)
        sessionCapabilities = capabilities
        return capabilities
    }

    private suspend fun readSessions(prootPath: String): List<ProotSession> {
        val result = hostIsolated(OllamaGuestRuntime.sessionListScript(prootPath), STATE_TIMEOUT_SECONDS)
        return OllamaGuestRuntime.parseSessionList(result.stdout)
    }

    private data class GuestProbe(val ready: Boolean?, val listen: String?)

    private suspend fun probeGuestApi(binary: String?): GuestProbe? {
        if (target.isHost || binary.isNullOrBlank()) return null
        return try {
            val result = execute(target, OllamaGuestRuntime.guestApiProbeScript(binary), GUEST_API_TIMEOUT_SECONDS)
            GuestProbe(
                ready = OllamaGuestRuntime.parseGuestApi(result.stdout),
                listen = OllamaGuestRuntime.parseGuestListen(result.stdout),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun awaitReady(pid: Int, binary: String? = null): OllamaServerState {
        val startedAt = System.currentTimeMillis()
        var attempts = 0
        var lastDetail = ""
        var lastSnapshot = readSnapshot()
        var lastGuestReady: Boolean? = null
        var lastGuestListen = ""
        var lastStage = OllamaStartupStage.API_WAITING
        val timeout = if (target.isHost) OllamaReadiness.DEFAULT_TIMEOUT_MILLIS else OllamaReadiness.GUEST_TIMEOUT_MILLIS
        while (true) {
            currentCoroutineContext().ensureActive()
            attempts++
            if (attempts == 1 || attempts % SNAPSHOT_EVERY_ATTEMPTS == 0) lastSnapshot = readSnapshot()
            val androidReady = probeApi()
            val processOwned = lastSnapshot.ownership == OllamaOwnership.OWNED
            val owned = processOwned || androidReady
            val guestReady = if (target.isHost) null else if (attempts == 1 || attempts % GUEST_PROBE_EVERY_ATTEMPTS == 0) {
                probeGuestApi(binary).also { probe ->
                    lastGuestReady = probe?.ready
                    lastGuestListen = probe?.listen.orEmpty()
                }?.ready
            } else {
                lastGuestReady
            }
            lastStage = OllamaGuestRuntime.startupStage(owned, guestReady, androidReady, lastSnapshot.logTail)
            if (lastStage == OllamaStartupStage.READY) {
                val models = fetchModels()
                val snapshot = lastSnapshot
                update {
                    it.copy(
                        status = OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.START_SUCCEEDED),
                        pid = if (processOwned) pid else null,
                        models = models,
                        startedByNyx = processOwned,
                        message = OllamaReadiness.successMessage(adopted = !processOwned, pid = pid.takeIf { processOwned }).let { base ->
                            val session = savedSession?.let { " Session: ${it.mode.name.lowercase().replace('_', ' ')}${it.sessionPid?.let { p -> " PID $p" } ?: ""}." }.orEmpty()
                            val noModels = if (models.isEmpty()) " No models are installed yet." else ""
                            base + session + noModels
                        },
                        logTail = snapshot.logTail,
                        rssKb = snapshot.rssKb,
                        uptimeMillis = snapshot.uptimeMillis,
                    )
                }
                return state.value
            }
            if (lastStage == OllamaStartupStage.PROCESS_GONE) break
            if (lastStage == OllamaStartupStage.PORT_CONFLICT && attempts >= 2) break
            val elapsed = System.currentTimeMillis() - startedAt
            if (OllamaReadiness.isTimedOut(elapsed, timeout)) break
            update {
                it.copy(
                    status = OllamaServerStatus.STARTING,
                    pid = if (owned) pid else null,
                    startedByNyx = owned,
                    message = OllamaGuestRuntime.stageMessage(lastStage, pid),
                    logTail = lastSnapshot.logTail.ifBlank { it.logTail },
                    rssKb = lastSnapshot.rssKb ?: it.rssKb,
                    uptimeMillis = lastSnapshot.uptimeMillis ?: it.uptimeMillis,
                )
            }
            delay(OllamaReadiness.pollIntervalMillis(attempts))
        }
        lastDetail = failureDetailFromLog().let { detail ->
            if (lastGuestListen.isBlank()) detail else "$detail Guest listener: $lastGuestListen."
        }
        val snapshot = readSnapshot()
        val alive = snapshot.ownership == OllamaOwnership.OWNED
        if (!alive) {
            clearSaved()
            stopOwnedSessionIfPossible()
            clearSession()
        }
        val message = OllamaReadiness.failureMessage(
            timeoutMillis = timeout,
            attempts = attempts,
            detail = if (lastDetail.isNotBlank()) lastDetail else "The API did not answer on $endpoint.",
            logTail = snapshot.logTail,
            stage = lastStage,
        ) + if (alive) " The live process was left running so its log and session remain inspectable; use Stop to end it." else ""
        update {
            it.copy(
                status = if (alive) OllamaServerStatus.UNREACHABLE else OllamaServerStatus.ERROR,
                pid = if (alive) pid else null,
                models = emptyList(),
                startedByNyx = alive,
                message = message,
                logTail = snapshot.logTail.ifBlank { it.logTail },
                rssKb = snapshot.rssKb,
                uptimeMillis = snapshot.uptimeMillis,
            )
        }
        return state.value
    }

    // ------------------------------------------------------------------ stop

    private suspend fun doStop(): OllamaServerState {
        savedSession = readSavedSession()
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
                        if (!stopOwnedSessionIfPossible()) {
                            return fail(
                                "Ollama stopped, but the verified PRoot session is still alive. Stop it again; NYX will not report a clean stop until the session is gone.",
                                readSnapshot().logTail,
                            )
                        }
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
                if (!stopOwnedSessionIfPossible()) {
                    return fail(
                        "A verified PRoot session is still alive and could not be stopped. Retry Stop; NYX will not report a clean stop until the session is gone.",
                        snapshot.logTail,
                    )
                }
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
            stopOwnedSessionIfPossible()
            clearSaved()
            return " The failed startup process was stopped."
        }
        return ""
    }

    // ------------------------------------------------------------------ restart

    private suspend fun doRestart(): OllamaServerState {
        savedSession = readSavedSession()
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

    private suspend fun host(script: String, timeoutSeconds: Long): TerminalCommandResult =
        execute(target, script, timeoutSeconds)

    /** Host-only commands must never be routed through [target], which may be a PRoot guest. */
    private suspend fun hostIsolated(script: String, timeoutSeconds: Long): TerminalCommandResult =
        try {
            bridge.runHostIsolated(script, timeoutSeconds)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            TerminalCommandResult(
                requestId = null,
                kind = TerminalResultKind.DISPATCH_ERROR,
                message = e.message?.take(300) ?: "The isolated host command could not be dispatched.",
            )
        }

    private suspend fun execute(
        at: OllamaTarget,
        script: String,
        timeoutSeconds: Long,
    ): TerminalCommandResult = try {
        if (at.isHost) {
            // Host-isolated on purpose: the interactive profile may `exec` into Debian.
            bridge.runHostIsolated(script, timeoutSeconds)
        } else {
            val alias = at.guestAlias
                ?: return TerminalCommandResult(
                    requestId = null,
                    kind = TerminalResultKind.INVALID_REQUEST,
                    message = "The selected PRoot guest has no valid distribution alias.",
                )
            bridge.runShell(
                script,
                environment = TerminalEnvironment.DEBIAN_PROOT,
                workingDirectory = null,
                timeoutSeconds = timeoutSeconds,
                useTimeoutBinary = false,
                approved = true,
                guestAlias = alias,
            )
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        TerminalCommandResult(
            requestId = null,
            kind = TerminalResultKind.DISPATCH_ERROR,
            message = e.message?.take(300) ?: "The Ollama command could not be dispatched.",
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

    /**
     * Probes the isolated host and every guest that environment discovery actually verified. The
     * caller's current environment is a preference, never proof that a binary exists there.
     */
    private suspend fun discoverInstallations(): OllamaDiscoveryPlan {
        val installations = mutableListOf<OllamaInstallation>()
        installations += probeInstallation(OllamaTarget.HOST)
        for (alias in guestAliases) {
            OllamaTarget.guest(alias)?.let { installations += probeInstallation(it) }
        }
        return OllamaDiscovery.choose(
            installations = installations,
            preferredEnvironment = preferredEnvironment,
            preferredGuest = preferredGuestAlias ?: target.guestAlias,
        )
    }

    private suspend fun probeInstallation(at: OllamaTarget): OllamaInstallation {
        val result = execute(at, OllamaProcess.detectScript(), DETECT_TIMEOUT_SECONDS)
        var detection = OllamaProcess.parseDetection(result.stdout, at)
        if (!detection.installed && !result.isSuccess && result.kind != TerminalResultKind.PROCESS_EXIT) {
            detection = detection.copy(error = failureDetail(result).take(300))
        }
        return OllamaInstallation(at, detection)
    }

    private suspend fun applyDiscovery(
        plan: OllamaDiscoveryPlan,
        apiHealthy: Boolean,
        snapshot: OllamaHostSnapshot? = null,
        owned: Boolean = false,
    ): OllamaServerState {
        val selected = plan.selected
        if (selected != null) {
            target = selected.target
            saveTarget(target)
            targetResolved = true
        }
        val verifiedDetachedSession = !target.isHost &&
            savedSession?.mode == OllamaLaunchMode.PROOT_DETACHED &&
            verifySavedSession()
        val status = when {
            apiHealthy -> OllamaServerStatus.RUNNING
            verifiedDetachedSession -> OllamaServerStatus.STARTING
            selected == null -> OllamaServerStatus.NOT_INSTALLED
            !selected.detection.compatible -> OllamaServerStatus.INCOMPATIBLE
            snapshot?.ownership == OllamaOwnership.OWNED ->
                if (ownedNotReadyStatus(snapshot.uptimeMillis) == OllamaServerStatus.ERROR) {
                    OllamaServerStatus.UNREACHABLE
                } else {
                    OllamaServerStatus.STARTING
                }
            else -> OllamaServerStatus.STOPPED
        }
        val models = if (apiHealthy) fetchModels() else emptyList()
        val message = when {
            apiHealthy && selected == null ->
                "An Ollama-compatible server is answering on $endpoint, but no local binary was found. It will be adopted and never stopped."
            apiHealthy -> "Ollama is running from ${selected?.target?.label ?: target.label}. " +
                if (models.isEmpty()) "No models are installed yet." else "Reconnected to ${models.size} model(s)."
            verifiedDetachedSession -> "A verified PRoot session is alive, but the guest Ollama API is not answering yet. Use Stop to end the session or Refresh after the model has loaded."
            selected == null -> plan.detail
            !selected.detection.compatible -> plan.detail
            status == OllamaServerStatus.UNREACHABLE ->
                "The NYX-started Ollama process is alive in ${selected.target.label}, but its API is not answering. Check the bounded log before restarting."
            else -> buildString {
                append("Ollama ")
                if (selected.detection.version.isNotBlank()) append(selected.detection.version).append(' ')
                append("is installed in ${selected.target.label}")
                selected.detection.path?.let { append(" at ").append(it) }
                append(". The server is not running.")
            }
        }
        update {
            it.copy(
                status = status,
                // A PID is meaningful only for the target that produced it. Never carry a host PID
                // across a target switch; an adopted API server intentionally has no stop handle.
                pid = if (owned) snapshot?.record?.pid else null,
                models = models,
                startedByNyx = owned,
                message = message,
                target = selected?.target ?: target,
                installations = plan.installations,
                architecture = selected?.detection?.architecture.orEmpty(),
                version = selected?.detection?.version.orEmpty(),
                logTail = snapshot?.logTail.orEmpty(),
                rssKb = if (owned) snapshot?.rssKb else null,
                uptimeMillis = if (owned) snapshot?.uptimeMillis else null,
            )
        }
        return state.value
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

    private fun readSavedTarget(): OllamaTarget? {
        val kind = prefs.getString(KEY_TARGET_ENV, null) ?: return null
        return when (kind) {
            "host" -> OllamaTarget.HOST
            "guest" -> prefs.getString(KEY_TARGET_GUEST, null)?.let(OllamaTarget::guest)
            else -> null
        }
    }

    private fun readSavedSession(): SavedOllamaSession? {
        val mode = prefs.getString(targetKey(KEY_SESSION_MODE), null)?.let {
            runCatching { OllamaLaunchMode.valueOf(it) }.getOrNull()
        } ?: return null
        val token = prefs.getString(targetKey(KEY_SESSION_TOKEN), null)
            ?.takeIf(OllamaGuestRuntime::isValidToken)
            ?: return null
        val pid = prefs.getInt(targetKey(KEY_SESSION_PID), 0)
            .takeIf { it in 2..OllamaGuestRuntime.MAX_SESSION_PID }
        val start = prefs.getLong(targetKey(KEY_SESSION_START), -1L).takeIf { it >= 0L }
        val container = prefs.getString(targetKey(KEY_SESSION_CONTAINER), "").orEmpty()
        return SavedOllamaSession(token, mode, pid, start, container)
    }

    private fun saveSession(session: SavedOllamaSession) {
        prefs.edit()
            .putString(targetKey(KEY_SESSION_MODE), session.mode.name)
            .putString(targetKey(KEY_SESSION_TOKEN), session.token)
            .putInt(targetKey(KEY_SESSION_PID), session.sessionPid ?: 0)
            .putLong(targetKey(KEY_SESSION_START), session.sessionStartTicks ?: -1L)
            .putString(targetKey(KEY_SESSION_CONTAINER), session.container)
            .apply()
    }

    private fun clearSession() {
        savedSession = null
        prefs.edit()
            .remove(targetKey(KEY_SESSION_MODE))
            .remove(targetKey(KEY_SESSION_TOKEN))
            .remove(targetKey(KEY_SESSION_PID))
            .remove(targetKey(KEY_SESSION_START))
            .remove(targetKey(KEY_SESSION_CONTAINER))
            .apply()
    }

    /** Kills only a detached session whose PID and kernel start token still match. */
    private suspend fun stopOwnedSessionIfPossible(): Boolean {
        val session = savedSession ?: return true
        if (session.mode != OllamaLaunchMode.PROOT_DETACHED) {
            clearSession()
            return true
        }
        val pid = session.sessionPid ?: run { clearSession(); return true }
        val expectedStart = session.sessionStartTicks ?: run { clearSession(); return true }
        val prootPath = bridge.prootExecutablePath() ?: return false
        val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(pid), STATE_TIMEOUT_SECONDS)
        if (!OllamaGuestRuntime.parseSessionAlive(identity.stdout) ||
            OllamaGuestRuntime.parseSessionStartTicks(identity.stdout) != expectedStart
        ) {
            clearSession()
            return true
        }
        hostIsolated(OllamaGuestRuntime.sessionKillScript(prootPath, pid), STOP_TIMEOUT_SECONDS)
        repeat(4) {
            delay(500L)
            val after = hostIsolated(OllamaGuestRuntime.sessionStateScript(pid), STATE_TIMEOUT_SECONDS)
            if (!OllamaGuestRuntime.parseSessionAlive(after.stdout)) {
                clearSession()
                return true
            }
        }
        return false
    }

    private suspend fun verifySavedSession(): Boolean {
        val session = savedSession ?: return false
        if (session.mode != OllamaLaunchMode.PROOT_DETACHED) return true
        val pid = session.sessionPid ?: return false
        val expectedStart = session.sessionStartTicks ?: return false
        val prootPath = bridge.prootExecutablePath() ?: return false
        val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(pid), STATE_TIMEOUT_SECONDS)
        return OllamaGuestRuntime.parseSessionAlive(identity.stdout) &&
            OllamaGuestRuntime.parseSessionStartTicks(identity.stdout) == expectedStart
    }

    private fun saveTarget(value: OllamaTarget) {
        if (!value.valid) return
        prefs.edit()
            .putString(KEY_TARGET_ENV, if (value.isHost) "host" else "guest")
            .putString(KEY_TARGET_GUEST, value.guestAlias)
            .apply()
        savedSession = readSavedSession()
    }

    private fun targetKey(base: String): String =
        "$base:${target.environment.name}:${target.guestAlias.orEmpty()}"

    private fun readStartedByNyxFlag(): Boolean {
        val scoped = prefs.getBoolean(targetKey(KEY_STARTED_BY_NYX), false)
        return scoped || (target.isHost && prefs.getBoolean(KEY_STARTED_BY_NYX, false))
    }

    /** Ownership verdicts that prove the saved record can never be signalled again. */
    private fun isDefinitelyStale(ownership: OllamaOwnership): Boolean = ownership == OllamaOwnership.MISSING ||
        ownership == OllamaOwnership.PID_REUSED ||
        ownership == OllamaOwnership.NOT_OLLAMA

    private fun currentOrErrorStatus(): OllamaServerStatus =
        if (_state.value.status == OllamaServerStatus.ERROR) OllamaServerStatus.ERROR else OllamaServerStatus.STOPPED

    private fun ownedNotReadyStatus(uptimeMillis: Long?): OllamaServerStatus =
        OllamaLifecyclePolicy.statusForOwnedButNotReady(
            uptimeMillis,
            if (target.isHost) OllamaReadiness.STARTUP_GRACE_MILLIS else OllamaReadiness.GUEST_STARTUP_GRACE_MILLIS,
        )

    private fun saveRecord(record: SavedOllamaProcess, startedByNyx: Boolean) {
        saveTarget(target)
        prefs.edit()
            .putString(targetKey(KEY_RECORD), OllamaProcess.encodeRecord(record))
            .putBoolean(targetKey(KEY_STARTED_BY_NYX), startedByNyx)
            .putLong(targetKey(KEY_STARTED_AT), record.startedAtMillis)
            .putString(KEY_ENDPOINT, endpoint)
            .apply()
    }

    private fun clearSaved() {
        prefs.edit()
            .remove(targetKey(KEY_RECORD))
            .putBoolean(targetKey(KEY_STARTED_BY_NYX), false)
            .remove(targetKey(KEY_STARTED_AT))
            .apply()
        if (target.isHost) {
            prefs.edit()
                .remove(KEY_RECORD)
                .putBoolean(KEY_STARTED_BY_NYX, false)
                .remove(KEY_STARTED_AT)
                .apply()
        }
    }

    /** Migrates a pre-v0.7.7 host record into the target-scoped key on first read. */
    private fun migrateLegacyRecordIfNeeded() {
        if (!target.isHost) return
        val scoped = targetKey(KEY_RECORD)
        if (prefs.contains(scoped)) return
        val legacy = prefs.getString(KEY_RECORD, null) ?: return
        prefs.edit()
            .putString(scoped, legacy)
            .putBoolean(targetKey(KEY_STARTED_BY_NYX), prefs.getBoolean(KEY_STARTED_BY_NYX, false))
            .putLong(targetKey(KEY_STARTED_AT), prefs.getLong(KEY_STARTED_AT, 0L))
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
            transform(current).withSessionIdentity().copy(endpoint = endpoint, lastCheckedAt = System.currentTimeMillis())
        }
    }

    private fun OllamaServerState.withSessionIdentity(): OllamaServerState {
        val session = savedSession?.takeIf {
            if (target.isHost) it.mode == OllamaLaunchMode.HOST_DETACHED
            else it.container == target.guestAlias.orEmpty()
        }
        return copy(
            sessionPid = session?.sessionPid,
            launchMode = session?.mode,
            sessionToken = session?.token.orEmpty(),
        )
    }

    companion object {
        const val PREFS_NAME = "nyx_ollama_server"
        const val KEY_RECORD = "record"
        const val KEY_STARTED_BY_NYX = "started_by_nyx"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TARGET_ENV = "target_environment"
        const val KEY_TARGET_GUEST = "target_guest"
        const val KEY_SESSION_MODE = "session_mode"
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_SESSION_PID = "session_pid"
        const val KEY_SESSION_START = "session_start_ticks"
        const val KEY_SESSION_CONTAINER = "session_container"

        private const val DETECT_TIMEOUT_SECONDS = 20L
        private const val STATE_TIMEOUT_SECONDS = 20L
        private const val START_TIMEOUT_SECONDS = 30L
        private const val STOP_TIMEOUT_SECONDS = 30L
        private const val SESSION_TIMEOUT_SECONDS = 30L
        private const val GUEST_API_TIMEOUT_SECONDS = 15L
        private const val GUEST_PID_TIMEOUT_MILLIS = 60_000L
        private const val SNAPSHOT_EVERY_ATTEMPTS = 3
        private const val GUEST_PROBE_EVERY_ATTEMPTS = 2
        private const val MAX_GUESTS = 4
    }
}

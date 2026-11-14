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
    // UI and foreground service can hold separate managers in the same app process.
    private val mutex = LIFECYCLE_MUTEX

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

    /** Read-only token from the guest log's first line, never from an API adoption. */
    private var observedGuestToken: String? = null

    // SharedPreferences can publish an edit in memory even when commit reports disk failure.
    // Such a failure cannot release ownership for any manager in this app process.
    private var ownershipStorageUncertain: Boolean
        get() = synchronized(PERSISTENT_OWNERSHIP_LOCK) { persistentStorageUncertain }
        set(value) { synchronized(PERSISTENT_OWNERSHIP_LOCK) { persistentStorageUncertain = value } }

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
            selectTarget(selected.target)
            targetResolved = true
        }
        savedSession = readSavedSession()
        val snapshot = readSnapshot()
        val owned = ownsRecord(snapshot)
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
                selectTarget(selected.target)
                targetResolved = true
            }
        }
        savedSession = readSavedSession()
        val snapshot = readSnapshot()
        val saved = snapshot.record
        val owned = ownsRecord(snapshot)
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
            selectTarget(selected.target)
            targetResolved = true
        }
        val verifiedDetachedSession = !target.isHost &&
            savedSession?.mode == OllamaLaunchMode.PROOT_DETACHED &&
            verifySavedSession()
        val status = when {
            hasGuestLaunchEvidence() -> OllamaServerStatus.ERROR
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
                message = if (hasGuestLaunchEvidence()) {
                    unresolvedLaunchMessage()
                } else if (verifiedDetachedSession) {
                    "A verified PRoot session is alive, but its guest Ollama API is not ready yet. NYX will not start a second server."
                } else {
                    discovery.detail
                },
                logTail = snapshot.logTail,
                rssKb = null,
                uptimeMillis = null,
                target = target,
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
        // Dispatch acknowledgement, missing PID, timeout or restart cannot release this fence.
        // Reconcile read-only: never select another namespace or replay a persistent launch.
        savedSession = readSavedSession()
        if (hasGuestLaunchEvidence()) {
            val existing = readSnapshot()
            val healthy = probeApi()
            if (ownsRecord(existing)) {
                existing.record?.let { saveRecord(it, startedByNyx = true) }
            }
            if (healthy) return adopt(apiHealthy = true, snapshot = existing)
            return fail(unresolvedLaunchMessage(), existing.logTail)
        }
        val discovery = discoverInstallations()
        val selected = discovery.selected
        if (selected != null) {
            selectTarget(selected.target)
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
        val owned = ownsRecord(snapshot)
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
        val result = host(OllamaProcess.startScript(binary, useSetsid = true), START_TIMEOUT_SECONDS)
        val outcome = OllamaProcess.parseStartOutcome(result.stdout)
        // Never retry an uncertain callback: wrapper completion does not prove child exit.
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
        val mode = if (capabilities.supportsDetachedSession) OllamaLaunchMode.PROOT_DETACHED
            else OllamaLaunchMode.PROOT_FOREGROUND
        val pending = SavedOllamaSession(token = token, mode = mode, container = alias)
        val launched = OllamaPersistentOwnership.dispatchAfterCheckpoint(
            checkpoint = { savePendingLaunch(pending) },
            dispatch = {
                currentCoroutineContext().ensureActive()
                if (mode == OllamaLaunchMode.PROOT_DETACHED) {
                    bridge.runProotDetached(alias, script, timeoutSeconds = SESSION_TIMEOUT_SECONDS)
                } else {
                    bridge.dispatchPersistentProot(alias, script)
                }
            },
        ) ?: return fail("The pending PRoot ownership checkpoint could not be committed; no launch was dispatched.", snapshot.logTail)
        if (launched.kind != TerminalResultKind.SUCCEEDED) {
            return fail("The PRoot dispatch was not confirmed: ${failureDetail(launched)}. ${unresolvedLaunchMessage()}", readSnapshot().logTail)
        }
        var sessionPid: Int? = null
        var sessionStart: Long? = null
        if (mode == OllamaLaunchMode.PROOT_DETACHED && capabilities.ps) {
            val after = readSessions(prootPath)
            val session = OllamaPersistentOwnership.newTokenSession(before, after, alias, token)
            if (session != null) {
                val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(session.pid), STATE_TIMEOUT_SECONDS)
                if (identity.isSuccess && OllamaPersistentOwnership.sessionMatchesToken(identity.stdout, alias, token)) {
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
            val late = OllamaPersistentOwnership.newTokenSession(before, readSessions(prootPath), alias, token)
            if (late != null) {
                val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(late.pid), STATE_TIMEOUT_SECONDS)
                if (identity.isSuccess && OllamaPersistentOwnership.sessionMatchesToken(identity.stdout, alias, token)) {
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
            if (record != null && ownsRecord(last)) return record
            delay(750L)
        }
        return last?.record?.takeIf { ownsRecord(last) }
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
            val processOwned = ownsRecord(lastSnapshot)
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
        val alive = ownsRecord(snapshot)
        if (!alive) {
            // Missing/failed observations and readiness timeouts are not shutdown evidence.
            if (!hasGuestLaunchEvidence() && isDefinitelyStale(snapshot.ownership)) clearSaved()
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
        val ownedRecord = ownsRecord(snapshot)
        if (hasGuestLaunchEvidence() && !ownedRecord) {
            if (stopOwnedSessionIfPossible()) {
                clearSaved()
            } else {
                return fail("Stop refused: guest token/PID or session termination is unverified. ${unresolvedLaunchMessage()}", snapshot.logTail)
            }
        }
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
                val stopScript = if (target.isHost) OllamaProcess.stopScript(record) else {
                    val session = savedSession ?: return fail("Stop refused: the guest launch token is missing.", snapshot.logTail)
                    OllamaPersistentOwnership.guestStopScript(record, session.token)
                }
                val result = host(stopScript, STOP_TIMEOUT_SECONDS)
                val outcome = OllamaProcess.parseStopOutcome(result.stdout)
                when {
                    result.isSuccess && outcome.stopped -> {
                        // A completion marker (including failed KILL) alone does not prove exit.
                        val ended = host(OllamaPersistentOwnership.processEndedScript(record), STATE_TIMEOUT_SECONDS)
                        if (!ended.isSuccess || OllamaProcess.markerValue(ended.stdout, "NYX_OWNED_PROCESS_ENDED") != "1") {
                            return fail("Stop remains unverified: the recorded process has not been independently observed to end.", snapshot.logTail)
                        }
                        if (!target.isHost) {
                            if (!recordGuestStopped(savedSession?.token)) {
                                return fail("Stop remains unverified: shutdown evidence could not be committed.", snapshot.logTail)
                            }
                        }
                        if (!stopOwnedSessionIfPossible()) {
                            return fail(
                                "The guest process ended, but PRoot session termination is unverified. Ownership evidence was retained; no session-tree signal or relaunch is allowed.",
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
                        if (hasGuestLaunchEvidence()) {
                            return fail("Stop refused: ${outcome.reason.ifBlank { "guest ownership could not be verified" }}. Ownership evidence was retained.", snapshot.logTail)
                        }
                        clearSaved()
                        val apiStillUp = probeApi()
                        val failedLogTail = readSnapshot().logTail
                        update {
                            it.copy(
                                status = if (apiStillUp) {
                                    OllamaLifecyclePolicy.nextState(it.status, OllamaLifecycleEvent.STOP_REFUSED)
                                } else {
                                    OllamaServerStatus.ERROR
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
                        "PRoot session termination is unverified. Ownership evidence was retained; NYX will not claim a clean stop or replay a launch.",
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

    // ------------------------------------------------------------------ restart

    private suspend fun doRestart(): OllamaServerState {
        savedSession = readSavedSession()
        val snapshot = readSnapshot()
        val saved = snapshot.record
        val owned = ownsRecord(snapshot)
        if (hasGuestLaunchEvidence()) {
            doStop()
            if (hasGuestLaunchEvidence() || state.value.status == OllamaServerStatus.ERROR) return state.value
            return doStart()
        }
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
        val tokenProbe = if (target.isHost) "" else "\nsed -n '1s/^NYX_SESSION_TOKEN=/NYX_GUEST_OWNER_TOKEN=/p' \"\${HOME:?}/.nyx/ollama.log\" 2>/dev/null\nexit 0"
        val result = host(OllamaProcess.stateScript().removeSuffix("exit 0") + tokenProbe + "\nexit 0", STATE_TIMEOUT_SECONDS)
        observedGuestToken = if (result.isSuccess) OllamaProcess.markerValue(result.stdout, "NYX_GUEST_OWNER_TOKEN") else null
        val observation = OllamaProcess.parseHostSnapshot(result.stdout)
        val parsed = if (!result.isSuccess ||
            OllamaProcess.markerValue(result.stdout, "NYX_OLLAMA_ALIVE") !in setOf("0", "1")
        ) observation.copy(ownership = OllamaOwnership.UNKNOWN) else observation
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
            selectTarget(selected.target)
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
                target = target,
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
        // The pending checkpoint selects the namespace before dispatch, not after acknowledgement.
        guestEvidenceAliases().singleOrNull()?.let { alias ->
            OllamaTarget.guest(alias)?.let { return it }
        }
        val kind = prefs.getString(KEY_TARGET_ENV, null) ?: return null
        return when (kind) {
            "host" -> OllamaTarget.HOST
            "guest" -> prefs.getString(KEY_TARGET_GUEST, null)?.let(OllamaTarget::guest)
            else -> null
        }
    }

    private fun readSavedSession(): SavedOllamaSession? {
        // A manager may have been constructed before another manager committed the fence.
        guestEvidenceAliases().singleOrNull()?.let { alias ->
            OllamaTarget.guest(alias)?.let { ownedTarget -> target = ownedTarget }
        }
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

    private fun guestEvidenceAliases(): List<String> {
        return OllamaPersistentOwnership.evidenceAliases(
            prefs.all.keys, prefs.contains(KEY_GUEST_LAUNCH_FENCE),
            prefs.getString(KEY_GUEST_LAUNCH_FENCE, null),
        )
    }

    private fun hasGuestLaunchEvidence(): Boolean = ownershipStorageUncertain || guestEvidenceAliases().isNotEmpty()

    private fun unresolvedLaunchMessage(): String =
        "Persistent PRoot ownership is unresolved. The saved token/namespace is retained; NYX will not replay a launch or signal an adopted server. Refresh is read-only; Stop requires verified guest and session termination."

    private fun ownsRecord(snapshot: OllamaHostSnapshot): Boolean = if (ownershipStorageUncertain ||
        (target.isHost && hasGuestLaunchEvidence())
    ) false else if (target.isHost) {
        snapshot.ownership == OllamaOwnership.OWNED && readStartedByNyxFlag() &&
            OllamaProcess.isOllamaServeText(snapshot.cmdline)
    } else {
        guestEvidenceAliases().singleOrNull() == target.guestAlias &&
            OllamaPersistentOwnership.verifiesGuest(savedSession, target.guestAlias, snapshot, observedGuestToken)
    }

    private fun selectTarget(selected: OllamaTarget) {
        if (hasGuestLaunchEvidence()) return
        target = selected
        saveTarget(target)
    }

    private fun savePendingLaunch(session: SavedOllamaSession): Boolean = synchronized(PERSISTENT_OWNERSHIP_LOCK) {
        if (hasGuestLaunchEvidence() || target.isHost || session.container != target.guestAlias) return@synchronized false
        // Even a failed commit retains in-memory uncertainty. Do not dispatch unless commit succeeds.
        savedSession = session
        val committed = prefs.edit()
            .putString(KEY_GUEST_LAUNCH_FENCE, session.container)
            .putString(KEY_TARGET_ENV, "guest")
            .putString(KEY_TARGET_GUEST, session.container)
            .putString(targetKey(KEY_SESSION_MODE), session.mode.name)
            .putString(targetKey(KEY_SESSION_TOKEN), session.token)
            .putInt(targetKey(KEY_SESSION_PID), 0)
            .putLong(targetKey(KEY_SESSION_START), -1L)
            .putString(targetKey(KEY_SESSION_CONTAINER), session.container)
            .putBoolean(targetKey(KEY_GUEST_PROCESS_STOPPED), false)
            .commit()
        if (!committed) ownershipStorageUncertain = true
        committed
    }

    private fun saveSession(session: SavedOllamaSession) = synchronized(PERSISTENT_OWNERSHIP_LOCK) {
        if (!target.isHost) {
            check(prefs.getString(targetKey(KEY_SESSION_TOKEN), null) == session.token) {
                "The pending PRoot token changed; refusing a stale identity update."
            }
        }
        val committed = prefs.edit()
            .putString(targetKey(KEY_SESSION_MODE), session.mode.name)
            .putString(targetKey(KEY_SESSION_TOKEN), session.token)
            .putInt(targetKey(KEY_SESSION_PID), session.sessionPid ?: 0)
            .putLong(targetKey(KEY_SESSION_START), session.sessionStartTicks ?: -1L)
            .putString(targetKey(KEY_SESSION_CONTAINER), session.container)
            .commit()
        if (!committed) ownershipStorageUncertain = true
        check(committed) { "PRoot session identity could not be committed; pending ownership was retained." }
    }

    private fun recordGuestStopped(token: String?): Boolean = synchronized(PERSISTENT_OWNERSHIP_LOCK) {
        if (token == null || prefs.getString(targetKey(KEY_SESSION_TOKEN), null) != token) return@synchronized false
        val committed = prefs.edit().putBoolean(targetKey(KEY_GUEST_PROCESS_STOPPED), true).commit()
        if (!committed) ownershipStorageUncertain = true
        committed
    }

    private fun clearSession(): Boolean = synchronized(PERSISTENT_OWNERSHIP_LOCK) {
        if (ownershipStorageUncertain) return@synchronized false
        if (!target.isHost && prefs.getString(targetKey(KEY_SESSION_TOKEN), null) != savedSession?.token) return@synchronized false
        val editor = prefs.edit()
            .remove(targetKey(KEY_SESSION_MODE))
            .remove(targetKey(KEY_SESSION_TOKEN))
            .remove(targetKey(KEY_SESSION_PID))
            .remove(targetKey(KEY_SESSION_START))
            .remove(targetKey(KEY_SESSION_CONTAINER))
            .remove(targetKey(KEY_GUEST_PROCESS_STOPPED))
        if (!target.isHost && prefs.getString(KEY_GUEST_LAUNCH_FENCE, null) == target.guestAlias) {
            editor.remove(KEY_GUEST_LAUNCH_FENCE)
        }
        val committed = editor.commit()
        if (committed) savedSession = null
        else ownershipStorageUncertain = true
        committed
    }

    /**
     * No PRoot tree signal is sent: proot-distro kill accepts a bare PID and has no atomic
     * token/start-time guard. A separate preflight cannot make that side effect identity-safe.
     * Unknown identity, failed probes and a live/reused session all refuse within one probe.
     */
    private suspend fun stopOwnedSessionIfPossible(): Boolean {
        val session = savedSession ?: return !hasGuestLaunchEvidence()
        if (target.isHost) return clearSession()
        val pid = session.sessionPid ?: return false
        if (session.sessionStartTicks == null || session.mode != OllamaLaunchMode.PROOT_DETACHED) return false
        val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(pid), STATE_TIMEOUT_SECONDS)
        return OllamaPersistentOwnership.canReleaseSession(
            session, target.guestAlias, prefs.getBoolean(targetKey(KEY_GUEST_PROCESS_STOPPED), false),
            identity.isSuccess, identity.stdout,
        ) && clearSession()
    }

    private suspend fun verifySavedSession(): Boolean {
        val session = savedSession ?: return false
        if (session.mode != OllamaLaunchMode.PROOT_DETACHED) return false
        val pid = session.sessionPid ?: return false
        val expectedStart = session.sessionStartTicks ?: return false
        val identity = hostIsolated(OllamaGuestRuntime.sessionStateScript(pid), STATE_TIMEOUT_SECONDS)
        return identity.isSuccess &&
            OllamaPersistentOwnership.sessionMatchesToken(identity.stdout, session.container, session.token) &&
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
            // An empty/failed probe cannot turn retained external evidence into a stopped state.
            status = if (hasGuestLaunchEvidence() && status in setOf(OllamaServerStatus.STOPPED, OllamaServerStatus.NOT_INSTALLED)) {
                OllamaServerStatus.ERROR
            } else status,
            message = if (hasGuestLaunchEvidence() && status in setOf(OllamaServerStatus.STOPPED, OllamaServerStatus.NOT_INSTALLED)) {
                unresolvedLaunchMessage()
            } else message,
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
        const val KEY_GUEST_LAUNCH_FENCE = "guest_launch_fence"
        const val KEY_GUEST_PROCESS_STOPPED = "guest_process_stopped"

        private val LIFECYCLE_MUTEX = Mutex()
        private val PERSISTENT_OWNERSHIP_LOCK = Any()
        private var persistentStorageUncertain = false
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

/** Pure manager-owned seams; a durable pending identity is never a process-success claim. */
internal object OllamaPersistentOwnership {
    suspend fun <T : Any> dispatchAfterCheckpoint(checkpoint: () -> Boolean, dispatch: suspend () -> T): T? {
        if (!checkpoint()) return null
        return dispatch()
    }

    fun evidenceAliases(keys: Set<String>, fencePresent: Boolean, fenceAlias: String?): List<String> {
        val aliases = keys.asSequence()
            .filter { it.startsWith("session_mode:DEBIAN_PROOT:") || it.startsWith("session_token:DEBIAN_PROOT:") }
            .map { it.substringAfterLast(':') }.toMutableSet()
        // Corrupt/partial fence data is still evidence. An empty alias fails closed.
        if (fencePresent) aliases += fenceAlias.orEmpty()
        return aliases.toList()
    }

    fun verifiesGuest(session: SavedOllamaSession?, alias: String?, snapshot: OllamaHostSnapshot, observedToken: String?): Boolean =
        session != null && session.mode != OllamaLaunchMode.HOST_DETACHED &&
            session.container == alias && OllamaTarget.guest(session.container) != null &&
            OllamaGuestRuntime.isValidToken(session.token) && session.token == observedToken &&
            snapshot.record != null && snapshot.ownership == OllamaOwnership.OWNED &&
            OllamaProcess.isOllamaServeText(snapshot.cmdline)

    private fun containsWord(command: String, word: String): Boolean =
        Regex("(^|[^A-Za-z0-9._-])${Regex.escape(word)}($|[^A-Za-z0-9._-])").containsMatchIn(command)

    fun sessionMatchesToken(stdout: String, alias: String, token: String): Boolean {
        if (!OllamaGuestRuntime.isValidToken(token) || OllamaTarget.guest(alias) == null) return false
        val command = OllamaGuestRuntime.parseSessionCmdline(stdout)
        return OllamaGuestRuntime.parseSessionAlive(stdout) &&
            OllamaGuestRuntime.parseSessionStartTicks(stdout) != null &&
            containsWord(command, token) && containsWord(command, alias) && "proot" in command
    }

    fun newTokenSession(before: List<ProotSession>, after: List<ProotSession>, alias: String, token: String): ProotSession? {
        if (!OllamaGuestRuntime.isValidToken(token)) return null
        val previous = before.map { it.pid }.toSet()
        // Multiple candidates are ambiguity, not a reason to pick the largest PID.
        return after.filter { it.pid !in previous && it.container == alias && it.detached &&
            containsWord(it.command, token) }.singleOrNull()
    }

    fun canReleaseSession(session: SavedOllamaSession, alias: String?, guestStopped: Boolean, probeSucceeded: Boolean, stdout: String): Boolean =
        guestStopped && probeSucceeded && session.mode == OllamaLaunchMode.PROOT_DETACHED &&
            session.container == alias && OllamaTarget.guest(session.container) != null &&
            OllamaGuestRuntime.isValidToken(session.token) &&
            session.sessionPid?.let { it in 2..OllamaGuestRuntime.MAX_SESSION_PID } == true &&
            session.sessionStartTicks?.let { it >= 0L } == true &&
            OllamaProcess.markerValue(stdout, "NYX_SESSION_ALIVE") == "0"

    fun guestStopScript(record: SavedOllamaProcess, token: String): String {
        require(OllamaGuestRuntime.isValidToken(token)) { "Missing guest launch token" }
        // Re-check the token and exact PID/start record in the same command as the signal.
        // The existing stop script independently rechecks kernel start ticks before TERM/KILL.
        return """
            export LC_ALL=C
            expected_token=${OllamaProcess.quote(token)}
            owner_token=${'$'}(sed -n '1s/^NYX_SESSION_TOKEN=//p' "${'$'}{HOME:?}/.nyx/ollama.log" 2>/dev/null)
            owner_record=${'$'}(sed -n '1p' "${'$'}{HOME:?}/.nyx/ollama.pid" 2>/dev/null)
            set -- ${'$'}owner_record
            if [ "${'$'}owner_token" != "${'$'}expected_token" ] || [ "${'$'}1" != NYX1 ] || [ "${'$'}2" != '${record.pid}' ] || [ "${'$'}3" != '${record.startTimeTicks}' ]; then
              printf 'NYX_OLLAMA_STOP_REFUSED=1\nNYX_OLLAMA_STOP_REASON=guest launch token or record mismatch\n'
              exit 4
            fi
            if [ -d '/proc/${record.pid}' ]; then
              owner_exe=${'$'}(tr '\0' '\n' < '/proc/${record.pid}/cmdline' 2>/dev/null | sed -n '1p')
              owner_arg=${'$'}(tr '\0' '\n' < '/proc/${record.pid}/cmdline' 2>/dev/null | sed -n '2p')
              if [ "${'$'}{owner_exe##*/}" != ollama ] || [ "${'$'}owner_arg" != serve ]; then
                printf 'NYX_OLLAMA_STOP_REFUSED=1\nNYX_OLLAMA_STOP_REASON=guest PID is not the exact server command\n'
                exit 5
              fi
            fi
        """.trimIndent() + "\n" + OllamaProcess.stopScript(record)
    }

    fun processEndedScript(record: SavedOllamaProcess): String {
        require(record.pid in 2..OllamaProcess.MAX_PID && record.startTimeTicks >= 0L)
        return """
            export LC_ALL=C
            if [ ! -d '/proc/${record.pid}' ]; then
              printf 'NYX_OWNED_PROCESS_ENDED=1\n'
            else
              observed_start=${'$'}(sed 's/^.*) //' '/proc/${record.pid}/stat' 2>/dev/null | awk '{print ${'$'}20}')
              case "${'$'}observed_start" in
                ''|*[!0-9]*) printf 'NYX_OWNED_PROCESS_ENDED=0\n' ;;
                '${record.startTimeTicks}') printf 'NYX_OWNED_PROCESS_ENDED=0\n' ;;
                *) printf 'NYX_OWNED_PROCESS_ENDED=1\n' ;;
              esac
            fi
            exit 0
        """.trimIndent()
    }
}

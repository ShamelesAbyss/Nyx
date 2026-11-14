package com.nyx.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.nyx.ai.MainActivity
import com.nyx.ai.R
import com.nyx.ai.autonomy.DeviceResourceReader
import com.nyx.ai.autonomy.ExecutionControl
import com.nyx.ai.autonomy.ExecutionMonitor
import com.nyx.ai.autonomy.ExecutionAllowancePolicy
import com.nyx.ai.autonomy.ExecutionAllowanceReservation
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ExecutionTimeoutPolicyStore
import com.nyx.ai.autonomy.InferenceSession
import com.nyx.ai.autonomy.InferenceSessionFactory
import com.nyx.ai.autonomy.ObjectiveExecutionEngine
import com.nyx.ai.autonomy.WorkerActivityBus
import com.nyx.ai.autonomy.WorkerActivityKind
import com.nyx.ai.agent.NetworkAgentTools
import com.nyx.ai.autonomy.policy.AutonomyRuntime
import com.nyx.ai.autonomy.PauseRequested
import com.nyx.ai.autonomy.ResourcePolicy
import com.nyx.ai.context.ContextStore
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.settings.CredentialVault
import com.nyx.ai.network.InferenceFabric
import com.nyx.ai.nativeinference.NativeRuntime
import com.nyx.ai.settings.MemoryTelemetry
import com.nyx.ai.data.ObjectiveEventEntity
import com.nyx.ai.settings.InferenceClientFactory
import com.nyx.ai.terminal.TerminalAgentTools
import com.nyx.ai.terminal.TerminalBridge
import com.nyx.ai.terminal.TerminalEnvironment
import com.nyx.ai.terminal.AutonomyModelCommandGate
import com.nyx.ai.terminal.BootstrapRequest
import com.nyx.ai.terminal.BootstrapStatus
import com.nyx.ai.terminal.DependencyBootstrap
import com.nyx.ai.terminal.EnvironmentConsistency
import com.nyx.ai.terminal.TerminalDependencyGateway
import com.nyx.ai.terminal.TerminalRepository
import com.nyx.ai.autonomy.policy.ActionKind
import com.nyx.ai.autonomy.policy.ActionRequest
import com.nyx.ai.development.DevelopmentPaths
import com.nyx.ai.workbench.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.nyx.ai.terminal.EnvironmentDiscovery
import org.json.JSONObject
import java.util.UUID

/**
 * Foreground service that owns objective execution. The ViewModel only starts, pauses, cancels, and
 * observes Room; it never owns the running coroutine.
 *
 * Android restricts background work, so this uses an honestly declared `specialUse` foreground service (requires Play review) with a visible
 * notification and no claim of unlimited background execution. If the process is killed, durable
 * checkpoints are recovered conservatively and uncertain tasks require review.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ObjectiveExecutionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: NyxDatabase
    private lateinit var contextStore: ContextStore
    private lateinit var bridge: TerminalBridge
    private lateinit var sessions: InferenceSessionFactory
    private var terminalTools: TerminalAgentTools? = null

    private class ObjectiveRun(val objectiveId: String) {
        val ownerId = UUID.randomUUID().toString()
        val runId = "run-${UUID.randomUUID()}"
        val control = ExecutionControl()
        lateinit var job: Job
        @Volatile var cancelRequested = false
        @Volatile var leaseAcquired = false
        @Volatile var leaseLost = false
        @Volatile var title = "Objective"
    }
    private val operations = ServiceOperationSlot<ObjectiveRun>()

    override fun onCreate() {
        super.onCreate()
        com.nyx.ai.autonomy.WorkerActivityBus.initialize(this)
        database = NyxDatabase.getInstance(this)
        contextStore = ContextStore(java.io.File(filesDir, "context"))
        bridge = TerminalBridge(this)
        val factory = InferenceClientFactory(database, CredentialVault(this), this)
        sessions = InferenceSessionFactory { objective, agent ->
            val config = com.nyx.ai.workbench.WorkbenchExecutionConfiguration.latest(database.objectiveDao().observeEvents(objective.id).first()
                .filter { it.kind == "execution_config" }.map { JSONObject(requireNotNull(it.detail)) })
                ?: error("Legacy objective has no provider snapshot. Review it and select a provider before resuming.")
            val endpoint = database.modelEndpointDao().getById(config.getString("endpointId"))
                ?: error("The objective's snapshotted provider was removed.")
            val handle = factory.providerForRecord(endpoint.copy(modelId = config.getString("modelId"), configurationJson = config.optJSONObject("options")?.toString()))
            val timeouts = ExecutionTimeoutPolicyStore.fromSnapshot(config)
            InferenceSession(handle.provider, config.getString("modelId"), handle.options.copy(executionTimeouts = timeouts), timeouts)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Do not let an IO finalizer consume this start ID between attachment and admission.
        synchronized(operations) {
        operations.attachStart(startId)
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_PAUSE -> {
                val run = operations.current() ?: run { stopSelf(startId); return START_NOT_STICKY }
                run.control.requestPause()
                run.job.cancel(PauseRequested())
                bridge.cancelAll()
                updateNotification("Pausing after the current step…")
            }
            ACTION_CANCEL -> {
                val run = operations.current() ?: run { stopSelf(startId); return START_NOT_STICKY }
                run.cancelRequested = true
                run.control.requestCancel()
                run.job.cancel(CancellationException("Cancelled by user"))
                bridge.cancelAll()
                updateNotification("Cancelling…")
            }
            ACTION_START -> {
                val objectiveId = intent?.getStringExtra(EXTRA_OBJECTIVE_ID)
                if (objectiveId.isNullOrBlank()) {
                    if (operations.current() == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (operations.current() != null) {
                    updateNotification("An objective is running or checkpointing. Retry after it finishes.")
                    return START_NOT_STICKY
                }
                AutonomyRuntime.initialize(this)
                if (AutonomyRuntime.policy().stopped) {
                    updateNotification("Emergency stop is active. Resume autonomy before starting work.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                try {
                    startForegroundSafely("Starting objective…")
                    launchObjective(objectiveId)
                } catch (failure: Exception) {
                    ExecutionMonitor.finish("Foreground-service startup rejected: ${failure.javaClass.simpleName}")
                    scope.launch {
                        // No lease was acquired: a rejected contender must not rewrite another owner.
                        recordEvent(objectiveId, "foreground_start_rejected", "Android refused foreground-service start: ${failure.javaClass.simpleName}")
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
        }
    }

    private fun launchObjective(objectiveId: String) {
        val run = ObjectiveRun(objectiveId)
        check(operations.admit(run)) { "The previous objective is still checkpointing" }
        val executionControl = run.control
        ExecutionMonitor.begin(objectiveId)
        run.job = scope.launch(start = CoroutineStart.ATOMIC) {
            val before = InferenceFabric.metrics.value
            val began = System.currentTimeMillis()
            var sampledPeakPssKb = 0
            val sampler = scope.launch {
                while (isActive) {
                    sampledPeakPssKb = maxOf(sampledPeakPssKb, MemoryTelemetry.snapshot(this@ObjectiveExecutionService).processPssKb)
                    delay(5_000)
                }
            }
            val watchdog = scope.launch {
                delay(MAX_RUN_MILLIS)
                executionControl.requestPause()
                run.job.cancel(PauseRequested())
                bridge.cancelAll()
            }
            var finalStatus: String? = null
            try {
                finalStatus = execute(run)
            } catch (pause: PauseRequested) {
                finalStatus = "paused"
            } catch (cancel: CancellationException) {
                finalStatus = if (run.cancelRequested) "cancelled" else "paused"
            } catch (e: Exception) {
                finalStatus = "needs_attention"
                withContext(NonCancellable) { markNeedsAttention(run, e.message ?: "Execution failed") }
            } finally {
                watchdog.cancel(); sampler.cancel()
                var checkpointSaved = false
                try { withContext(NonCancellable) {
                    watchdog.join(); sampler.join()
                    checkpointInterrupted(run, finalStatus ?: "paused")
                    checkpointSaved = true
                    val after = InferenceFabric.metrics.value
                    val tasks = database.objectiveDao().getTasks(objectiveId)
                    val metrics = JSONObject().put("status", finalStatus).put("elapsedMillis", System.currentTimeMillis() - began)
                        .put("inferenceCalls", after.calls - before.calls).put("generatedCharacters", after.generatedCharacters - before.generatedCharacters)
                        .put("sampledPeakPssKb", sampledPeakPssKb).put("sampleIntervalMillis", 5000)
                        .put("completedTasks", tasks.count { it.status == "completed" }).put("plannedTasks", tasks.size)
                        .put("retryCount", tasks.sumOf { (it.attemptCount - 1).coerceAtLeast(0) })
                        .put("verifiedTasks", tasks.count { it.verificationStatus == "passed" })
                    database.objectiveDao().appendEvent(ObjectiveEventEntity(UUID.randomUUID().toString(), objectiveId, null, "execution_metrics", metrics.toString(), System.currentTimeMillis()))
                    if (finalStatus != "completed") NativeRuntime.unload()
                } } finally {
                    // Cleanup failures must not strand the service; persisted ownership stays uncertain.
                    try {
                        withContext(NonCancellable) {
                            if (run.leaseAcquired && checkpointSaved) database.objectiveDao().releaseLease(objectiveId, run.ownerId)
                        }
                    } finally {
                        operations.finish(run) { latestStartId ->
                            ExecutionMonitor.finish(finalStatus)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(latestStartId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun execute(run: ObjectiveRun): String {
        val objectiveId = run.objectiveId
        val executionControl = run.control
        suspend fun recordEvent(id: String, kind: String, detail: String) = recordEvent(id, kind, detail, run.runId)
        suspend fun markNeedsAttention(id: String, message: String) { check(id == objectiveId); markNeedsAttention(run, message) }
        val dao = database.objectiveDao()
        val objective = dao.getObjective(objectiveId) ?: return "missing"
        val agent = objective.agentId?.let { database.agentDao().getById(it) } ?: return "missing_agent"
        run.title = objective.title.take(80)
        val now = System.currentTimeMillis()
        if (dao.tryAcquireLease(objectiveId, run.ownerId, now, now + LEASE_MILLIS) == 0) {
            return "busy"
        }
        run.leaseAcquired = true
        val heartbeat = scope.launch {
            try { while (isActive) {
                delay(LEASE_MILLIS / 3)
                val renewed = database.withTransaction {
                    if (!ownsLease(run, dao.getObjective(objectiveId)?.leaseOwner)) 0
                    else dao.tryAcquireLease(objectiveId, run.ownerId, System.currentTimeMillis(), System.currentTimeMillis() + LEASE_MILLIS)
                }
                if (renewed == 0) {
                    run.leaseLost = true
                    executionControl.requestPause()
                    run.job.cancel(PauseRequested())
                    return@launch
                }
                val pressure = DeviceResourceReader.read(this@ObjectiveExecutionService)
                if (pressure.lowMemory || pressure.thermalStatus >= 4 || (pressure.batteryPercent < 8 && !pressure.charging)) {
                    executionControl.requestPause()
                    run.job.cancel(PauseRequested())
                    return@launch
                }
                operations.withOwner(run) { updateNotification("Running") }
            } } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                run.leaseLost = true
                executionControl.requestPause()
                run.job.cancel(PauseRequested())
            }
        }
        var reservation: ExecutionAllowanceReservation? = null
        var allowanceStartedNanos = 0L
        var segmentWatchdog: Job? = null
        var allowanceSequence = 0L
        return try {
            val events = dao.observeEvents(objectiveId).first()
            val snapshot = com.nyx.ai.workbench.WorkbenchExecutionConfiguration.latest(events.filter { it.kind == "execution_config" }
                .map { JSONObject(requireNotNull(it.detail)) })
                ?: error("No approved execution settings for this objective.")
            val timeouts = ExecutionTimeoutPolicyStore.fromSnapshot(snapshot)
            val workbenchApproval = WorkbenchAuthorization.fromSnapshot(snapshot)
            val workbenchStore = if (workbenchApproval.enabled) WorkbenchStore(java.io.File(filesDir, "workbench"), objectiveId) else null
            val researchEngine = workbenchStore?.takeIf { workbenchApproval.researchEnabled }?.let { store ->
                ResearchEngine(store, workbenchApproval.authorizedUrls.toSet()) { AutonomyRuntime.mode() }
            }
            val workbenchTools = workbenchStore?.let { store ->
                WorkbenchAgentTools(store, objectiveId, workbenchApproval, research = researchEngine?.let { researcher ->
                    { url ->
                        val result = researcher.research(listOf(url))
                        WorkbenchResearchObservation(result.pages.isNotEmpty(), result.asUntrustedBlock(24_000))
                    }
                })
            }
            // Room orders general events by wall clock/UUID. A dedicated sequence prevents same-ms
            // ties or clock adjustment from selecting an older reservation and restoring spent time.
            val checkpoint = events.filter { it.kind == "timeout_allowance" }.map {
                JSONObject(requireNotNull(it.detail)).also { json -> require(json.getLong("sequence") > 0) }
            }.maxByOrNull { it.getLong("sequence") }
            val consumed = checkpoint?.getLong("consumedMillis") ?: 0L
            allowanceSequence = (checkpoint?.getLong("sequence") ?: 0L).also { require(it < Long.MAX_VALUE - 1) } + 1
            val segment = ExecutionAllowancePolicy.reserve(timeouts, consumed)
            if (segment == null) {
                markNeedsAttention(objectiveId, "The finite ${timeouts.overallAllowanceMs / 60_000}min checkpointed allowance is exhausted. Review the work and create a new explicitly approved objective; no automatic continuation.")
                recordEvent(objectiveId, "timeout_allowance_exhausted", "Checkpointed overall allowance exhausted; no tool or inference request was started.")
                return "needs_attention"
            }
            // Reserve durably before dispatch. A crash consumes this segment conservatively rather than resetting the allowance.
            recordEvent(objectiveId, "timeout_allowance", JSONObject().put("consumedMillis", segment.reservedConsumedMs)
                .put("sequence", allowanceSequence).put("reservedMillis", segment.segmentMs).put("runId", run.runId).put("state", "reserved").toString())
            reservation = segment
            allowanceStartedNanos = System.nanoTime()
            recordEvent(objectiveId, "timeout_profile", "${timeouts.profile.name.lowercase()} profile; ${segment.segmentMs / 60_000}min finite service segment, ${(timeouts.overallAllowanceMs - consumed) / 60_000}min remaining checkpointed allowance. Explicit resume/review is required after pause; this is not an unlimited background entitlement.")
            segmentWatchdog = scope.launch {
                delay(segment.segmentMs)
                recordEvent(objectiveId, "timeout_segment", "Finite service segment ended; checkpointing and pausing for explicit review. Uncertain side effects will not be replayed automatically.")
                executionControl.requestPause()
                run.job.cancel(PauseRequested())
                bridge.cancelAll()
            }
            val tasks = dao.getTasks(objectiveId)
            val environment = runCatching { TerminalEnvironment.valueOf(snapshot.optString("environment", "TERMUX_HOST")) }
                .getOrDefault(TerminalEnvironment.TERMUX_HOST)
            val guestAlias = snapshot.optString("guestAlias", "debian")
            val workspace = snapshot.optString("workspace")
            val environmentId = if (environment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:$guestAlias"
            terminalTools = null
            val combined = workbenchApproval.combinedDevelopment
            val outputApproval = combined?.terminalApproval(objectiveId)
            if (combined != null) {
                require(terminalEnabled() && snapshot.getBoolean("terminalEnabled")) { "Combined development terminal approval is revoked; no execution started." }
                require(workspace == combined.workspaceRoot && environment == combined.environment && guestAlias == combined.guestAlias) {
                    "Saved combined project scope mismatch; create a separately approved objective rather than rebinding."
                }
            }
            if ((!workbenchApproval.enabled || combined != null) && terminalEnabled() && snapshot.optBoolean("terminalEnabled")) {
                val environmentProfile = EnvironmentDiscovery(bridge).discover()
                require(environmentProfile.hostAvailable) { environmentProfile.detail ?: "Termux setup is incomplete." }
                require(com.nyx.ai.terminal.HostShell.isSafeAlias(guestAlias)) { "The selected PRoot distribution alias is invalid." }
                val environmentState = EnvironmentConsistency.evaluate(
                    profile = environmentProfile,
                    environment = environment,
                    guestAlias = guestAlias,
                    previousError = objective.error,
                )
                if (!environmentState.usable) {
                    markNeedsAttention(objectiveId, environmentState.message)
                    recordEvent(objectiveId, "environment_unavailable", environmentState.message)
                    return "needs_attention"
                }
                if (environmentState.stalePreviousError) {
                    recordEvent(objectiveId, "environment_refreshed", "Current environment probe succeeded; the historical error was stale.")
                }
                require(DevelopmentPaths.isAbsoluteNormalizedWorkspace(workspace)) { "Select an absolute normalized authorized workspace." }
                terminalTools = TerminalAgentTools(
                    bridge, workspace, environment,
                    timeoutSeconds = (timeouts.terminalMs / 1_000).coerceAtLeast(1),
                    useTimeoutBinary = environmentProfile.timeoutAvailable,
                    workspaceApproved = true,
                    guestAlias = guestAlias,
                    modelCommandGate = if (combined != null) null else AutonomyModelCommandGate(this, environmentId, workspace, environment,
                        maximumTimeoutSeconds = (timeouts.terminalMs / 1_000).coerceAtLeast(1)),
                    executionTimeouts = timeouts,
                    outputApproval = outputApproval,
                )
            }
            val beforeTasksExecute: suspend (List<com.nyx.ai.data.ObjectiveTaskEntity>) -> Boolean = { plannedTasks ->
                if (terminalTools == null) {
                    true
                } else if (combined != null) {
                    // This approval permits existing fixed tools, never dependency installation.
                    val inspected = requireNotNull(terminalTools).inspectProject()
                    if (!inspected.ready) markNeedsAttention(objectiveId, "Combined fixed-tool preflight blocked: ${inspected.reason}; ${inspected.missingTools.joinToString()}. No installation attempted.")
                    inspected.ready
                } else {
                    val dependencyTasks = plannedTasks.map { task ->
                        com.nyx.ai.terminal.PlannedDependencyTask(
                            workerKind = task.workerKind,
                            title = task.title,
                            instructions = task.instructions,
                            verification = task.verification.orEmpty(),
                        )
                    }
                    com.nyx.ai.terminal.ObjectiveDependencyScope.beforeExecution(dependencyTasks) { projectKind ->
                        try {
                            val bootstrap = DependencyBootstrap.prepare(
                                request = BootstrapRequest(
                                    objectiveId = objectiveId,
                                    projectKind = projectKind,
                                    environment = environment,
                                    guestAlias = guestAlias,
                                    workspace = workspace,
                                    environmentId = environmentId,
                                ),
                                gateway = TerminalDependencyGateway(TerminalRepository(this, database, bridge)),
                                authorize = { candidate ->
                                    AutonomyRuntime.authorize(
                                        this,
                                        ActionRequest(
                                            kind = ActionKind.INSTALL_PACKAGE,
                                            target = "$workspace/.nyx/package/${candidate.packageName}",
                                            environmentId = environmentId,
                                            insideAuthorizedWorkspace = true,
                                            trustedSource = candidate.trusted,
                                        ),
                                    ).allowed
                                },
                                onProgress = { kind, detail -> recordEvent(objectiveId, kind, detail) },
                            )
                            when (bootstrap.status) {
                                BootstrapStatus.READY, BootstrapStatus.INSTALLED -> {
                                    recordEvent(objectiveId, "dependency_ready", bootstrap.message)
                                    true
                                }
                                BootstrapStatus.APPROVAL_REQUIRED,
                                BootstrapStatus.BLOCKED,
                                BootstrapStatus.FAILED -> {
                                    markNeedsAttention(objectiveId, bootstrap.message)
                                    recordEvent(objectiveId, "dependency_blocked", bootstrap.message)
                                    false
                                }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            val message = "Dependency preparation failed: ${(e.message ?: e.javaClass.simpleName).replace(Regex("(?i)(bearer\\s+)[^\\s]+"), "\$1[redacted]").take(350)}"
                            markNeedsAttention(objectiveId, message)
                            recordEvent(objectiveId, "dependency_blocked", message)
                            false
                        }
                    }
                }
            }
            val gatewayExtra = if (workbenchTools != null) null else runCatching {
                val vault = com.nyx.ai.settings.CredentialVault(this)
                val repository = com.nyx.ai.gateway.GatewayRepository(
                    root = java.io.File(filesDir, "gateways"),
                    tokenProvider = { reference -> runCatching { vault.read(reference) }.getOrNull() },
                )
                val servers = repository.list().filter { it.enabled }.mapNotNull { server ->
                    val tools = repository.listTools(server.id)
                    if (tools.isEmpty()) null else server to tools
                }
                com.nyx.ai.agent.GatewayAgentTools(
                    context = this,
                    repository = repository,
                    servers = servers,
                    environmentId = snapshot.optString("environment", "termux_host").lowercase(),
                    workspace = snapshot.optString("workspace"),
                ).takeIf { it.names.isNotEmpty() }
            }.getOrNull()
            val resources = DeviceResourceReader.read(this)
            val resourceProfile = ResourcePolicy.profile(
                resources = resources,
                requestedParallelism = objective.parallelism,
                parallelismCap = com.nyx.ai.performance.PerformanceController.tuning().autonomyParallelism,
            )
            ExecutionMonitor.update { it.copy(phase = "executing", message = resourceProfile.reason) }
            val engine = ObjectiveExecutionEngine(
                context = this,
                database = database,
                sessions = sessions,
                terminalTools = terminalTools,
                extraTools = if (workbenchTools != null) workbenchTools else if (gatewayExtra != null) {
                    com.nyx.ai.agent.CompositeToolProvider(listOf(NetworkAgentTools(this), gatewayExtra))
                } else NetworkAgentTools(this),
                arsenal = com.nyx.ai.skills.ArsenalRepository(this),
                projectMemory = snapshot.optString("workspace").takeIf { it.startsWith("/") }?.let { workspace ->
                    com.nyx.ai.context.ProjectMemory(
                        root = java.io.File(workspace),
                        storeDirectory = java.io.File(filesDir, "project-memory"),
                    )
                },
                fileProbe = terminalTools,
                contextStore = contextStore,
                suppliedRunId = run.runId,
                beforeTasksExecute = beforeTasksExecute,
                workbenchApproval = workbenchApproval,
                workbenchStore = workbenchStore,
            )
            val result = if (tasks.isEmpty()) {
                engine.planAndExecute(objectiveId, agent, resourceProfile.policy, executionControl)
            } else {
                engine.resumeAfterReview(objectiveId, agent, resourceProfile.policy, executionControl)
            }
            result.status
        } finally {
            segmentWatchdog?.cancel()
            heartbeat.cancel()
            withContext(NonCancellable) {
                segmentWatchdog?.join(); heartbeat.join()
                database.withTransaction {
                    if (!ownsLease(run, dao.getObjective(objectiveId)?.leaseOwner)) return@withTransaction
                    reservation?.let { segment ->
                        val elapsed = ((System.nanoTime() - allowanceStartedNanos) / 1_000_000).coerceAtLeast(0)
                        recordEvent(objectiveId, "timeout_allowance", JSONObject().put("consumedMillis", segment.settledConsumedMs(elapsed))
                            .put("sequence", allowanceSequence + 1).put("elapsedMillis", elapsed).put("runId", run.runId).put("state", "settled").toString())
                    }
                }
            }
        }
    }

    private suspend fun markNeedsAttention(run: ObjectiveRun, message: String) = database.withTransaction {
        val dao = database.objectiveDao()
        dao.getObjective(run.objectiveId)?.takeIf { ownsLease(run, it.leaseOwner) }?.let { objective ->
            dao.upsertObjective(
                objective.copy(
                    status = "needs_attention",
                    error = message.take(600),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun recordEvent(objectiveId: String, kind: String, detail: String, runId: String? = null) {
        database.objectiveDao().appendEvent(
            ObjectiveEventEntity(
                id = UUID.randomUUID().toString(),
                objectiveId = objectiveId,
                taskId = null,
                kind = kind,
                detail = detail.take(1_000),
                createdAt = System.currentTimeMillis(),
            )
        )
        WorkerActivityBus.record(
            kind = when {
                kind.contains("install") || kind.contains("dependency") -> WorkerActivityKind.TOOL
                kind.contains("file") -> WorkerActivityKind.FILE
                kind.contains("terminal") -> WorkerActivityKind.TERMINAL
                kind.contains("environment") -> WorkerActivityKind.INFO
                kind.contains("recover") -> WorkerActivityKind.RECOVERY
                kind.contains("error") || kind.contains("failed") -> WorkerActivityKind.ERROR
                else -> WorkerActivityKind.INFO
            },
            title = kind.replace('_', ' '),
            detail = detail,
            objectiveId = objectiveId,
            runId = runId,
        )
    }

    private fun startForegroundSafely(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, ObjectiveExecutionService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, ObjectiveExecutionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("NYX · ${operations.current()?.title ?: "Objective"}")
            .setContentText(text.take(120))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .addAction(0, "Cancel", cancel)
            .setProgress(0, 0, true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Objective execution", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when NYX is running an autonomous objective"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun terminalEnabled(): Boolean =
        getSharedPreferences("nyx_settings", Context.MODE_PRIVATE).getBoolean(KEY_TERMINAL_ENABLED, false)

    private fun terminalWorkspace(): String =
        getSharedPreferences("nyx_settings", Context.MODE_PRIVATE).getString(KEY_TERMINAL_WORKSPACE, "") ?: ""

    private fun ownsLease(run: ObjectiveRun, persistedOwner: String?): Boolean =
        com.nyx.ai.autonomy.ExecutionLeasePolicy.mayCheckpoint(run.leaseAcquired, run.leaseLost, run.ownerId, persistedOwner)

    private suspend fun checkpointInterrupted(run: ObjectiveRun, status: String) = database.withTransaction {
        val id = run.objectiveId
        val dao = database.objectiveDao()
        val row = dao.getObjective(id) ?: return@withTransaction
        if (!ownsLease(run, row.leaseOwner)) return@withTransaction
        val now = System.currentTimeMillis()
        dao.getTasks(id).filter { it.status in setOf("running", "verifying") }.forEach {
            dao.upsertTask(it.copy(status = "needs_review", updatedAt = now, finishedAt = now,
                error = "Interrupted. An external process or file edit may already have occurred; no automatic replay."))
        }
        if (row.status in setOf("planning", "running", "paused")) dao.upsertObjective(row.copy(status = status, updatedAt = now,
                error = if (status == "completed") null else "Execution stopped ($status). Review uncertain tasks and provider settings before resuming."))
    }

    override fun onTimeout(startId: Int) { onTimeout(startId, 0) }

    override fun onTimeout(startId: Int, fgsType: Int) {
        operations.current()?.let { it.control.requestPause(); it.job.cancel(PauseRequested()) }
        bridge.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        bridge.cancelAll()
        operations.current()?.job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.nyx.ai.action.START_OBJECTIVE"
        const val ACTION_PAUSE = "com.nyx.ai.action.PAUSE_OBJECTIVE"
        const val ACTION_CANCEL = "com.nyx.ai.action.CANCEL_OBJECTIVE"
        const val EXTRA_OBJECTIVE_ID = "objective_id"
        const val KEY_TERMINAL_ENABLED = "terminal_tools_enabled"
        const val KEY_TERMINAL_WORKSPACE = "terminal_workspace"
        private const val CHANNEL_ID = "nyx_objective_execution"
        private const val NOTIFICATION_ID = 4101
        // Bootstrap safety bound; the approved policy starts a separate <=2h segment watchdog.
        // A 12h Custom allowance requires checkpointed, explicitly resumed segments, never a 12h service.
        private const val MAX_RUN_MILLIS = ExecutionTimeoutPolicy.MAX_SERVICE_SEGMENT_MS
        private const val LEASE_MILLIS = 90_000L

        fun start(context: Context, objectiveId: String) {
            val intent = Intent(context, ObjectiveExecutionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_OBJECTIVE_ID, objectiveId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ObjectiveExecutionService::class.java).setAction(ACTION_PAUSE))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ObjectiveExecutionService::class.java).setAction(ACTION_CANCEL))
        }
    }
}

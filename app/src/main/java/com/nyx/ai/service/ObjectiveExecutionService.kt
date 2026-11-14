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
import com.nyx.ai.MainActivity
import com.nyx.ai.R
import com.nyx.ai.autonomy.DeviceResourceReader
import com.nyx.ai.autonomy.ExecutionControl
import com.nyx.ai.autonomy.ExecutionMonitor
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
class ObjectiveExecutionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownerId = UUID.randomUUID().toString()
    private lateinit var database: NyxDatabase
    private lateinit var contextStore: ContextStore
    private lateinit var bridge: TerminalBridge
    private lateinit var sessions: InferenceSessionFactory
    private var terminalTools: TerminalAgentTools? = null

    private var runJob: Job? = null
    private var control: ExecutionControl? = null
    private var cancelRequested = false
    private var currentObjectiveId: String? = null
    private var currentRunId: String? = null
    private var currentTitle: String = "Objective"

    override fun onCreate() {
        super.onCreate()
        com.nyx.ai.autonomy.WorkerActivityBus.initialize(this)
        database = NyxDatabase.getInstance(this)
        contextStore = ContextStore(java.io.File(filesDir, "context"))
        bridge = TerminalBridge(this)
        val factory = InferenceClientFactory(database, CredentialVault(this), this)
        sessions = InferenceSessionFactory { objective, agent ->
            val saved = database.objectiveDao().observeEvents(objective.id).first().lastOrNull { it.kind == "execution_config" }
                ?: error("Legacy objective has no provider snapshot. Review it and select a provider before resuming.")
            val config = JSONObject(saved.detail ?: "{}")
            val endpoint = database.modelEndpointDao().getById(config.getString("endpointId"))
                ?: error("The objective's snapshotted provider was removed.")
            val handle = factory.providerForRecord(endpoint.copy(modelId = config.getString("modelId"), configurationJson = config.optJSONObject("options")?.toString()))
            InferenceSession(handle.provider, config.getString("modelId"), handle.options)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_PAUSE -> {
                if (runJob == null) { stopSelf(startId); return START_NOT_STICKY }
                control?.requestPause()
                runJob?.cancel(PauseRequested())
                bridge.cancelAll()
                updateNotification("Pausing after the current step…")
            }
            ACTION_CANCEL -> {
                if (runJob == null) { stopSelf(startId); return START_NOT_STICKY }
                cancelRequested = true
                control?.requestCancel()
                runJob?.cancel(CancellationException("Cancelled by user"))
                bridge.cancelAll()
                updateNotification("Cancelling…")
            }
            ACTION_START -> {
                val objectiveId = intent?.getStringExtra(EXTRA_OBJECTIVE_ID)
                if (objectiveId.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                AutonomyRuntime.initialize(this)
                if (AutonomyRuntime.policy().stopped) {
                    updateNotification("Emergency stop is active. Resume autonomy before starting work.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (runJob?.isActive == true) {
                    updateNotification("Another objective is already running.")
                    return START_NOT_STICKY
                }
                try {
                    startForegroundSafely("Starting objective…")
                    launchObjective(objectiveId)
                } catch (failure: Exception) {
                    ExecutionMonitor.finish("Foreground-service startup rejected: ${failure.javaClass.simpleName}")
                    scope.launch {
                        database.objectiveDao().getObjective(objectiveId)?.let { database.objectiveDao().upsertObjective(it.copy(status = "needs_attention", error = "Android refused foreground-service start: ${failure.javaClass.simpleName}")) }
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun launchObjective(objectiveId: String) {
        val executionControl = ExecutionControl()
        control = executionControl
        cancelRequested = false
        currentObjectiveId = objectiveId
        currentRunId = "run-${UUID.randomUUID()}"
        ExecutionMonitor.begin(objectiveId)
        runJob = scope.launch {
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
                runJob?.cancel(PauseRequested())
                bridge.cancelAll()
            }
            var finalStatus: String? = null
            try {
                finalStatus = execute(objectiveId, executionControl)
            } catch (pause: PauseRequested) {
                finalStatus = "paused"
            } catch (cancel: CancellationException) {
                finalStatus = if (cancelRequested) "cancelled" else "paused"
            } catch (e: Exception) {
                finalStatus = "needs_attention"
                database.objectiveDao().getObjective(objectiveId)?.let {
                    database.objectiveDao().upsertObjective(
                        it.copy(status = "needs_attention", error = (e.message ?: "Execution failed").take(350), updatedAt = System.currentTimeMillis())
                    )
                }
            } finally {
                watchdog.cancel(); sampler.cancel()
                withContext(NonCancellable) {
                    checkpointInterrupted(objectiveId, finalStatus ?: "paused")
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
                }
                ExecutionMonitor.finish(finalStatus)
                updateNotification(finalStatus ?: "finished")
                runJob = null
                control = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun execute(objectiveId: String, executionControl: ExecutionControl): String {
        val dao = database.objectiveDao()
        val objective = dao.getObjective(objectiveId) ?: return "missing"
        val agent = objective.agentId?.let { database.agentDao().getById(it) } ?: return "missing_agent"
        currentTitle = objective.title.take(80)
        val now = System.currentTimeMillis()
        if (dao.tryAcquireLease(objectiveId, ownerId, now, now + LEASE_MILLIS) == 0) {
            return "busy"
        }
        val heartbeat = scope.launch {
            while (isActive) {
                delay(LEASE_MILLIS / 3)
                val renewed = dao.tryAcquireLease(objectiveId, ownerId, System.currentTimeMillis(), System.currentTimeMillis() + LEASE_MILLIS)
                if (renewed == 0) {
                    executionControl.requestPause()
                    runJob?.cancel(PauseRequested())
                    return@launch
                }
                val pressure = DeviceResourceReader.read(this@ObjectiveExecutionService)
                if (pressure.lowMemory || pressure.thermalStatus >= 4 || (pressure.batteryPercent < 8 && !pressure.charging)) {
                    executionControl.requestPause()
                    runJob?.cancel(PauseRequested())
                    return@launch
                }
                updateNotification("Running")
            }
        }
        return try {
            val snapshot = dao.observeEvents(objectiveId).first().lastOrNull { it.kind == "execution_config" }?.detail?.let { JSONObject(it) }
                ?: error("No approved execution settings for this objective.")
            val tasks = dao.getTasks(objectiveId)
            val environment = runCatching { TerminalEnvironment.valueOf(snapshot.optString("environment", "TERMUX_HOST")) }
                .getOrDefault(TerminalEnvironment.TERMUX_HOST)
            val guestAlias = snapshot.optString("guestAlias", "debian")
            val workspace = snapshot.optString("workspace")
            val environmentId = if (environment == TerminalEnvironment.TERMUX_HOST) "termux_host" else "proot:$guestAlias"
            terminalTools = null
            if (terminalEnabled() && snapshot.optBoolean("terminalEnabled")) {
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
                    useTimeoutBinary = environmentProfile.timeoutAvailable,
                    workspaceApproved = true,
                    guestAlias = guestAlias,
                    modelCommandGate = AutonomyModelCommandGate(this, environmentId, workspace, environment),
                )
            }
            val beforeTasksExecute: suspend (List<com.nyx.ai.data.ObjectiveTaskEntity>) -> Boolean = { plannedTasks ->
                if (terminalTools == null) {
                    true
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
            val gatewayExtra = runCatching {
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
                extraTools = if (gatewayExtra != null) {
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
                suppliedRunId = currentRunId,
                beforeTasksExecute = beforeTasksExecute,
            )
            val result = if (tasks.isEmpty()) {
                engine.planAndExecute(objectiveId, agent, resourceProfile.policy, executionControl)
            } else {
                engine.resumeAfterReview(objectiveId, agent, resourceProfile.policy, executionControl)
            }
            result.status
        } finally {
            heartbeat.cancel()
            withContext(NonCancellable) { dao.releaseLease(objectiveId, ownerId) }
        }
    }

    private suspend fun markNeedsAttention(objectiveId: String, message: String) {
        val dao = database.objectiveDao()
        dao.getObjective(objectiveId)?.let { objective ->
            dao.upsertObjective(
                objective.copy(
                    status = "needs_attention",
                    error = message.take(600),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun recordEvent(objectiveId: String, kind: String, detail: String) {
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
            runId = currentRunId,
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
            .setContentTitle("NYX · $currentTitle")
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

    private suspend fun checkpointInterrupted(id: String, status: String) {
        val dao = database.objectiveDao()
        val now = System.currentTimeMillis()
        dao.getTasks(id).filter { it.status in setOf("running", "verifying") }.forEach {
            dao.upsertTask(it.copy(status = "needs_review", updatedAt = now, finishedAt = now,
                error = "Interrupted. An external process or file edit may already have occurred; no automatic replay."))
        }
        dao.getObjective(id)?.let { row ->
            if (row.status in setOf("planning", "running", "paused")) dao.upsertObjective(row.copy(status = status, updatedAt = now, leaseOwner = null, leaseExpiresAt = null,
                error = if (status == "completed") null else "Execution stopped ($status). Review uncertain tasks and provider settings before resuming."))
        }
    }

    override fun onTimeout(startId: Int) { onTimeout(startId, 0) }

    override fun onTimeout(startId: Int, fgsType: Int) {
        control?.requestPause()
        runJob?.cancel(PauseRequested())
        bridge.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        bridge.cancelAll()
        runJob?.cancel()
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
        // Long objectives are checkpointed and can legitimately exceed half an hour. The watchdog
        // remains a hard bound, while the lease heartbeat still pauses on thermal, memory, or
        // battery pressure and the user can pause or cancel at any time.
        private const val MAX_RUN_MILLIS = 2 * 60 * 60_000L
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

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
import com.nyx.ai.autonomy.PauseRequested
import com.nyx.ai.autonomy.ResourcePolicy
import com.nyx.ai.context.ContextStore
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.settings.CredentialVault
import com.nyx.ai.settings.InferenceClientFactory
import com.nyx.ai.terminal.TerminalAgentTools
import com.nyx.ai.terminal.TerminalBridge
import com.nyx.ai.terminal.TerminalEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service that owns objective execution. The ViewModel only starts, pauses, cancels, and
 * observes Room; it never owns the running coroutine.
 *
 * Android restricts background work, so this uses a `dataSync` foreground service with a visible
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
    private var currentTitle: String = "Objective"

    override fun onCreate() {
        super.onCreate()
        database = NyxDatabase.getInstance(this)
        contextStore = ContextStore(java.io.File(filesDir, "context"))
        bridge = TerminalBridge(this)
        val factory = InferenceClientFactory(database, CredentialVault(this))
        sessions = InferenceSessionFactory { objective, agent ->
            val handle = factory.providerFor(agent.endpointId)
            InferenceSession(
                provider = handle.provider,
                modelId = objective.modelId?.takeIf { it.isNotBlank() } ?: handle.endpoint.modelId,
                options = handle.options,
            )
        }
        if (terminalEnabled()) {
            val root = terminalWorkspace()
            if (root.isNotBlank()) {
                terminalTools = TerminalAgentTools(bridge, root, TerminalEnvironment.TERMUX_HOST)
            }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_PAUSE -> {
                control?.requestPause()
                updateNotification("Pausing after the current step…")
            }
            ACTION_CANCEL -> {
                cancelRequested = true
                control?.requestCancel()
                updateNotification("Cancelling…")
            }
            ACTION_START -> {
                val objectiveId = intent?.getStringExtra(EXTRA_OBJECTIVE_ID)
                if (objectiveId.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (runJob?.isActive == true) {
                    updateNotification("Another objective is already running.")
                    return START_NOT_STICKY
                }
                startForegroundSafely("Starting objective…")
                launchObjective(objectiveId)
            }
        }
        return START_NOT_STICKY
    }

    private fun launchObjective(objectiveId: String) {
        val executionControl = ExecutionControl()
        control = executionControl
        cancelRequested = false
        currentObjectiveId = objectiveId
        ExecutionMonitor.begin(objectiveId)
        runJob = scope.launch {
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
                if (renewed == 0) return@launch
                updateNotification("Running")
            }
        }
        return try {
            val resources = DeviceResourceReader.read(this)
            val profile = ResourcePolicy.profile(resources, objective.parallelism)
            ExecutionMonitor.update { it.copy(phase = "executing", message = profile.reason) }
            val engine = ObjectiveExecutionEngine(
                context = this,
                database = database,
                sessions = sessions,
                terminalTools = terminalTools,
                fileProbe = terminalTools,
                contextStore = contextStore,
            )
            val tasks = dao.getTasks(objectiveId)
            val result = if (tasks.isEmpty()) {
                engine.planAndExecute(objectiveId, agent, profile.policy, executionControl)
            } else {
                engine.resumeAfterReview(objectiveId, agent, profile.policy, executionControl)
            }
            result.status
        } finally {
            heartbeat.cancel()
            dao.releaseLease(objectiveId, ownerId)
        }
    }

    private fun startForegroundSafely(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

    override fun onDestroy() {
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

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
import com.nyx.ai.ollama.OllamaServerManager
import com.nyx.ai.ollama.OllamaServerState
import com.nyx.ai.ollama.OllamaServerStatus
import com.nyx.ai.terminal.TerminalBridge
import com.nyx.ai.terminal.TerminalEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps a managed guest Ollama session alive while the NYX activity is in the background.
 *
 * A PRoot session is a child of the Termux RunCommandService. If NYX does not hold a foreground
 * service while it waits for the server, Android may freeze or kill the app process and the user
 * sees a server that started and then vanished. This service deliberately declares the bounded
 * `dataSync` type for a user-started local model-serving session; it is not a way to claim unlimited
 * background execution. Android's own foreground-service timeout still applies, and the server
 * remains independently owned and stoppable through its verified PID.
 */
class OllamaHostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var bridge: TerminalBridge
    private lateinit var manager: OllamaServerManager
    private var runJob: Job? = null
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        bridge = TerminalBridge(this)
        manager = OllamaServerManager(this, bridge, autoRefresh = false)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        latestStartId = startId
        startForegroundSafely("Preparing the managed Ollama session…")
        if (runJob?.isActive == true) {
            if (action == ACTION_START) return START_NOT_STICKY
            // Stop or restart must be able to supersede a startup that is still discovering the
            // guest. The old job's finally block is start-id guarded, so it cannot remove this
            // service's newer notification.
            runJob?.cancel()
        }
        val aliases = intent?.getStringArrayListExtra(EXTRA_GUEST_ALIASES).orEmpty()
        val selected = intent?.getStringExtra(EXTRA_SELECTED_GUEST)
        val environment = intent?.getStringExtra(EXTRA_ENVIRONMENT)
            ?.let { runCatching { TerminalEnvironment.valueOf(it) }.getOrNull() }
            ?: TerminalEnvironment.TERMUX_HOST
        runJob = scope.launch {
            val notifier = launch {
                manager.state.collect { updateNotification(it) }
            }
            try {
                if (action != ACTION_STOP) {
                    manager.configureGuests(aliases, selected, environment)
                }
                val state = when (action) {
                    ACTION_STOP -> manager.stop()
                    ACTION_RESTART -> manager.restart()
                    else -> manager.start()
                }
                updateNotification(state)
                if (state.startedByNyx && (state.isRunning || state.sessionPid != null)) {
                    supervise()
                } else {
                    // Give the notification a moment to show the final message before the service ends.
                    delay(1_200L)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                updateNotification(e.message?.take(160) ?: "The Ollama lifecycle operation failed.")
                delay(800L)
            } finally {
                notifier.cancel()
                if (startId == latestStartId) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Keeps the foreground notification and the Android process alive while the managed guest
     * server is active. This is deliberately a bounded poll, not a promise of indefinite execution:
     * Android's foreground-service timeout still applies.
     */
    private suspend fun supervise() {
        var consecutiveFailures = 0
        while (true) {
            delay(SUPERVISE_INTERVAL_MS)
            val next = runCatching { manager.detect() }.getOrElse { error ->
                consecutiveFailures++
                updateNotification("Recovering · ${error.message?.take(140) ?: "state check failed"}")
                if (consecutiveFailures >= 6) return
                continue
            }
            consecutiveFailures = 0
            updateNotification(next)
            val managed = next.startedByNyx || next.sessionPid != null
            if (!managed) return
            if (!next.isRunning && next.sessionPid == null &&
                (next.status == OllamaServerStatus.STOPPED || next.status == OllamaServerStatus.NOT_INSTALLED)
            ) {
                return
            }
        }
    }

    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onTimeout(startId: Int, fgsType: Int) {
        runJob?.cancel()
        bridge.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        runJob?.cancel()
        bridge.cancelAll()
        manager.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely(text: String) {
        val notification = buildNotification("NYX · Managed Ollama", text, progress = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification("NYX · Managed Ollama", text, progress = true),
            )
        }
    }

    private fun updateNotification(state: OllamaServerState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val label = statusLabel(state.status)
        val text = state.message.ifBlank { label }
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    title = "NYX · $label",
                    text = text,
                    progress = state.status == OllamaServerStatus.STARTING,
                ),
            )
        }
    }

    private fun buildNotification(title: String, text: String, progress: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, OllamaHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text.take(160))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "View Activity", open)
            .addAction(0, "Stop Worker", stop)
            .setProgress(0, 0, progress)
        val termux = packageManager.getLaunchIntentForPackage("com.termux")
        if (termux != null) {
            val termuxPending = PendingIntent.getActivity(
                this,
                12,
                termux,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Open Termux", termuxPending)
        }
        return builder.build()
    }

    private fun statusLabel(status: OllamaServerStatus): String = when (status) {
        OllamaServerStatus.STARTING -> "Starting"
        OllamaServerStatus.RUNNING -> "Ready · Running"
        OllamaServerStatus.UNREACHABLE -> "Recovering"
        OllamaServerStatus.ERROR, OllamaServerStatus.INCOMPATIBLE -> "Error"
        OllamaServerStatus.STOPPING, OllamaServerStatus.STOPPED, OllamaServerStatus.NOT_INSTALLED -> "Stopped"
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Managed Ollama server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps a NYX-managed guest Ollama session alive while it starts or serves a local model"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.nyx.ai.action.START_OLLAMA_HOST"
        const val ACTION_STOP = "com.nyx.ai.action.STOP_OLLAMA_HOST"
        const val ACTION_RESTART = "com.nyx.ai.action.RESTART_OLLAMA_HOST"
        const val EXTRA_GUEST_ALIASES = "guest_aliases"
        const val EXTRA_SELECTED_GUEST = "selected_guest"
        const val EXTRA_ENVIRONMENT = "terminal_environment"
        private const val CHANNEL_ID = "nyx_ollama_host"
        private const val NOTIFICATION_ID = 4102
        private const val SUPERVISE_INTERVAL_MS = 20_000L

        fun start(
            context: Context,
            guestAliases: List<String>,
            selectedGuest: String?,
            environment: TerminalEnvironment,
        ) {
            val intent = Intent(context, OllamaHostService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_GUEST_ALIASES, ArrayList(guestAliases.take(4)))
                putExtra(EXTRA_SELECTED_GUEST, selectedGuest)
                putExtra(EXTRA_ENVIRONMENT, environment.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OllamaHostService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restart(
            context: Context,
            guestAliases: List<String>,
            selectedGuest: String?,
            environment: TerminalEnvironment,
        ) {
            val intent = Intent(context, OllamaHostService::class.java).apply {
                action = ACTION_RESTART
                putStringArrayListExtra(EXTRA_GUEST_ALIASES, ArrayList(guestAliases.take(4)))
                putExtra(EXTRA_SELECTED_GUEST, selectedGuest)
                putExtra(EXTRA_ENVIRONMENT, environment.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

package com.nyx.ai.terminal

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit probe contract. Implementations must return a real, currently available executable path. */
fun interface ProotDistroProbe {
    fun probeAvailableExecutablePath(): String?
}

/** Probe only the exact path provided by the caller; no guessed Termux paths or package installation. */
class ExplicitProotDistroPathProbe(private val configuredPath: String) : ProotDistroProbe {
    override fun probeAvailableExecutablePath(): String? {
        if (!configuredPath.startsWith('/') || '\u0000' in configuredPath) return null
        val candidate = File(configuredPath)
        return configuredPath.takeIf { candidate.isFile && candidate.canExecute() }
    }
}

private object NoProotDistroProbe : ProotDistroProbe {
    override fun probeAvailableExecutablePath(): String? = null
}

data class TermuxReadiness(
    val installed: Boolean,
    val runCommandPermissionGranted: Boolean,
    val requiredPermission: String = TermuxProtocol.PERMISSION_RUN_COMMAND,
    val detail: String? = null,
) {
    val ready: Boolean get() = installed && runCommandPermissionGranted
}

/**
 * Initial local IPC adapter. It never requests/grants permissions and requires per-command approval.
 * Use background commands so Termux can return distinct stdout, stderr, and process exit code.
 */
class TermuxIpcAdapter(
    context: Context,
    private val prootDistroProbe: ProotDistroProbe = NoProotDistroProbe,
) {
    private val appContext = context.applicationContext

    fun readiness(): TermuxReadiness {
        val installed = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(TermuxProtocol.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: RuntimeException) {
            return TermuxReadiness(false, false, detail = e.message ?: "Unable to inspect Termux package")
        }
        val granted = appContext.checkSelfPermission(TermuxProtocol.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        return TermuxReadiness(
            installed = installed,
            runCommandPermissionGranted = granted,
            detail = if (!installed) "Termux package ${TermuxProtocol.PACKAGE} is not visible or installed"
                else if (!granted) "Grant ${TermuxProtocol.PERMISSION_RUN_COMMAND} to NYX in Android app permissions and enable allow-external-apps=true in Termux"
                else "Termux package visible and RUN_COMMAND permission granted; allow-external-apps=true is still a Termux-side prerequisite",
        )
    }

    /** Dispatches a command only after explicit per-command approval. Results arrive once on callback. */
    fun execute(
        request: TerminalCommandRequest,
        callback: (TerminalCommandResult) -> Unit,
    ): TerminalCommandHandle? {
        val requestId = UUID.randomUUID().toString()
        if (!request.authorized) {
            callback(TerminalCommandResult(requestId, TerminalResultKind.DENIED, message = "Explicit per-command authorization is required"))
            return null
        }
        val validation = TerminalRequestValidator.validate(request)
        if (validation.isNotEmpty()) {
            callback(TerminalCommandResult(requestId, TerminalResultKind.INVALID_REQUEST, message = validation.joinToString("; ") { "${it.field}: ${it.message}" }))
            return null
        }
        val readiness = readiness()
        if (!readiness.installed) {
            callback(TerminalCommandResult(requestId, TerminalResultKind.TERMUX_NOT_INSTALLED, message = readiness.detail))
            return null
        }
        if (!readiness.runCommandPermissionGranted) {
            callback(TerminalCommandResult(requestId, TerminalResultKind.PERMISSION_REQUIRED, message = readiness.detail))
            return null
        }

        val prootPath = if (request.environment == TerminalEnvironment.DEBIAN_PROOT) {
            try {
                prootDistroProbe.probeAvailableExecutablePath()
            } catch (e: Exception) {
                null
            } ?: run {
                callback(TerminalCommandResult(requestId, TerminalResultKind.PROOT_DISTRO_UNAVAILABLE, message = "proot-distro path probe did not find an available executable"))
                return null
            }
        } else null
        val descriptor = TerminalCommandDescriptors.create(request, prootPath)
        if (descriptor == null) {
            callback(TerminalCommandResult(requestId, TerminalResultKind.INVALID_REQUEST, message = "Could not construct a safe command descriptor"))
            return null
        }

        val receiverIntent = Intent(appContext, TermuxResultReceiver::class.java).apply {
            action = TermuxResultReceiver.ACTION_RESULT
            data = Uri.parse("nyx-termux-result://callback/$requestId")
        }
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val resultPendingIntent = try {
            PendingIntent.getBroadcast(
                appContext,
                requestId.hashCode(),
                receiverIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or mutableFlag,
            )
        } catch (e: RuntimeException) {
            callback(TerminalCommandResult(requestId, TerminalResultKind.DISPATCH_ERROR, message = e.message ?: "Unable to create callback PendingIntent"))
            return null
        }

        val timeoutHandler = Handler(Looper.getMainLooper())
        val completion = Completion(requestId, resultPendingIntent, callback, timeoutHandler)
        pending[requestId] = completion
        completion.timeoutRunnable = Runnable {
            complete(requestId, TerminalCommandResult(requestId, TerminalResultKind.TIMEOUT, message = "No Termux result callback arrived before timeout; the command may still be running"))
        }
        timeoutHandler.postDelayed(completion.timeoutRunnable!!, request.timeoutMillis)

        val intent = Intent(TermuxProtocol.ACTION_RUN_COMMAND).apply {
            component = ComponentName(TermuxProtocol.PACKAGE, TermuxProtocol.SERVICE_CLASS)
            putExtra(TermuxProtocol.EXTRA_COMMAND_PATH, descriptor.executablePath)
            putExtra(TermuxProtocol.EXTRA_ARGUMENTS, descriptor.arguments.toTypedArray())
            descriptor.stdin?.let { putExtra(TermuxProtocol.EXTRA_STDIN, it) }
            descriptor.workingDirectory?.let { putExtra(TermuxProtocol.EXTRA_WORKDIR, it) }
            putExtra(TermuxProtocol.EXTRA_BACKGROUND, true)
            putExtra(TermuxProtocol.EXTRA_PENDING_INTENT, resultPendingIntent)
        }
        try {
            val started = appContext.startService(intent)
            if (started == null) {
                complete(requestId, TerminalCommandResult(requestId, TerminalResultKind.DISPATCH_ERROR, message = "Android did not resolve the Termux RunCommandService"))
            }
        } catch (e: SecurityException) {
            complete(requestId, TerminalCommandResult(requestId, TerminalResultKind.DISPATCH_ERROR, message = e.message ?: "Android denied Termux service dispatch"))
        } catch (e: RuntimeException) {
            complete(requestId, TerminalCommandResult(requestId, TerminalResultKind.DISPATCH_ERROR, message = e.message ?: "Termux service dispatch failed"))
        }
        return TerminalCommandHandle(requestId) { result -> complete(requestId, result) }
    }

    /**
     * Starts a command without a result callback. This is used only for a deliberately long-lived
     * PRoot session whose lifetime is the server's lifetime. Termux's documented callback arrives
     * only when the command exits, so installing a timeout here would incorrectly cancel the wait
     * while leaving the server running.
     */
    fun dispatchPersistent(request: TerminalCommandRequest): TerminalCommandResult {
        if (!request.authorized) {
            return TerminalCommandResult(null, TerminalResultKind.DENIED, message = "Explicit authorization is required")
        }
        val validation = TerminalRequestValidator.validate(request)
        if (validation.isNotEmpty()) {
            return TerminalCommandResult(
                null,
                TerminalResultKind.INVALID_REQUEST,
                message = validation.joinToString("; ") { "${it.field}: ${it.message}" },
            )
        }
        val readiness = readiness()
        if (!readiness.installed) {
            return TerminalCommandResult(null, TerminalResultKind.TERMUX_NOT_INSTALLED, message = readiness.detail)
        }
        if (!readiness.runCommandPermissionGranted) {
            return TerminalCommandResult(null, TerminalResultKind.PERMISSION_REQUIRED, message = readiness.detail)
        }
        val prootPath = if (request.environment == TerminalEnvironment.DEBIAN_PROOT) {
            runCatching { prootDistroProbe.probeAvailableExecutablePath() }.getOrNull()
                ?: return TerminalCommandResult(
                    null,
                    TerminalResultKind.PROOT_DISTRO_UNAVAILABLE,
                    message = "proot-distro path probe did not find an available executable",
                )
        } else {
            null
        }
        val descriptor = TerminalCommandDescriptors.create(request, prootPath)
            ?: return TerminalCommandResult(null, TerminalResultKind.INVALID_REQUEST, message = "Could not construct a safe command descriptor")
        val intent = Intent(TermuxProtocol.ACTION_RUN_COMMAND).apply {
            component = ComponentName(TermuxProtocol.PACKAGE, TermuxProtocol.SERVICE_CLASS)
            putExtra(TermuxProtocol.EXTRA_COMMAND_PATH, descriptor.executablePath)
            putExtra(TermuxProtocol.EXTRA_ARGUMENTS, descriptor.arguments.toTypedArray())
            descriptor.stdin?.let { putExtra(TermuxProtocol.EXTRA_STDIN, it) }
            descriptor.workingDirectory?.let { putExtra(TermuxProtocol.EXTRA_WORKDIR, it) }
            putExtra(TermuxProtocol.EXTRA_BACKGROUND, true)
        }
        return try {
            if (appContext.startService(intent) == null) {
                TerminalCommandResult(null, TerminalResultKind.DISPATCH_ERROR, message = "Android did not resolve the Termux RunCommandService")
            } else {
                TerminalCommandResult(null, TerminalResultKind.SUCCEEDED, message = "Long-lived Termux command dispatched")
            }
        } catch (e: SecurityException) {
            TerminalCommandResult(null, TerminalResultKind.DISPATCH_ERROR, message = e.message ?: "Android denied Termux service dispatch")
        } catch (e: RuntimeException) {
            TerminalCommandResult(null, TerminalResultKind.DISPATCH_ERROR, message = e.message ?: "Termux service dispatch failed")
        }
    }

    private fun complete(requestId: String, result: TerminalCommandResult) {
        val completion = pending.remove(requestId) ?: return
        if (!completion.finished.compareAndSet(false, true)) return
        completion.timeoutRunnable?.let(completion.handler::removeCallbacks)
        if (result.kind != TerminalResultKind.SUCCEEDED && result.kind != TerminalResultKind.PROCESS_EXIT && result.kind != TerminalResultKind.TERMUX_INFRASTRUCTURE_ERROR && result.kind != TerminalResultKind.UNKNOWN_RESULT) {
            completion.pendingIntent.cancel()
        }
        completion.callback(result)
    }

    private class Completion(
        val requestId: String,
        val pendingIntent: PendingIntent,
        val callback: (TerminalCommandResult) -> Unit,
        val handler: Handler,
        val finished: AtomicBoolean = AtomicBoolean(false),
        var timeoutRunnable: Runnable? = null,
    )

    companion object {
        private val pending = ConcurrentHashMap<String, Completion>()

        internal fun receive(requestId: String?, resultBundle: android.os.Bundle?) {
            if (requestId == null) return
            val classified = TerminalResultClassifier.fromAndroidBundle(requestId, resultBundle)
            val completion = pending[requestId] ?: return
            completion.handler.post { completionFor(requestId, classified) }
        }

        // Receivers call into the adapter's shared registry without requiring an Activity reference.
        private fun completionFor(requestId: String, result: TerminalCommandResult) {
            val completion = pending.remove(requestId) ?: return
            if (!completion.finished.compareAndSet(false, true)) return
            completion.timeoutRunnable?.let(completion.handler::removeCallbacks)
            completion.callback(result)
        }
    }
}

class TerminalCommandHandle internal constructor(
    val requestId: String,
    private val cancelAction: (TerminalCommandResult) -> Unit,
) {
    fun cancel() {
        cancelAction(TerminalCommandResult(requestId, TerminalResultKind.CANCELLED, message = "Result waiting cancelled; the Termux command may still be running"))
    }
}

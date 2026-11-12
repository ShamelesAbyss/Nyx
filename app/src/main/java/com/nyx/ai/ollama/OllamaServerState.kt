package com.nyx.ai.ollama

import com.nyx.ai.network.LocalModel

/** Lifecycle of the Termux-hosted Ollama server as NYX understands it right now. */
enum class OllamaServerStatus {
    /** `command -v ollama` found nothing on the Termux host. */
    NOT_INSTALLED,

    /** Installed (or unknown) but no server is answering and no NYX-owned PID is alive. */
    STOPPED,

    /** A start was issued and readiness is being polled. */
    STARTING,

    /** `GET /api/tags` answers. The server may be NYX-owned or adopted. */
    RUNNING,

    /** A verified stop is in progress. */
    STOPPING,

    /** The last operation failed; [OllamaServerState.message] and `logTail` say why. */
    ERROR,

    /** A binary exists but cannot execute or does not identify as Ollama. */
    INCOMPATIBLE,

    /** The installation is present but the API did not answer. */
    UNREACHABLE,
}

/**
 * Observable snapshot of the managed server.
 *
 * [startedByNyx] is the ownership flag that gates [OllamaServerManager.stop]: an adopted server is
 * reported as RUNNING with `startedByNyx = false` and is never signalled.
 */
data class OllamaServerState(
    val status: OllamaServerStatus = OllamaServerStatus.STOPPED,
    val pid: Int? = null,
    val endpoint: String = OllamaProcess.DEFAULT_ENDPOINT,
    val models: List<LocalModel> = emptyList(),
    val message: String = "",
    val logTail: String = "",
    val rssKb: Long? = null,
    val uptimeMillis: Long? = null,
    val startedByNyx: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val target: OllamaTarget = OllamaTarget.HOST,
    val installations: List<OllamaInstallation> = emptyList(),
    val architecture: String = "",
    val version: String = "",
    /** The PRoot session PID when proot-distro exposes one; never the Ollama PID. */
    val sessionPid: Int? = null,
    val launchMode: OllamaLaunchMode? = null,
    val sessionToken: String = "",
) {
    val isRunning: Boolean get() = status == OllamaServerStatus.RUNNING

    val isBusy: Boolean
        get() = status == OllamaServerStatus.STARTING || status == OllamaServerStatus.STOPPING

    /** True only when NYX owns a live PID and may therefore stop or restart it. */
    val canStop: Boolean get() = startedByNyx && pid != null && status in setOf(
        OllamaServerStatus.RUNNING,
        OllamaServerStatus.STARTING,
        OllamaServerStatus.UNREACHABLE,
    )

    /** A detached session can be stopped even when the guest PID record is missing. */
    val canStopSession: Boolean
        get() = launchMode == OllamaLaunchMode.PROOT_DETACHED && sessionPid != null

    val targetLabel: String get() = target.label

    val sessionLabel: String
        get() = when (launchMode) {
            OllamaLaunchMode.PROOT_DETACHED -> "PRoot session ${sessionPid ?: "detached"}"
            OllamaLaunchMode.PROOT_FOREGROUND -> "PRoot foreground session"
            OllamaLaunchMode.HOST_DETACHED -> "Termux host"
            null -> if (target.isHost) "Termux host" else "PRoot guest"
        }
}

package com.nyx.ai.ollama

/** What NYX may do when [OllamaServerManager.start] is called. */
enum class OllamaStartDecision {
    /** The API already answers: adopt it, never start a second server. */
    USE_RUNNING_SERVER,

    /** A verified, live NYX-owned process exists: refuse to duplicate it. */
    REFUSE_DUPLICATE,

    /** A saved PID exists but ownership cannot be verified: refuse to start or signal. */
    REFUSE_UNVERIFIED_OWNERSHIP,

    /** The endpoint is not loopback, so no local process can back it. */
    REFUSE_REMOTE_ENDPOINT,

    /** Ollama is not installed on the Termux host. */
    INSTALL_REQUIRED,

    /** Launch a new detached server. */
    START_NEW,
}

data class OllamaStartPlan(val decision: OllamaStartDecision, val reason: String)

/** What NYX may do when [OllamaServerManager.stop] is called. */
enum class OllamaStopDecision {
    /** No NYX-owned process is recorded, or ownership is unverified: leave everything running. */
    NOT_OWNED,

    /** The recorded process is already gone; only the stale record needs clearing. */
    NOT_RUNNING,

    /** Verified single-PID stop may proceed. */
    STOP_OWNED,
}

data class OllamaStopPlan(val decision: OllamaStopDecision, val reason: String)

enum class OllamaRestartDecision {
    /** Adopted or unverifiable server: restart would require killing something NYX does not own. */
    REFUSE_NOT_OWNED,

    /** Stop the verified process (if any) and start a fresh one. */
    STOP_THEN_START,
}

/** Internal transitions, each produced by exactly one observable step of a lifecycle operation. */
enum class OllamaLifecycleEvent {
    DETECTED_INSTALLED,
    DETECTED_MISSING,
    START_REQUESTED,
    START_SUCCEEDED,
    START_FAILED,
    ADOPTED,
    STOP_REQUESTED,
    STOP_SUCCEEDED,
    STOP_FAILED,
    STOP_REFUSED,
    RESET,
}

/**
 * Every lifecycle decision, expressed as a pure function of evidence.
 *
 * The manager collects evidence (API probe, `/proc` inspection, saved record) and then asks this
 * object. Keeping the rules here means duplicate prevention, adoption, and PID-reuse refusal are
 * testable without Android, Termux, or a running server.
 */
object OllamaLifecyclePolicy {

    /**
     * Order matters:
     * 1. A remote endpoint can never be a local process.
     * 2. A healthy API wins over everything: adopt instead of duplicating, even when Ollama was
     *    installed outside NYX and no record exists.
     * 3. A verified live record blocks a second start.
     * 4. An unverifiable live record also blocks it: refusing is safer than guessing.
     * 5. Only then does installation matter.
     */
    fun planStart(
        endpointLoopback: Boolean,
        apiHealthy: Boolean,
        installed: Boolean,
        savedPid: Int?,
        ownership: OllamaOwnership,
    ): OllamaStartPlan = when {
        !endpointLoopback -> OllamaStartPlan(
            OllamaStartDecision.REFUSE_REMOTE_ENDPOINT,
            "The configured endpoint is not a loopback address, so NYX cannot manage its process on this device.",
        )

        apiHealthy -> OllamaStartPlan(
            OllamaStartDecision.USE_RUNNING_SERVER,
            "An Ollama server is already answering on the configured endpoint; it will be adopted, not duplicated.",
        )

        savedPid != null && ownership == OllamaOwnership.OWNED -> OllamaStartPlan(
            OllamaStartDecision.REFUSE_DUPLICATE,
            "NYX already started Ollama with PID $savedPid and it is still running.",
        )

        savedPid != null && ownership == OllamaOwnership.UNKNOWN -> OllamaStartPlan(
            OllamaStartDecision.REFUSE_UNVERIFIED_OWNERSHIP,
            "A saved PID ($savedPid) could not be verified. NYX will not start a second server or stop an unverified process.",
        )

        !installed -> OllamaStartPlan(
            OllamaStartDecision.INSTALL_REQUIRED,
            "No compatible ollama binary is available in the selected host or verified guests.",
        )

        else -> OllamaStartPlan(
            OllamaStartDecision.START_NEW,
            "No server is running and Ollama is installed; a detached server will be started.",
        )
    }

    /**
     * A stop is allowed only for a record NYX wrote itself, whose live PID still matches both the
     * `ollama serve` command line and the saved start token. A recycled PID is not a stop target.
     */
    fun planStop(
        startedByNyx: Boolean,
        savedPid: Int?,
        ownership: OllamaOwnership,
    ): OllamaStopPlan = when {
        savedPid == null -> OllamaStopPlan(
            OllamaStopDecision.NOT_OWNED,
            "No NYX-owned Ollama process is recorded.",
        )

        !startedByNyx -> OllamaStopPlan(
            OllamaStopDecision.NOT_OWNED,
            "The running Ollama server was not started by NYX, so it will not be stopped.",
        )

        ownership == OllamaOwnership.MISSING -> OllamaStopPlan(
            OllamaStopDecision.NOT_RUNNING,
            "The recorded process has already exited; only its stale record remains.",
        )

        ownership == OllamaOwnership.OWNED -> OllamaStopPlan(
            OllamaStopDecision.STOP_OWNED,
            "The recorded PID and start token still belong to the NYX-started Ollama server.",
        )

        else -> OllamaStopPlan(
            OllamaStopDecision.NOT_OWNED,
            "The saved PID could not be verified as the NYX-started Ollama server and will not be signalled.",
        )
    }

    /**
     * Restarting an adopted server would mean killing a process the user started, so it is refused.
     * A stopped server simply starts; an owned live server is stopped and started again.
     */
    fun planRestart(
        running: Boolean,
        startedByNyx: Boolean,
        savedPid: Int?,
        ownership: OllamaOwnership,
    ): OllamaRestartDecision = when {
        !running && savedPid == null -> OllamaRestartDecision.STOP_THEN_START
        !running && startedByNyx && ownership in setOf(OllamaOwnership.OWNED, OllamaOwnership.MISSING) ->
            OllamaRestartDecision.STOP_THEN_START
        running && startedByNyx && savedPid != null && ownership == OllamaOwnership.OWNED ->
            OllamaRestartDecision.STOP_THEN_START
        else -> OllamaRestartDecision.REFUSE_NOT_OWNED
    }

    /**
     * A verified NYX process can be alive while the API still refuses connections. That is normal
     * during warm-up and becomes an error once the startup grace period has clearly passed.
     */
    fun statusForOwnedButNotReady(uptimeMillis: Long?): OllamaServerStatus {
        val uptime = uptimeMillis ?: return OllamaServerStatus.STARTING
        return if (uptime < OllamaReadiness.STARTUP_GRACE_MILLIS) {
            OllamaServerStatus.STARTING
        } else {
            OllamaServerStatus.ERROR
        }
    }

    /**
     * Transition table. `ADOPTED` and `START_SUCCEEDED` both mean RUNNING; no event may move a
     * RUNNING server to STARTING (that would imply a duplicate start), and a refused stop restores
     * RUNNING rather than leaving the UI stuck in STOPPING.
     */
    fun nextState(current: OllamaServerStatus, event: OllamaLifecycleEvent): OllamaServerStatus = when (event) {
        OllamaLifecycleEvent.DETECTED_INSTALLED ->
            if (current == OllamaServerStatus.RUNNING || current == OllamaServerStatus.STARTING) current
            else OllamaServerStatus.STOPPED

        OllamaLifecycleEvent.DETECTED_MISSING ->
            if (current == OllamaServerStatus.RUNNING || current == OllamaServerStatus.STARTING) current
            else OllamaServerStatus.NOT_INSTALLED

        OllamaLifecycleEvent.START_REQUESTED -> when (current) {
            OllamaServerStatus.RUNNING,
            OllamaServerStatus.STARTING,
            OllamaServerStatus.STOPPING,
            -> current

            else -> OllamaServerStatus.STARTING
        }

        OllamaLifecycleEvent.START_SUCCEEDED,
        OllamaLifecycleEvent.ADOPTED,
        -> OllamaServerStatus.RUNNING

        OllamaLifecycleEvent.START_FAILED -> OllamaServerStatus.ERROR

        OllamaLifecycleEvent.STOP_REQUESTED -> when (current) {
            OllamaServerStatus.RUNNING,
            OllamaServerStatus.STARTING,
            OllamaServerStatus.ERROR,
            -> OllamaServerStatus.STOPPING

            else -> current
        }

        OllamaLifecycleEvent.STOP_SUCCEEDED -> OllamaServerStatus.STOPPED

        OllamaLifecycleEvent.STOP_FAILED -> OllamaServerStatus.ERROR

        OllamaLifecycleEvent.STOP_REFUSED ->
            if (current == OllamaServerStatus.STOPPING) OllamaServerStatus.RUNNING else current

        OllamaLifecycleEvent.RESET -> OllamaServerStatus.STOPPED
    }
}

/**
 * Readiness budget for a freshly started server. Pure so the timeout behaviour can be tested
 * without sleeping for 45 seconds.
 */
object OllamaReadiness {
    const val DEFAULT_TIMEOUT_MILLIS = 45_000L
    const val INITIAL_INTERVAL_MILLIS = 250L
    const val MAX_INTERVAL_MILLIS = 2_000L

    /** After this long, a live-but-unreachable NYX-owned process is reported as ERROR, not STARTING. */
    const val STARTUP_GRACE_MILLIS = 60_000L

    /** 250 ms, 500 ms, 1 s, 2 s, 2 s, ... — bounded so polling cannot hot-loop or drift. */
    fun pollIntervalMillis(attempt: Int): Long {
        val exponent = (attempt.coerceIn(1, 6) - 1)
        return (INITIAL_INTERVAL_MILLIS shl exponent).coerceAtMost(MAX_INTERVAL_MILLIS)
    }

    fun isTimedOut(elapsedMillis: Long, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean =
        elapsedMillis >= timeoutMillis.coerceAtLeast(1L)

    fun successMessage(adopted: Boolean, pid: Int?): String = when {
        adopted -> "Adopted the Ollama server that was already running."
        pid != null -> "Ollama started with PID $pid and is answering."
        else -> "Ollama is answering."
    }

    fun failureMessage(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        attempts: Int,
        detail: String = "",
        logTail: String = "",
    ): String {
        val seconds = (timeoutMillis / 1_000L).coerceAtLeast(1L)
        val builder = StringBuilder("Ollama did not become ready within $seconds s after $attempts API probe(s).")
        if (detail.isNotBlank()) builder.append(' ').append(detail.trim().take(OllamaProcess.MAX_ERROR_CHARS))
        val tail = OllamaProcess.sanitizeLogTail(logTail)
        if (tail.isNotBlank()) {
            builder.append("\nLast log lines:\n")
            builder.append(tail)
        }
        return builder.toString()
    }
}

package com.nyx.ai.ollama

/**
 * Capabilities reported by the installed `proot-distro`. The detach path is used only when all three
 * commands that make it safe to track and stop are present; otherwise NYX keeps the server in one
 * long-lived foreground PRoot session.
 */
data class ProotSessionCapabilities(
    val detachedLogin: Boolean = false,
    val ps: Boolean = false,
    val kill: Boolean = false,
) {
    val supportsDetachedSession: Boolean get() = detachedLogin && ps && kill
}

/** One row of `proot-distro ps`. The PID is the PRoot session process, not the guest server PID. */
data class ProotSession(
    val pid: Int,
    val container: String,
    val type: String,
    val detached: Boolean,
    val command: String,
)

enum class OllamaLaunchMode {
    /** The Termux host process was detached by the existing host start script. */
    HOST_DETACHED,

    /** A `proot-distro login --detach` session owns the guest server. */
    PROOT_DETACHED,

    /** One long-lived `proot-distro login` command owns the guest server. */
    PROOT_FOREGROUND,
}

/**
 * Persistent identity for the PRoot session that owns a guest Ollama server. A foreground fallback
 * has no independently visible session PID, so it relies on the token written into the guest state
 * directory and on the verified Ollama PID.
 */
data class SavedOllamaSession(
    val token: String,
    val mode: OllamaLaunchMode,
    val sessionPid: Int? = null,
    val sessionStartTicks: Long? = null,
    val container: String = "",
)

enum class OllamaStartupStage {
    PROCESS_GONE,
    GUEST_API_WAITING,
    ANDROID_REACHABILITY_WAITING,
    API_WAITING,
    READY,
    PORT_CONFLICT,
}

/**
 * Pure parsing and script construction for guest Ollama lifecycle work. Keeping this separate from
 * Android makes the dangerous parts testable: which session is new, which PID is safe to signal, and
 * whether a launch command actually keeps the server in the foreground.
 */
object OllamaGuestRuntime {
    const val MAX_SESSION_PID = 4_194_303
    const val MAX_SESSION_TOKEN_CHARS = 80
    const val MAX_SESSION_COMMAND_CHARS = 240
    const val DEFAULT_GUEST_START_TIMEOUT_MILLIS = 180_000L
    const val GUEST_STARTUP_GRACE_MILLIS = 180_000L

    private val tokenPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{7,79}")
    private val sessionRow = Regex(
        """^\s*(\d{1,7})\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*(.*)$""",
    )

    fun isValidToken(token: String): Boolean =
        token.length in 8..MAX_SESSION_TOKEN_CHARS && tokenPattern.matches(token)

    fun parseSessionCapabilities(stdout: String): ProotSessionCapabilities {
        fun marker(key: String): Boolean = OllamaProcess.markerValue(stdout, key) == "1"
        return ProotSessionCapabilities(
            detachedLogin = marker("NYX_PROOT_LOGIN_DETACH"),
            ps = marker("NYX_PROOT_PS"),
            kill = marker("NYX_PROOT_KILL"),
        )
    }

    /** Parses `PID CONTAINER TYPE USER UPTIME COMMAND`; malformed or header rows are ignored. */
    fun parseSessionList(stdout: String): List<ProotSession> {
        val result = mutableListOf<ProotSession>()
        for (line in stdout.replace("\r\n", "\n").lineSequence()) {
            val match = sessionRow.matchEntire(line.trimEnd()) ?: continue
            val pid = match.groupValues[1].toIntOrNull()?.takeIf { it in 2..MAX_SESSION_PID } ?: continue
            val container = match.groupValues[2].takeIf { isSafeSessionName(it) } ?: continue
            val type = match.groupValues[3].takeIf { it.isNotBlank() } ?: continue
            if (type.removeSuffix("*") !in setOf("login", "run")) continue
            val command = match.groupValues[6].trim().take(MAX_SESSION_COMMAND_CHARS)
            result += ProotSession(
                pid = pid,
                container = container,
                type = type.removeSuffix("*"),
                detached = type.endsWith("*"),
                command = command,
            )
        }
        return result
    }

    /** The one session that appeared after this launch and belongs to the requested container. */
    fun newSession(before: List<ProotSession>, after: List<ProotSession>, container: String): ProotSession? {
        val previous = before.map { it.pid }.toSet()
        return after.asSequence()
            .filter { it.pid !in previous && it.container == container }
            .sortedByDescending { it.pid }
            .firstOrNull()
    }

    fun parseGuestApi(stdout: String): Boolean? = when (OllamaProcess.markerValue(stdout, "NYX_GUEST_API")) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** Returns a bounded listener row when the guest can see the Ollama port, or null if unavailable. */
    fun parseGuestListen(stdout: String): String? =
        OllamaProcess.markerValue(stdout, "NYX_GUEST_LISTEN")
            ?.takeIf { it.isNotBlank() && it != "none" }
            ?.take(160)

    fun isPortConflict(logTail: String): Boolean {
        val text = logTail.lowercase()
        return "address already in use" in text ||
            "bind: address already in use" in text ||
            "listen tcp" in text && "bind" in text && "in use" in text
    }

    /**
     * Distinguishes a dead process from a live process that has not opened the guest API, a live
     * guest API that Android still cannot reach, and a ready server. The caller must never stop a
     * process merely because the last stage is not READY.
     */
    fun startupStage(
        processOwned: Boolean,
        guestApiReady: Boolean?,
        androidApiReady: Boolean,
        logTail: String,
    ): OllamaStartupStage = when {
        !processOwned -> OllamaStartupStage.PROCESS_GONE
        isPortConflict(logTail) -> OllamaStartupStage.PORT_CONFLICT
        androidApiReady -> OllamaStartupStage.READY
        guestApiReady == true -> OllamaStartupStage.ANDROID_REACHABILITY_WAITING
        guestApiReady == false -> OllamaStartupStage.GUEST_API_WAITING
        else -> OllamaStartupStage.API_WAITING
    }

    fun stageMessage(stage: OllamaStartupStage, pid: Int?): String = when (stage) {
        OllamaStartupStage.PROCESS_GONE ->
            "The Ollama process exited before it became ready."
        OllamaStartupStage.GUEST_API_WAITING ->
            "The guest Ollama process${pid?.let { " (PID $it)" } ?: ""} is alive; it has not opened its guest API yet."
        OllamaStartupStage.ANDROID_REACHABILITY_WAITING ->
            "The guest API is answering inside the PRoot session, but Android cannot reach it yet. The process was left running."
        OllamaStartupStage.API_WAITING ->
            "The Ollama process${pid?.let { " (PID $it)" } ?: ""} is alive but the API is not ready yet."
        OllamaStartupStage.PORT_CONFLICT ->
            "The log reports an address or port conflict. NYX will not kill a process that may belong to another server."
        OllamaStartupStage.READY -> "Ollama is ready."
    }

    // ------------------------------------------------------------------ host probes

    fun sessionCapabilityScript(prootDistroPath: String): String {
        require(prootDistroPath.startsWith('/') && '\u0000' !in prootDistroPath) { "Unsafe proot-distro path" }
        val path = OllamaProcess.quote(prootDistroPath)
        return """
            # NYX PRoot capability probe: read-only help output, no container command is run.
            export LC_ALL=C
            pd=$path
            login_help=${'$'}("${'$'}pd" login --help 2>&1)
            ps_help=${'$'}("${'$'}pd" ps --help 2>&1)
            kill_help=${'$'}("${'$'}pd" kill --help 2>&1)
            case "${'$'}login_help" in *--detach*) printf 'NYX_PROOT_LOGIN_DETACH=1\n' ;; *) printf 'NYX_PROOT_LOGIN_DETACH=0\n' ;; esac
            case "${'$'}ps_help" in *PID*|*CONTAINER*) printf 'NYX_PROOT_PS=1\n' ;; *) printf 'NYX_PROOT_PS=0\n' ;; esac
            case "${'$'}kill_help" in *PID*|*CONTAINER*|*--all*) printf 'NYX_PROOT_KILL=1\n' ;; *) printf 'NYX_PROOT_KILL=0\n' ;; esac
            exit 0
        """.trimIndent()
    }

    fun sessionListScript(prootDistroPath: String): String {
        require(prootDistroPath.startsWith('/') && '\u0000' !in prootDistroPath) { "Unsafe proot-distro path" }
        return """
            # NYX PRoot session list: read-only.
            export LC_ALL=C
            ${OllamaProcess.quote(prootDistroPath)} ps 2>&1
            exit 0
        """.trimIndent()
    }

    fun sessionStateScript(sessionPid: Int): String {
        require(sessionPid in 2..MAX_SESSION_PID) { "Unsafe PRoot session PID" }
        return """
            # NYX PRoot session identity: read-only /proc inspection.
            export LC_ALL=C
            session_pid=$sessionPid
            if [ ! -d "/proc/${'$'}session_pid" ]; then
              printf 'NYX_SESSION_ALIVE=0\n'
              exit 0
            fi
            printf 'NYX_SESSION_ALIVE=1\n'
            printf 'NYX_SESSION_CMDLINE=%s\n' "${'$'}(tr '\0' ' ' < "/proc/${'$'}session_pid/cmdline" 2>/dev/null | cut -c1-240)"
            printf 'NYX_SESSION_START_TICKS=%s\n' "${'$'}(sed 's/^.*) //' "/proc/${'$'}session_pid/stat" 2>/dev/null | awk '{print ${'$'}20}')"
            exit 0
        """.trimIndent()
    }

    fun parseSessionAlive(stdout: String): Boolean = OllamaProcess.markerValue(stdout, "NYX_SESSION_ALIVE") == "1"

    fun parseSessionStartTicks(stdout: String): Long? =
        OllamaProcess.markerValue(stdout, "NYX_SESSION_START_TICKS")?.toLongOrNull()?.takeIf { it >= 0 }

    fun parseSessionCmdline(stdout: String): String =
        OllamaProcess.markerValue(stdout, "NYX_SESSION_CMDLINE").orEmpty().take(MAX_SESSION_COMMAND_CHARS)

    /** Stops one verified session PID with proot-distro's process-tree teardown. */
    fun sessionKillScript(prootDistroPath: String, sessionPid: Int): String {
        require(prootDistroPath.startsWith('/') && '\u0000' !in prootDistroPath) { "Unsafe proot-distro path" }
        require(sessionPid in 2..MAX_SESSION_PID) { "Unsafe PRoot session PID" }
        val path = OllamaProcess.quote(prootDistroPath)
        return """
            # NYX PRoot session stop: one verified session PID only.
            export LC_ALL=C
            if [ ! -d "/proc/$sessionPid" ]; then
              printf 'NYX_SESSION_STOPPED=1\n'
              exit 0
            fi
            $path kill $sessionPid 2>&1
            status=${'$'}?
            if [ "${'$'}status" -eq 0 ]; then
              printf 'NYX_SESSION_STOPPED=1\n'
            else
              printf 'NYX_SESSION_STOPPED=0\n'
              printf 'NYX_SESSION_STOP_REASON=%s\n' 'proot-distro kill rejected the session'
            fi
            exit 0
        """.trimIndent()
    }

    // ------------------------------------------------------------------ guest scripts

    /**
     * Starts Ollama in the foreground of one PRoot session. It is deliberately not detached inside
     * the guest: PRoot's `--kill-on-exit` tears down tracees when the login process ends. The caller
     * either uses proot-distro's own `--detach` on the whole session or keeps this login command alive.
     */
    fun guestStartScript(ollamaBinary: String, sessionToken: String): String {
        require(ollamaBinary.startsWith('/') && '\u0000' !in ollamaBinary) { "Unsafe Ollama path" }
        require(isValidToken(sessionToken)) { "Unsafe session token" }
        return """
            # NYX guest Ollama start: one foreground server owned by this PRoot session.
            export LC_ALL=C
            export OLLAMA_HOST=127.0.0.1:11434
            umask 077
            ollama_bin=${OllamaProcess.quote(ollamaBinary)}
            session_token=${OllamaProcess.quote(sessionToken)}
            nyx_dir="${'$'}{HOME:?}/.nyx"
            log_file="${'$'}nyx_dir/ollama.log"
            pid_file="${'$'}nyx_dir/ollama.pid"
            if [ ! -x "${'$'}ollama_bin" ]; then
              printf 'NYX_OLLAMA_START=0\n'
              printf 'NYX_OLLAMA_ERROR=%s\n' 'the ollama binary is missing or not executable'
              exit 3
            fi
            if ! mkdir -p -- "${'$'}nyx_dir" 2>/dev/null || [ ! -w "${'$'}nyx_dir" ]; then
              printf 'NYX_OLLAMA_START=0\n'
              printf 'NYX_OLLAMA_ERROR=%s\n' 'the NYX state directory could not be created'
              exit 4
            fi
            if [ -f "${'$'}pid_file" ]; then
              saved_pid=${'$'}(sed -n '1s/^NYX1[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p' "${'$'}pid_file" 2>/dev/null)
              saved_start=${'$'}(sed -n '1s/^NYX1[[:space:]]\{1,\}[0-9]\{1,\}[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p' "${'$'}pid_file" 2>/dev/null)
              case "${'$'}saved_start" in ''|*[!0-9]*) saved_start='' ;; esac
              if [ -n "${'$'}saved_pid" ] && [ -n "${'$'}saved_start" ] && [ -d "/proc/${'$'}saved_pid" ]; then
                saved_cmd=${'$'}(tr '\0' ' ' < "/proc/${'$'}saved_pid/cmdline" 2>/dev/null)
                saved_now=${'$'}(sed 's/^.*) //' "/proc/${'$'}saved_pid/stat" 2>/dev/null | awk '{print ${'$'}20}')
                case "${'$'}saved_cmd" in
                  *ollama*)
                    if [ "${'$'}saved_now" = "${'$'}saved_start" ]; then
                      printf 'NYX_OLLAMA_START=0\n'
                      printf 'NYX_OLLAMA_DUPLICATE=1\n'
                      printf 'NYX_OLLAMA_PID=%s\n' "${'$'}saved_pid"
                      printf 'NYX_OLLAMA_START_TICKS=%s\n' "${'$'}saved_start"
                      printf 'NYX_OLLAMA_ERROR=%s\n' 'a verified NYX-owned Ollama process is already running'
                      exit 0
                    fi
                    ;;
                esac
              fi
              : > "${'$'}pid_file"
            fi
            if ! : > "${'$'}log_file" 2>/dev/null; then
              printf 'NYX_OLLAMA_START=0\n'
              printf 'NYX_OLLAMA_ERROR=%s\n' 'the Ollama log file could not be opened'
              exit 5
            fi
            printf 'NYX_SESSION_TOKEN=%s\n' "${'$'}session_token" >> "${'$'}log_file"
            server_pid=${'$'}${'$'}
            server_start=${'$'}(sed 's/^.*) //' "/proc/${'$'}server_pid/stat" 2>/dev/null | awk '{print ${'$'}20}')
            case "${'$'}server_start" in
              ''|*[!0-9]*)
                printf 'NYX_OLLAMA_START=0\n'
                printf 'NYX_OLLAMA_ERROR=%s\n' 'the kernel start token could not be read'
                exit 8
                ;;
            esac
            printf 'NYX1 %s %s %s\n' "${'$'}server_pid" "${'$'}server_start" "${'$'}(date +%s)" > "${'$'}pid_file"
            printf 'NYX_SESSION_TOKEN=%s\n' "${'$'}session_token"
            printf 'NYX_OLLAMA_START=1\n'
            printf 'NYX_OLLAMA_PID=%s\n' "${'$'}server_pid"
            printf 'NYX_OLLAMA_START_TICKS=%s\n' "${'$'}server_start"
            exec "${'$'}ollama_bin" serve >> "${'$'}log_file" 2>&1 < /dev/null
        """.trimIndent()
    }

    /** Confirms the guest-local API, which is the only view that can distinguish a bound port from an Android network failure. */
    fun guestApiProbeScript(ollamaBinary: String): String {
        require(ollamaBinary.startsWith('/') && '\u0000' !in ollamaBinary) { "Unsafe Ollama path" }
        return """
            # NYX guest API probe: read-only and local to the guest network namespace.
            export LC_ALL=C
            export OLLAMA_HOST=127.0.0.1:11434
            ollama_bin=${OllamaProcess.quote(ollamaBinary)}
            if [ ! -x "${'$'}ollama_bin" ]; then
              printf 'NYX_GUEST_API=0\n'
              exit 0
            fi
            if "${'$'}ollama_bin" list >/dev/null 2>&1; then
              printf 'NYX_GUEST_API=1\n'
            else
              printf 'NYX_GUEST_API=0\n'
            fi
            listen_row=${'$'}({ command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null; command -v netstat >/dev/null 2>&1 && netstat -ltn 2>/dev/null; } | grep -E '(:|\.)11434[[:space:]]' | head -n 1)
            printf 'NYX_GUEST_LISTEN=%s\n' "${'$'}{listen_row:-none}"
            exit 0
        """.trimIndent()
    }
    private fun isSafeSessionName(value: String): Boolean =
        value.length in 1..40 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))
}

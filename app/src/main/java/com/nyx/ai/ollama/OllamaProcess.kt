package com.nyx.ai.ollama

import java.net.URI

/**
 * How a recorded PID relates to the process that currently owns that number.
 *
 * A PID is only a number. Between the moment NYX saved it and the moment it acts on it, the
 * original process may have exited and the kernel may have recycled the same number for something
 * entirely unrelated. [OWNED] is the only verdict that may ever be signalled.
 */
enum class OllamaOwnership {
    /** `/proc/<pid>` matches both the recorded `ollama serve` command line and start token. */
    OWNED,

    /** The recorded PID now belongs to a different process; the record is stale. */
    PID_REUSED,

    /** The recorded PID exists but is not `ollama serve`. */
    NOT_OLLAMA,

    /** No process exists at the recorded PID; it exited. */
    MISSING,

    /** Ownership could not be established. Never treated as safe to signal. */
    UNKNOWN,
}

/** A PID together with the kernel start token that makes the number unambiguous. */
data class SavedOllamaProcess(
    val pid: Int,
    val startTimeTicks: Long,
    val startedAtMillis: Long = 0L,
)

/** Result of the read-only installation probe. */
data class OllamaDetection(
    val installed: Boolean,
    val path: String? = null,
    val version: String = "",
    /** Why the probe could not produce an answer, if it failed; empty when the probe really ran. */
    val error: String = "",
    /** Where the binary was found: the Termux host or a verified PRoot guest. */
    val target: OllamaTarget? = null,
    /** `uname -m` in the same environment as the binary. */
    val architecture: String = "",
    /** False when the binary exists but cannot execute or does not identify as Ollama. */
    val compatible: Boolean = true,
)

/** Result of the detached start script. */
data class OllamaStartOutcome(
    val started: Boolean,
    val pid: Int? = null,
    val startTimeTicks: Long? = null,
    val duplicate: Boolean = false,
    val error: String = "",
)

/** Result of the verified stop script. */
data class OllamaStopOutcome(
    val stopped: Boolean,
    val killed: Boolean = false,
    val refused: Boolean = false,
    val reason: String = "",
)

/**
 * One isolated host observation: the saved record, whether that exact process is still the one NYX
 * started, its resource usage, and the tail of the server log.
 */
data class OllamaHostSnapshot(
    val record: SavedOllamaProcess? = null,
    val ownership: OllamaOwnership = OllamaOwnership.UNKNOWN,
    val cmdline: String = "",
    val rssKb: Long? = null,
    val uptimeMillis: Long? = null,
    val logTail: String = "",
)

/**
 * Pure parsing, ownership verification, and script generation for an Ollama server running on the
 * Termux host.
 *
 * Nothing here touches Android, the network, or the file system: every function takes text and
 * returns data, so the security-relevant decisions (is this PID still ours?) and the exact shell
 * text NYX is about to run are unit-testable without a device.
 */
object OllamaProcess {
    const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"
    const val DEFAULT_PORT = 11434
    const val APP_DIRECTORY = "\$HOME/.nyx"
    const val LOG_FILE = "\$HOME/.nyx/ollama.log"
    const val PID_FILE = "\$HOME/.nyx/ollama.pid"

    /** Version tag of the PID record format; a record with any other tag is never trusted. */
    const val RECORD_TAG = "NYX1"

    const val MAX_LOG_TAIL_LINES = 40
    const val MAX_LOG_TAIL_CHARS = 2_000
    const val MAX_PID = 4_194_303
    const val CLOCK_TICKS_PER_SECOND = 100L
    const val MAX_ERROR_CHARS = 300

    private const val RECORD_BEGIN = "NYX_OLLAMA_RECORD_BEGIN"
    private const val RECORD_END = "NYX_OLLAMA_RECORD_END"
    private const val LOG_BEGIN = "NYX_OLLAMA_LOG_BEGIN"
    private const val LOG_END = "NYX_OLLAMA_LOG_END"

    // ------------------------------------------------------------------ endpoints

    /** Returns a syntactically valid HTTP endpoint, or the loopback default; never invents a host. */
    fun normalizeEndpoint(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return DEFAULT_ENDPOINT
        val trimmed = value.trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return DEFAULT_ENDPOINT
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return DEFAULT_ENDPOINT
        if (uri.host.isNullOrBlank()) return DEFAULT_ENDPOINT
        if (trimmed.length > 2_000 || '\u0000' in trimmed) return DEFAULT_ENDPOINT
        return trimmed
    }

    /** Only a loopback endpoint can name a server this device is able to start and stop. */
    fun isLoopbackEndpoint(endpoint: String): Boolean {
        if (endpoint.isBlank()) return false
        val host = runCatching { URI(normalizeEndpoint(endpoint)).host }.getOrNull()
            ?.lowercase()
            ?.trim('[', ']')
            ?: return false
        return host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1" || host.startsWith("127.")
    }

    // ------------------------------------------------------------------ shell quoting

    /** POSIX single-quote escaping for embedding a value in a shell word. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ PID records

    /** `NYX1 <pid> <starttime> <startedAtMillis>`; the start token is what defeats PID reuse. */
    fun encodeRecord(saved: SavedOllamaProcess): String =
        "$RECORD_TAG ${saved.pid} ${saved.startTimeTicks} ${saved.startedAtMillis}"

    /**
     * Decodes a saved record. Anything that is not a well-formed NYX record is rejected outright
     * rather than repaired, so a corrupt or foreign file can never become a kill target.
     */
    fun decodeRecord(text: String?): SavedOllamaProcess? {
        val tokens = text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        if (tokens.size !in 3..4) return null
        if (!tokens[0].equals(RECORD_TAG, ignoreCase = true)) return null
        val pid = tokens[1].toIntOrNull()?.takeIf { it in 2..MAX_PID } ?: return null
        val start = tokens[2].toLongOrNull()?.takeIf { it in 0..MAX_START_TICKS } ?: return null
        val startedAt = if (tokens.size == 4) {
            tokens[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        } else {
            0L
        }
        return SavedOllamaProcess(pid, start, startedAt)
    }

    private const val MAX_START_TICKS = 100_000_000_000_000L

    // ------------------------------------------------------------------ output parsing

    /** Parses a PID printed by NYX's own script; rejects zero, negatives, and implausible values. */
    fun parsePid(stdout: String): Int? {
        markerValue(stdout, "NYX_OLLAMA_PID")?.let { return parsePidToken(it) }
        for (line in stdout.lineSequence()) {
            val candidate = line.trim()
            if (candidate.isEmpty()) continue
            parsePidToken(candidate)?.let { return it }
        }
        return null
    }

    private fun parsePidToken(raw: String): Int? {
        val token = raw.trim().substringBefore(' ').substringBefore('\t')
        if (token.isEmpty() || token.length > 7 || token.any { it !in '0'..'9' }) return null
        val value = token.toIntOrNull() ?: return null
        return value.takeIf { it in 2..MAX_PID }
    }

    /**
     * Reads field 22 (`starttime`) of `/proc/<pid>/stat`.
     *
     * The `comm` field is wrapped in parentheses and may itself contain spaces and parentheses, so
     * parsing starts after the *last* `)` instead of splitting the whole line.
     */
    fun parseStartTimeFromStat(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close == stat.lastIndex) return null
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        // Field 3 (state) is index 0 here, so field 22 (starttime) is index 19.
        val raw = fields.getOrNull(19) ?: return null
        return raw.toLongOrNull()?.takeIf { it >= 0 }
    }

    /** Splits a NUL-separated `/proc/<pid>/cmdline` into argv, dropping the trailing empty token. */
    fun parseCmdline(raw: String): List<String> =
        raw.split('\u0000').map { it.trim() }.dropLastWhile { it.isEmpty() }

    /**
     * True only when argv[0] is the `ollama` binary and the first non-flag argument is `serve`.
     * A shell that merely mentions "ollama serve" in its arguments is not the server.
     */
    fun isOllamaServeCmdline(raw: String): Boolean {
        val argv = parseCmdline(raw)
        if (argv.isEmpty()) return false
        if (argv[0].substringAfterLast('/') != "ollama") return false
        val firstArgument = argv.drop(1).firstOrNull { it.isNotEmpty() && !it.startsWith("-") } ?: return false
        return firstArgument == "serve"
    }

    /**
     * `NYX_OLLAMA_CMDLINE` is flattened to spaces by the probe so it survives line-based output.
     * This variant classifies that flattened text without pretending the argv boundaries are known.
     */
    fun isOllamaServeText(text: String): Boolean {
        val tokens = text.replace('\u0000', ' ').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens[0].substringAfterLast('/') != "ollama") return false
        return tokens.drop(1).firstOrNull { !it.startsWith("-") } == "serve"
    }

    /** Classifies the recorded PID against the live evidence. */
    fun ownership(
        saved: SavedOllamaProcess?,
        cmdline: String,
        stat: String,
    ): OllamaOwnership {
        if (saved == null) return OllamaOwnership.UNKNOWN
        if (cmdline.isBlank()) return OllamaOwnership.MISSING
        if (!isOllamaServeText(cmdline)) return OllamaOwnership.NOT_OLLAMA
        val start = parseStartTimeFromStat(stat) ?: return OllamaOwnership.UNKNOWN
        return if (start == saved.startTimeTicks) OllamaOwnership.OWNED else OllamaOwnership.PID_REUSED
    }

    fun parseDetection(stdout: String, target: OllamaTarget? = null): OllamaDetection {
        val installed = markerValue(stdout, "NYX_OLLAMA_INSTALLED") == "1"
        val path = markerValue(stdout, "NYX_OLLAMA_PATH")?.takeIf { it.startsWith('/') && it.length <= 4_096 }
        val version = markerValue(stdout, "NYX_OLLAMA_VERSION").orEmpty().take(160)
        val error = markerValue(stdout, "NYX_OLLAMA_ERROR").orEmpty().take(MAX_ERROR_CHARS)
        val architecture = markerValue(stdout, "NYX_OLLAMA_ARCH").orEmpty().take(80)
        val compatible = markerValue(stdout, "NYX_OLLAMA_COMPATIBLE")?.let { it != "0" } ?: installed
        return OllamaDetection(installed, path, version, error, target, architecture, compatible)
    }

    fun parseStartOutcome(stdout: String): OllamaStartOutcome {
        val started = markerValue(stdout, "NYX_OLLAMA_START") == "1"
        val duplicate = markerValue(stdout, "NYX_OLLAMA_DUPLICATE") == "1"
        val pid = parsePid(stdout)
        val startTicks = markerValue(stdout, "NYX_OLLAMA_START_TICKS")?.toLongOrNull()
        val error = markerValue(stdout, "NYX_OLLAMA_ERROR").orEmpty().take(MAX_ERROR_CHARS)
        return OllamaStartOutcome(started, pid, startTicks, duplicate, error)
    }

    fun parseStopOutcome(stdout: String): OllamaStopOutcome {
        val stopped = markerValue(stdout, "NYX_OLLAMA_STOPPED") == "1"
        val killed = markerValue(stdout, "NYX_OLLAMA_KILLED") == "1"
        val refused = markerValue(stdout, "NYX_OLLAMA_STOP_REFUSED") == "1"
        val reason = markerValue(stdout, "NYX_OLLAMA_STOP_REASON").orEmpty().take(MAX_ERROR_CHARS)
        return OllamaStopOutcome(stopped, killed, refused, reason)
    }

    fun parseHostSnapshot(stdout: String): OllamaHostSnapshot {
        val record = decodeRecord(block(stdout, RECORD_BEGIN, RECORD_END))
        val alive = markerValue(stdout, "NYX_OLLAMA_ALIVE") == "1"
        val ownedMarker = markerValue(stdout, "NYX_OLLAMA_OWNED") == "1"
        val cmdline = markerValue(stdout, "NYX_OLLAMA_CMDLINE").orEmpty()
        val startTicks = markerValue(stdout, "NYX_OLLAMA_START")?.toLongOrNull()
        val procRss = markerValue(stdout, "NYX_OLLAMA_RSS_KB")?.toLongOrNull()
        val psRss = markerValue(stdout, "NYX_OLLAMA_PS_RSS_KB")?.toLongOrNull()
        val uptimeSeconds = markerValue(stdout, "NYX_OLLAMA_UPTIME_SECONDS")?.toDoubleOrNull()
        val psElapsed = markerValue(stdout, "NYX_OLLAMA_PS_ELAPSED")?.let(::parseElapsedMillis)
        val ownership = when {
            record == null -> OllamaOwnership.UNKNOWN
            ownedMarker -> OllamaOwnership.OWNED
            !alive -> OllamaOwnership.MISSING
            !isOllamaServeText(cmdline) -> OllamaOwnership.NOT_OLLAMA
            startTicks != null && startTicks != record.startTimeTicks -> OllamaOwnership.PID_REUSED
            else -> OllamaOwnership.UNKNOWN
        }
        val uptime = computeUptimeMillis(uptimeSeconds, startTicks) ?: psElapsed
        return OllamaHostSnapshot(
            record = record,
            ownership = ownership,
            cmdline = cmdline,
            rssKb = (procRss ?: psRss)?.takeIf { it > 0 },
            uptimeMillis = uptime,
            logTail = sanitizeLogTail(block(stdout, LOG_BEGIN, LOG_END)),
        )
    }

    /** `(systemUptimeSeconds - startTicks / HZ) * 1000`, never negative. */
    fun computeUptimeMillis(
        uptimeSeconds: Double?,
        startTimeTicks: Long?,
        ticksPerSecond: Long = CLOCK_TICKS_PER_SECOND,
    ): Long? {
        if (uptimeSeconds == null || startTimeTicks == null) return null
        if (!uptimeSeconds.isFinite() || uptimeSeconds < 0.0) return null
        if (ticksPerSecond <= 0L) return null
        val startedSeconds = startTimeTicks.toDouble() / ticksPerSecond.toDouble()
        val elapsed = uptimeSeconds - startedSeconds
        if (!elapsed.isFinite() || elapsed < 0.0) return null
        return (elapsed * 1_000.0).toLong()
    }

    /** Parses `ps -o etime=` output such as `03:04`, `01:02:03`, or `2-03:04:05`. */
    fun parseElapsedMillis(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val days: Long
        val rest: String
        if ('-' in text) {
            days = text.substringBefore('-').toLongOrNull() ?: return null
            rest = text.substringAfter('-')
        } else {
            days = 0L
            rest = text
        }
        val parts = rest.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.trim().toLongOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        val (hours, minutes, seconds) = when (numbers.size) {
            2 -> Triple(0L, numbers[0], numbers[1])
            else -> Triple(numbers[0], numbers[1], numbers[2])
        }
        if (minutes > 59 || seconds > 59) return null
        val total = (((days * 24) + hours) * 60 + minutes) * 60 + seconds
        return total * 1_000L
    }

    /** Keeps the last [maxLines] non-blank lines, strips control characters, and caps length. */
    fun sanitizeLogTail(
        text: String,
        maxLines: Int = MAX_LOG_TAIL_LINES,
        maxChars: Int = MAX_LOG_TAIL_CHARS,
    ): String {
        if (text.isBlank()) return ""
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val printable = normalized.filter { it == '\n' || it == '\t' || !it.isISOControl() }
        val lines = printable.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(maxLines.coerceAtLeast(1))
        val joined = lines.joinToString("\n").trim()
        val limit = maxChars.coerceAtLeast(1)
        return if (joined.length <= limit) joined else "…" + joined.takeLast(limit - 1)
    }

    /** The value after `KEY=` on the first matching line, or null. */
    fun markerValue(stdout: String, key: String): String? {
        val prefix = "$key="
        for (line in stdout.lineSequence()) {
            if (line.startsWith(prefix)) return line.removePrefix(prefix).trim()
        }
        return null
    }

    /** Text between a begin marker and its end marker, excluding both marker lines. */
    fun block(stdout: String, begin: String, end: String): String {
        val lines = stdout.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.trim() == begin }
        if (start < 0) return ""
        val finish = (start + 1 until lines.size).firstOrNull { lines[it].trim() == end } ?: return ""
        return lines.subList(start + 1, finish).joinToString("\n")
    }

    // ------------------------------------------------------------------ scripts

    /**
     * Read-only detection. It runs in exactly one environment: the isolated Termux host or a
     * verified PRoot guest. Discovery decides which environments to probe and never trusts an
     * interactive shell profile to choose for it.
     */
    fun detectScript(): String = """
        # NYX Ollama detection: read-only, one explicitly selected environment.
        export LC_ALL=C
        ollama_bin=${'$'}(command -v ollama 2>/dev/null | head -n 1)
        case "${'$'}ollama_bin" in
          /*) ;;
          *) printf 'NYX_OLLAMA_INSTALLED=0\n'; exit 0 ;;
        esac
        printf 'NYX_OLLAMA_INSTALLED=1\n'
        printf 'NYX_OLLAMA_PATH=%s\n' "${'$'}ollama_bin"
        printf 'NYX_OLLAMA_ARCH=%s\n' "${'$'}(uname -m 2>/dev/null)"
        version_output=${'$'}("${'$'}ollama_bin" --version 2>&1)
        version_status=${'$'}?
        version=$(printf '%s\n' "${'$'}version_output" | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)
        if [ -z "${'$'}version" ]; then
          version=$(printf '%s\n' "${'$'}version_output" | sed -n '1p')
        fi
        printf 'NYX_OLLAMA_VERSION=%s\n' "${'$'}version"
        if [ "${'$'}version_status" -eq 0 ] && [ -n "${'$'}version" ]; then
          printf 'NYX_OLLAMA_COMPATIBLE=1\n'
        else
          printf 'NYX_OLLAMA_COMPATIBLE=0\n'
          printf 'NYX_OLLAMA_ERROR=%s\n' 'the ollama binary exists but could not identify itself'
        fi
        exit 0
    """.trimIndent()

    /**
     * Starts a detached server. stdin is closed and both streams are appended to the NYX log, so
     * the process survives the Termux RUN_COMMAND session that created it. `setsid` is used when
     * present; the child reports its own `$$` *after* any fork, so the saved PID is the real one.
     *
     * A live, verified record in the PID file aborts the script, which makes duplicate starts
     * impossible even if two callers race.
     */
    fun startScript(ollamaBinary: String, useSetsid: Boolean = true): String = """
        # NYX Ollama start: one detached server, stdin closed, duplicate starts refused.
        export LC_ALL=C
        umask 077
        ollama_bin=${quote(ollamaBinary)}
        nyx_dir="${'$'}{HOME:?}/.nyx"
        log_file="${'$'}nyx_dir/ollama.log"
        pid_file="${'$'}nyx_dir/ollama.pid"
        pid_tmp="${'$'}nyx_dir/ollama.pid.tmp"
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
          if [ -n "${'$'}saved_pid" ] && [ -d "/proc/${'$'}saved_pid" ]; then
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
        : > "${'$'}pid_tmp"
        if ${if (useSetsid) "command -v setsid >/dev/null 2>&1" else "false"}; then
          setsid sh -c 'printf "%s\n" "${'$'}${'$'}" > "${'$'}1"; exec "${'$'}0" serve >>"${'$'}2" 2>&1 < /dev/null' "${'$'}ollama_bin" "${'$'}pid_tmp" "${'$'}log_file" &
        else
          nohup sh -c 'printf "%s\n" "${'$'}${'$'}" > "${'$'}1"; exec "${'$'}0" serve >>"${'$'}2" 2>&1 < /dev/null' "${'$'}ollama_bin" "${'$'}pid_tmp" "${'$'}log_file" &
        fi
        attempt=0
        new_pid=
        while [ "${'$'}attempt" -lt 25 ]; do
          if [ -s "${'$'}pid_tmp" ]; then
            new_pid=${'$'}(sed -n '1p' "${'$'}pid_tmp" 2>/dev/null | tr -cd '0-9')
            [ -n "${'$'}new_pid" ] && break
          fi
          sleep 0.2
          attempt=${'$'}((attempt + 1))
        done
        if [ -z "${'$'}new_pid" ] || [ ! -d "/proc/${'$'}new_pid" ]; then
          printf 'NYX_OLLAMA_START=0\n'
          printf 'NYX_OLLAMA_ERROR=%s\n' 'the detached Ollama process did not report a usable PID'
          exit 6
        fi
        new_cmd=${'$'}(tr '\0' ' ' < "/proc/${'$'}new_pid/cmdline" 2>/dev/null)
        case "${'$'}new_cmd" in
          *ollama*serve*) ;;
          *)
            printf 'NYX_OLLAMA_START=0\n'
            printf 'NYX_OLLAMA_ERROR=%s\n' 'the launched PID is not an ollama serve process'
            exit 7
            ;;
        esac
        new_start=${'$'}(sed 's/^.*) //' "/proc/${'$'}new_pid/stat" 2>/dev/null | awk '{print ${'$'}20}')
        case "${'$'}new_start" in
          ''|*[!0-9]*)
            printf 'NYX_OLLAMA_START=0\n'
            printf 'NYX_OLLAMA_ERROR=%s\n' 'the kernel start token could not be read'
            exit 8
            ;;
        esac
        printf 'NYX1 %s %s %s\n' "${'$'}new_pid" "${'$'}new_start" "${'$'}(date +%s)" > "${'$'}pid_file"
        : > "${'$'}pid_tmp"
        printf 'NYX_OLLAMA_START=1\n'
        printf 'NYX_OLLAMA_PID=%s\n' "${'$'}new_pid"
        printf 'NYX_OLLAMA_START_TICKS=%s\n' "${'$'}new_start"
        exit 0
    """.trimIndent()

    /**
     * Stops exactly one verified process. The PID and start token are re-checked inside the shell,
     * immediately before the signal, so a recycled PID is never signalled even if Kotlin's view was
     * stale. There is deliberately no pattern matching, process-group, or "all ollama" kill here.
     */
    fun stopScript(expected: SavedOllamaProcess): String {
        require(expected.pid in 2..MAX_PID) { "Refusing to build a stop script for an implausible PID" }
        require(expected.startTimeTicks in 0..MAX_START_TICKS) { "Refusing to build a stop script without a start token" }
        return """
            # NYX Ollama stop: verified single PID only. Never a pattern or process-group kill.
            export LC_ALL=C
            nyx_dir="${'$'}{HOME:?}/.nyx"
            pid_file="${'$'}nyx_dir/ollama.pid"
            expected_pid=${expected.pid}
            expected_start=${expected.startTimeTicks}
            if [ -f "${'$'}pid_file" ]; then
              file_pid=${'$'}(sed -n '1s/^NYX1[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p' "${'$'}pid_file" 2>/dev/null)
              if [ -n "${'$'}file_pid" ] && [ "${'$'}file_pid" != "${'$'}expected_pid" ]; then
                printf 'NYX_OLLAMA_STOP_REFUSED=1\n'
                printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the PID file no longer matches the requested process'
                exit 4
              fi
            fi
            if [ ! -d "/proc/${'$'}expected_pid" ]; then
              : > "${'$'}pid_file"
              printf 'NYX_OLLAMA_STOPPED=1\n'
              printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the process had already exited'
              exit 0
            fi
            live_cmd=${'$'}(tr '\0' ' ' < "/proc/${'$'}expected_pid/cmdline" 2>/dev/null)
            case "${'$'}live_cmd" in
              *ollama*) ;;
              *)
                printf 'NYX_OLLAMA_STOP_REFUSED=1\n'
                printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the PID is no longer an ollama process'
                exit 5
                ;;
            esac
            case "${'$'}live_cmd" in
              *serve*) ;;
              *)
                printf 'NYX_OLLAMA_STOP_REFUSED=1\n'
                printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the PID is not an ollama serve process'
                exit 5
                ;;
            esac
            live_start=${'$'}(sed 's/^.*) //' "/proc/${'$'}expected_pid/stat" 2>/dev/null | awk '{print ${'$'}20}')
            if [ "${'$'}live_start" != "${'$'}expected_start" ]; then
              printf 'NYX_OLLAMA_STOP_REFUSED=1\n'
              printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the PID was reused by another process'
              exit 6
            fi
            if ! kill -TERM "${'$'}expected_pid" 2>/dev/null; then
              printf 'NYX_OLLAMA_STOP_REFUSED=1\n'
              printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the TERM signal could not be delivered'
              exit 7
            fi
            attempt=0
            while [ "${'$'}attempt" -lt 50 ]; do
              [ -d "/proc/${'$'}expected_pid" ] || break
              sleep 0.2
              attempt=${'$'}((attempt + 1))
            done
            killed=0
            if [ -d "/proc/${'$'}expected_pid" ]; then
              still_start=${'$'}(sed 's/^.*) //' "/proc/${'$'}expected_pid/stat" 2>/dev/null | awk '{print ${'$'}20}')
              if [ "${'$'}still_start" != "${'$'}expected_start" ]; then
                : > "${'$'}pid_file"
                printf 'NYX_OLLAMA_STOP_REFUSED=1\n'
                printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the PID was reused while waiting for shutdown'
                exit 8
              fi
              kill -KILL "${'$'}expected_pid" 2>/dev/null
              killed=1
              sleep 0.2
            fi
            : > "${'$'}pid_file"
            printf 'NYX_OLLAMA_STOPPED=1\n'
            if [ "${'$'}killed" = "1" ]; then
              printf 'NYX_OLLAMA_KILLED=1\n'
              printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the server ignored TERM and was terminated'
            else
              printf 'NYX_OLLAMA_STOP_REASON=%s\n' 'the server stopped after TERM'
            fi
            exit 0
        """.trimIndent()
    }

    /**
     * One bounded read-only observation of the saved record, its live ownership, resource usage, and
     * the log tail. Used by refresh, stop planning, and resource reporting.
     */
    fun stateScript(): String = """
        # NYX Ollama state: read-only inspection of the saved record and its process.
        export LC_ALL=C
        nyx_dir="${'$'}{HOME:?}/.nyx"
        log_file="${'$'}nyx_dir/ollama.log"
        pid_file="${'$'}nyx_dir/ollama.pid"
        printf 'NYX_OLLAMA_RECORD_BEGIN\n'
        if [ -f "${'$'}pid_file" ]; then
          sed -n '1p' "${'$'}pid_file" 2>/dev/null
        fi
        printf 'NYX_OLLAMA_RECORD_END\n'
        saved_pid=${'$'}(sed -n '1s/^NYX1[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p' "${'$'}pid_file" 2>/dev/null)
        saved_start=${'$'}(sed -n '1s/^NYX1[[:space:]]\{1,\}[0-9]\{1,\}[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p' "${'$'}pid_file" 2>/dev/null)
        if [ -n "${'$'}saved_pid" ] && [ -d "/proc/${'$'}saved_pid" ]; then
          printf 'NYX_OLLAMA_ALIVE=1\n'
          live_cmd=${'$'}(tr '\0' ' ' < "/proc/${'$'}saved_pid/cmdline" 2>/dev/null | cut -c1-240)
          printf 'NYX_OLLAMA_CMDLINE=%s\n' "${'$'}live_cmd"
          live_start=${'$'}(sed 's/^.*) //' "/proc/${'$'}saved_pid/stat" 2>/dev/null | awk '{print ${'$'}20}')
          printf 'NYX_OLLAMA_START=%s\n' "${'$'}live_start"
          case "${'$'}live_cmd" in
            *ollama*serve*)
              if [ "${'$'}live_start" = "${'$'}saved_start" ]; then
                printf 'NYX_OLLAMA_OWNED=1\n'
              fi
              ;;
          esac
          rss_pages=${'$'}(awk '{print ${'$'}2}' "/proc/${'$'}saved_pid/statm" 2>/dev/null)
          case "${'$'}rss_pages" in
            ''|*[!0-9]*) ;;
            *) printf 'NYX_OLLAMA_RSS_KB=%s\n' "${'$'}((rss_pages * 4))" ;;
          esac
          uptime_seconds=${'$'}(cut -d' ' -f1 /proc/uptime 2>/dev/null)
          printf 'NYX_OLLAMA_UPTIME_SECONDS=%s\n' "${'$'}uptime_seconds"
          if [ -z "${'$'}rss_pages" ]; then
            ps -o rss= -o etime= -p "${'$'}saved_pid" 2>/dev/null | awk 'NR==1 {print "NYX_OLLAMA_PS_RSS_KB=" ${'$'}1; print "NYX_OLLAMA_PS_ELAPSED=" ${'$'}2}'
          fi
        else
          printf 'NYX_OLLAMA_ALIVE=0\n'
        fi
        printf 'NYX_OLLAMA_LOG_BEGIN\n'
        if [ -f "${'$'}log_file" ]; then
          tail -n 40 "${'$'}log_file" 2>/dev/null
        fi
        printf 'NYX_OLLAMA_LOG_END\n'
        exit 0
    """.trimIndent()
}

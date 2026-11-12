package com.nyx.ai.terminal

/** Outcome of checking one tool in a terminal environment. */
enum class CapabilityState {
    /** The tool was found at an absolute path and its probe command actually ran. */
    VERIFIED,

    /** No file was found for the tool name. */
    MISSING,

    /** The tool exists but the operating system denied access to it. */
    PERMISSION_DENIED,

    /** The tool exists but cannot run here: not executable, wrong format, or a broken loader. */
    INCOMPATIBLE,

    /** No probe evidence exists for this tool. Never treated as success. */
    NOT_CHECKED,
}

/**
 * One observed tool. [path] and [version] are evidence produced by a probe, never guessed.
 * For states other than [CapabilityState.VERIFIED], [detail] carries the bounded failure text and
 * [version] stays null because the probe output was a diagnostic, not a version string.
 */
data class ToolCapability(
    val name: String,
    val state: CapabilityState,
    val path: String? = null,
    val version: String? = null,
    val detail: String? = null,
    /** Where the executable really comes from; a guest must not use inherited Termux binaries. */
    val origin: ExecutableOrigin = ExecutableOrigin.UNKNOWN,
)

/**
 * A point-in-time snapshot of what a terminal environment can actually do. Every field is observed
 * evidence: absent tool entries mean "not checked", never "available".
 *
 * @param environmentId stable identity of the environment, for example `termux_host` or `proot:debian`.
 * @param kind environment family: `termux_host` or `proot:<alias>`.
 * @param checkedAt epoch milliseconds of the probe that produced this snapshot.
 * @param source how the snapshot was obtained, for example `probe` or `manual`.
 */
data class EnvironmentCapabilities(
    val environmentId: String,
    val kind: String,
    val checkedAt: Long,
    val tools: Map<String, ToolCapability> = emptyMap(),
    val storageFreeBytes: Long? = null,
    val workspaceAccessible: Boolean? = null,
    val source: String = "probe",
) {
    /** Returns the recorded state for [name], or [CapabilityState.NOT_CHECKED] when absent. */
    fun stateOf(name: String): CapabilityState = tools[name]?.state ?: CapabilityState.NOT_CHECKED

    /**
     * The environment's own package manager, proven by a verified executable. A guest that inherited
     * Termux's `pkg` reports null rather than pretending APT is available.
     */
    val packageManager: String?
        get() = when {
            kind.startsWith("proot:") && stateOf("apt") == CapabilityState.VERIFIED -> "apt"
            kind == "termux_host" && stateOf("pkg") == CapabilityState.VERIFIED -> "pkg"
            else -> null
        }

    /**
     * True when this snapshot can no longer be trusted. A snapshot taken in the future is always
     * stale because the clock moved backwards, and a snapshot older than [ttlMillis] is stale too.
     */
    fun isStale(nowMillis: Long, ttlMillis: Long = DEFAULT_TTL_MILLIS): Boolean {
        if (nowMillis < checkedAt) return true
        val elapsed = nowMillis - checkedAt
        return elapsed < 0 || elapsed > ttlMillis
    }

    /** Returns a copy that records the workspace accessibility observed by a separate probe. */
    fun withWorkspaceAccess(accessible: Boolean): EnvironmentCapabilities =
        copy(workspaceAccessible = accessible)

    companion object {
        /** Snapshots older than fifteen minutes are re-probed before a tool-dependent step. */
        const val DEFAULT_TTL_MILLIS = 15 * 60 * 1000L
    }
}

/**
 * Builds and parses the read-only capability probe. The probe never installs, downloads, or elevates:
 * it resolves each tool with `command -v`, requires a real executable file, and runs one bounded
 * version command. Callers should apply a timeout because JVM-based tools start slowly.
 */
object CapabilityProbe {
    /** Tools whose presence NYX may need for terminal and development objectives. */
    val TOOL_NAMES: List<String> = listOf(
        "sh",
        "git",
        "java",
        "javac",
        "kotlinc",
        "gradle",
        "sdkmanager",
        "adb",
        "clang",
        "gcc",
        "make",
        "cmake",
        "python3",
        "node",
        "npm",
        "pkg",
        "apt",
        "dpkg",
    )

    const val CAP_PREFIX = "NYX_CAP:"
    const val STORAGE_PREFIX = "NYX_STORAGE_FREE="
    const val WORKSPACE_PREFIX = "NYX_WORKSPACE="
    const val MAX_VERSION_CHARS = 80
    const val MAX_PATH_CHARS = 4_096
    const val MAX_TOOL_ENTRIES = 64

    private const val MAX_LINE_CHARS = 8_192
    private const val MAX_TOOL_NAME_CHARS = 32
    private val TOOL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,$MAX_TOOL_NAME_CHARS}")
    private val ORIGIN_TOKENS = ExecutableOrigin.entries.map { it.name }.toSet()
    private val STATE_BY_TOKEN: Map<String, CapabilityState> =
        CapabilityState.entries.associateBy { it.name }

    /**
     * POSIX shell probe. Prints one `NYX_CAP:<tool>:<state>:<path>:<version>` line per tool plus
     * `NYX_STORAGE_FREE=<bytes>` and `NYX_WORKSPACE=<0|1>`. `NYX_WS` optionally selects the
     * directory used for the write check; the current directory is used otherwise.
     */
    fun script(environmentKind: String = "termux_host"): String =
        "NYX_ENV_KIND=" + shellQuote(environmentKind) + "\n" + SCRIPT

    /**
     * Parses probe output. Malformed lines are ignored, repeated tools keep the last observation,
     * and versions are sanitized to a single printable-ASCII line of at most
     * [MAX_VERSION_CHARS] characters. Tools without a line stay absent, so [EnvironmentCapabilities.stateOf]
     * reports [CapabilityState.NOT_CHECKED].
     */
    fun parse(stdout: String, environmentId: String, kind: String, nowMillis: Long): EnvironmentCapabilities {
        val tools = LinkedHashMap<String, ToolCapability>()
        var storageFreeBytes: Long? = null
        var workspaceAccessible: Boolean? = null
        for (rawLine in stdout.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.length > MAX_LINE_CHARS) continue
            when {
                line.startsWith(CAP_PREFIX) -> {
                    val tool = parseToolLine(line) ?: continue
                    if (tools.size < MAX_TOOL_ENTRIES || tools.containsKey(tool.name)) tools[tool.name] = tool
                }

                line.startsWith(STORAGE_PREFIX) -> {
                    val bytes = line.removePrefix(STORAGE_PREFIX).trim().toLongOrNull()
                    if (bytes != null && bytes >= 0L) storageFreeBytes = bytes
                }

                line.startsWith(WORKSPACE_PREFIX) -> when (line.removePrefix(WORKSPACE_PREFIX).trim()) {
                    "1" -> workspaceAccessible = true
                    "0" -> workspaceAccessible = false
                }
            }
        }
        return EnvironmentCapabilities(
            environmentId = environmentId,
            kind = kind,
            checkedAt = nowMillis,
            tools = tools,
            storageFreeBytes = storageFreeBytes,
            workspaceAccessible = workspaceAccessible,
            source = "probe",
        )
    }

    /**
     * Parses a single capability line. The state token is authoritative, but a `VERIFIED` claim
     * without an absolute executable path is rejected because a name alone is not evidence.
     */
    private fun parseToolLine(line: String): ToolCapability? {
        val parts = line.removePrefix(CAP_PREFIX).split(':', limit = 5)
        if (parts.size < 4) return null
        val name = parts[0].trim()
        if (!TOOL_NAME.matches(name)) return null
        val state = STATE_BY_TOKEN[parts[1].trim().uppercase()] ?: return null
        val path = parts[2].trim().ifEmpty { null }?.takeIf(::isPlausiblePath)
        if (parts[2].trim().isNotEmpty() && path == null) return null
        if (state == CapabilityState.VERIFIED && path == null) return null
        if ((state == CapabilityState.MISSING || state == CapabilityState.NOT_CHECKED) && path != null) return null
        val originToken = parts.getOrNull(3)?.trim()?.uppercase()
        val hasOrigin = parts.size == 5 && originToken in ORIGIN_TOKENS
        val origin = if (hasOrigin) ExecutableOrigin.valueOf(originToken!!) else ExecutableOrigin.UNKNOWN
        // Four-field legacy lines may contain colons in the diagnostic/version; rejoin them instead
        // of misreading the first fragment as an origin token.
        val text = sanitizeVersion(if (hasOrigin) parts[4] else parts.drop(3).joinToString(":"))
        return when (state) {
            CapabilityState.VERIFIED -> ToolCapability(name, state, path, text, null, origin)
            else -> ToolCapability(name, state, path, null, text, origin)
        }
    }

    private fun isPlausiblePath(path: String): Boolean =
        path.length in 2..MAX_PATH_CHARS && path.startsWith('/') && path.none { it == '\u0000' }

    /** Keeps the first line, strips non-printable and non-ASCII characters, and caps the length. */
    private fun sanitizeVersion(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val builder = StringBuilder(minOf(raw.length, MAX_VERSION_CHARS))
        for (character in raw) {
            if (builder.length >= MAX_VERSION_CHARS) break
            when {
                character == '\n' || character == '\r' || character == '\t' -> {
                    if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
                }

                character.code in 0x20..0x7E -> builder.append(character)
            }
        }
        return builder.toString().trim().replace(MULTIPLE_SPACES, " ").ifBlank { null }
    }

    private val MULTIPLE_SPACES = Regex(" {2,}")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val SCRIPT: String = """
        # NYX capability probe. Read-only: resolves tools and prints bounded evidence.
        # NYX_WS optionally selects the directory used for the write-access check.
        export LC_ALL=C
        emit() { printf 'NYX_CAP:%s:%s:%s:%s:%s\n' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" "${'$'}5"; }
        classify_origin() {
          case "${'$'}NYX_ENV_KIND" in
            termux_host) printf '%s' TERMUX_HOST ;;
            proot:*)
              case "${'$'}1" in
                /data/data/com.termux/files/usr/*) printf '%s' GUEST_INHERITED_TERMUX ;;
                /system/*|/apex/*) printf '%s' ANDROID_NATIVE ;;
                /*) printf '%s' GUEST_NATIVE ;;
                *) printf '%s' UNKNOWN ;;
              esac ;;
            *) printf '%s' UNKNOWN ;;
          esac
        }
        version_args() {
          case "${'$'}1" in
            java|javac) printf '%s' '-version' ;;
            adb) printf '%s' 'version' ;;
            *) printf '%s' '--version' ;;
          esac
        }
        first_line() { printf '%s\n' "${'$'}1" | head -n 1 | tr -d '\r'; }
        probe() {
          _name=${'$'}1
          _path=${'$'}(command -v "${'$'}_name" 2>/dev/null)
          case "${'$'}_path" in
            /*) ;;
            '')
              # command -v only reports executable files, so look for a present but unusable one.
              _path=
              _old_ifs=${'$'}IFS
              IFS=:
              for _dir in ${'$'}PATH; do
                if [ -n "${'$'}_dir" ] && [ -f "${'$'}_dir/${'$'}_name" ]; then
                  _path=${'$'}_dir/${'$'}_name
                  break
                fi
              done
              IFS=${'$'}_old_ifs
              if [ -n "${'$'}_path" ]; then
                emit "${'$'}_name" INCOMPATIBLE "${'$'}_path" "${'$'}(classify_origin "${'$'}_path")" 'present but not executable'
              else
                emit "${'$'}_name" MISSING '' UNKNOWN ''
              fi
              return 0 ;;
            *) emit "${'$'}_name" MISSING '' UNKNOWN ''; return 0 ;;
          esac
          if [ ! -e "${'$'}_path" ] || [ ! -f "${'$'}_path" ] || [ ! -x "${'$'}_path" ]; then
            emit "${'$'}_name" INCOMPATIBLE "${'$'}_path" "${'$'}(classify_origin "${'$'}_path")" ''; return 0
          fi
          _origin=${'$'}(classify_origin "${'$'}_path")
          if [ "${'$'}_name" = sh ]; then
            # The shell running this probe has no portable version flag; its path is the evidence.
            _out=
          else
            _out=${'$'}("${'$'}_path" ${'$'}(version_args "${'$'}_name") </dev/null 2>&1)
          fi
          case "${'$'}_out" in
            *"Permission denied"*|*"permission denied"*|*EACCES*|*"Operation not permitted"*)
              emit "${'$'}_name" PERMISSION_DENIED "${'$'}_path" "${'$'}_origin" "${'$'}(first_line "${'$'}_out")"
              return 0 ;;
          esac
          case "${'$'}_out" in
            *"cannot execute"*|*"Exec format error"*|*"bad ELF interpreter"*|*"not found"*|*"No such file or directory"*)
              emit "${'$'}_name" INCOMPATIBLE "${'$'}_path" "${'$'}_origin" "${'$'}(first_line "${'$'}_out")"
              return 0 ;;
          esac
          if [ "${'$'}NYX_ENV_KIND" != termux_host ] && [ "${'$'}_origin" = GUEST_INHERITED_TERMUX ]; then
            emit "${'$'}_name" INCOMPATIBLE "${'$'}_path" "${'$'}_origin" 'inherited Termux binary; install the guest-native package'
            return 0
          fi
          emit "${'$'}_name" VERIFIED "${'$'}_path" "${'$'}_origin" "${'$'}(first_line "${'$'}_out")"
        }
        for _tool in sh git java javac kotlinc gradle sdkmanager adb clang gcc make cmake python3 node npm pkg apt dpkg; do
          probe "${'$'}_tool"
        done
        if command -v df >/dev/null 2>&1; then
          _free=${'$'}(df -Pk . 2>/dev/null | awk 'NR==2 && ${'$'}4 ~ /^[0-9]+${'$'}/ {printf "%.0f", ${'$'}4 * 1024}')
          case "${'$'}_free" in
            ''|*[!0-9]*) ;;
            *) printf 'NYX_STORAGE_FREE=%s\n' "${'$'}_free" ;;
          esac
        fi
        _ws=${'$'}{NYX_WS:-}
        [ -n "${'$'}_ws" ] || _ws=${'$'}PWD
        _writable=0
        if [ -d "${'$'}_ws" ]; then
          _probe_file="${'$'}_ws/.nyx_cap_probe_${'$'}${'$'}"
          if ( : > "${'$'}_probe_file" ) 2>/dev/null; then
            _writable=1
            rm -f "${'$'}_probe_file" 2>/dev/null
          fi
        fi
        printf 'NYX_WORKSPACE=%s\n' "${'$'}_writable"
        exit 0
    """.trimIndent()
}

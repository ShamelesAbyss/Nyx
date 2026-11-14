package com.nyx.ai.terminal

/** A request to use [path] as the workspace of [environment]. Authorization is explicit. */
data class WorkspaceRequest(
    val environment: TerminalEnvironment,
    val path: String,
    val authorized: Boolean,
)

/** Result of checking a workspace request. */
sealed interface WorkspaceCheck {
    /**
     * The path is usable. [normalizedPath] is the accepted canonical path for syntax checks and the
     * canonical real path reported by the probe for on-device checks.
     */
    data class Valid(val normalizedPath: String) : WorkspaceCheck

    /** The request is unusable. [action] is a short imperative hint that can be shown to the user. */
    data class Invalid(val code: String, val message: String, val action: String) : WorkspaceCheck
}

/**
 * Validates workspace paths before any command runs. Syntax validation is pure and cheap; the probe
 * is a separate step because only the device can prove that a directory exists and is writable.
 *
 * Check precedence: character and shape checks, then the reserved-root and guest-filesystem policy,
 * then authorization. A structurally invalid path therefore never reaches the device.
 */
object WorkspaceValidator {
    const val MIN_PATH_CHARS = 2
    const val MAX_PATH_CHARS = 1_024

    private const val MAX_DETAIL_CHARS = 200

    /** Letters, digits, `.`, `_`, `-`, `+`, `@`, `/` and spaces. Nothing that a shell could expand. */
    private val ALLOWED_PATH_CHARS = Regex("[A-Za-z0-9._+@/ -]+")

    private val RESERVED_ROOTS = setOf("/", "/root", "/data", "/system", "/sdcard")

    /** Android shared-storage locations that do not exist inside a PRoot guest. */
    private val ANDROID_PREFIXES = listOf(
        "/storage/emulated",
        "/storage/self",
        "/sdcard",
        "/mnt/sdcard",
        "/data/data",
        "/data/app",
    )

    private val FLAG_KEYS = setOf("NYX_WS_EXISTS", "NYX_WS_DIR", "NYX_WS_WRITABLE", "NYX_WS_REALPATH")

    /**
     * Validates a request without touching the device. A [WorkspaceCheck.Valid] result still needs
     * [probeScript] before it can be trusted: syntax never proves that a directory exists.
     */
    fun validateSyntax(request: WorkspaceRequest): WorkspaceCheck {
        val path = request.path
        if (path.any { it == '\u0000' }) {
            return invalid(
                "path_contains_nul",
                "The workspace path contains a NUL character.",
                "Remove control characters from the path.",
            )
        }
        if (!path.startsWith('/')) {
            return invalid(
                "path_not_absolute",
                "The workspace path must be absolute and start with '/'.",
                "Enter an absolute path such as /home/user/project.",
            )
        }
        if (path in RESERVED_ROOTS) {
            return invalid(
                "path_too_broad",
                "'$path' is a system root, not a project folder.",
                "Choose a project subdirectory.",
            )
        }
        if (path.length < MIN_PATH_CHARS) {
            return invalid(
                "path_too_short",
                "The workspace path is too short to be a project folder.",
                "Choose a project folder.",
            )
        }
        if (path.length > MAX_PATH_CHARS) {
            return invalid(
                "path_too_long",
                "The workspace path exceeds $MAX_PATH_CHARS characters.",
                "Use a shorter project path.",
            )
        }
        if (path.endsWith('/')) {
            return invalid(
                "path_trailing_slash",
                "The workspace path must not end with '/'.",
                "Remove the trailing slash.",
            )
        }
        if (path.contains("//")) {
            return invalid(
                "path_redundant_separator",
                "The workspace path contains an empty path segment ('//').",
                "Remove the duplicate slashes.",
            )
        }
        if (path.contains("..")) {
            return invalid(
                "path_parent_reference",
                "The workspace path must not contain '..'.",
                "Use a canonical path without parent references.",
            )
        }
        if (!ALLOWED_PATH_CHARS.matches(path)) {
            return invalid(
                "path_invalid_characters",
                "The workspace path contains unsupported characters; '~', '\$' and shell metacharacters are rejected.",
                "Use letters, digits, '.', '_', '-', '+', '@' and '/'.",
            )
        }
        if (path.split('/').any { it == "." || it.lowercase() in setOf(".ssh", ".aws", ".gnupg", ".git", ".nyx-checkpoints") } ||
            listOf("/proc", "/sys", "/dev", "/etc", "/system").any { path == it || path.startsWith("$it/") }) {
            return invalid("private_or_system_path", "Private credential and system trees are not project workspaces.", "Choose an explicitly approved project directory.")
        }
        if (request.environment == TerminalEnvironment.DEBIAN_PROOT && isAndroidPath(path)) {
            return invalid(
                "android_path_in_guest",
                "Android shared-storage paths such as /sdcard and /data/data do not exist inside the Debian " +
                    "guest, which has its own filesystem.",
                "Use a path inside the guest, such as /root/projects/myapp.",
            )
        }
        if (!request.authorized) {
            return invalid(
                "not_authorized",
                "The user has not authorized this workspace for the objective.",
                "Ask the user to approve the workspace folder.",
            )
        }
        return WorkspaceCheck.Valid(path)
    }

    /**
     * Builds the on-device workspace probe. It reports existence, directory kind, a real write test
     * through a temporary file that is deleted immediately, and the canonical real path.
     */
    fun probeScript(path: String): String = """
        # NYX workspace probe. Read-only apart from a temporary write test that is removed at once.
        export LC_ALL=C
        ws=${shellQuote(path)}
        if [ -e "${'$'}ws" ]; then printf 'NYX_WS_EXISTS=1\n'; else printf 'NYX_WS_EXISTS=0\n'; fi
        if [ -d "${'$'}ws" ]; then printf 'NYX_WS_DIR=1\n'; else printf 'NYX_WS_DIR=0\n'; fi
        _real=${'$'}(cd "${'$'}ws" 2>/dev/null && pwd -P)
        _writable=0
        if [ "${'$'}_real" = "${'$'}ws" ] && [ ! -L "${'$'}ws" ] && [ -d "${'$'}ws" ] && [ -w "${'$'}ws" ]; then
          if _probe_file=${'$'}(umask 077; mktemp "${'$'}ws/.nyx-ws-probe-XXXXXXXX") 2>/dev/null; then
            _writable=1
            rm -f -- "${'$'}_probe_file" 2>/dev/null || exit 1
          fi
        fi
        printf 'NYX_WS_WRITABLE=%s\n' "${'$'}_writable"
        printf 'NYX_WS_REALPATH=%s\n' "${'$'}_real"
        exit 0
    """.trimIndent()

    /**
     * Interprets probe output. A non-zero or unknown exit code is a failed probe; otherwise the
     * reported flags decide between a missing path, a non-directory, an unwritable folder, and a
     * canonical path.
     */
    fun parseProbe(stdout: String, stderr: String, exitCode: Int?, expectedPath: String? = null,
                   environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST): WorkspaceCheck {
        if (exitCode != 0) {
            val detail = sanitizeDetail(stderr.ifBlank { stdout })
            val suffix = if (detail == null) "" else ": $detail"
            return invalid(
                "probe_failed",
                "The workspace probe did not complete$suffix.",
                "Check that the folder exists and that Termux can run commands.",
            )
        }
        val flags = parseFlags(stdout)
            ?: return invalid("probe_failed", "The workspace probe returned duplicate fields.", "Run the workspace check again.")
        when (flags["NYX_WS_EXISTS"].toFlag()) {
            null -> return invalid(
                "probe_failed",
                "The workspace probe returned no existence result.",
                "Run the workspace check again.",
            )

            false -> return invalid(
                "missing",
                "The workspace folder does not exist.",
                "Create the folder or choose another path.",
            )

            true -> Unit
        }
        when (flags["NYX_WS_DIR"].toFlag()) {
            null -> return invalid(
                "probe_failed",
                "The workspace probe returned no directory result.",
                "Run the workspace check again.",
            )

            false -> return invalid(
                "not_directory",
                "The workspace path is a file, not a folder.",
                "Choose a folder.",
            )

            true -> Unit
        }
        when (flags["NYX_WS_WRITABLE"].toFlag()) {
            null -> return invalid(
                "probe_failed",
                "The workspace probe returned no write result.",
                "Run the workspace check again.",
            )

            false -> return invalid(
                "not_writable",
                "NYX cannot write inside the workspace folder.",
                "Grant write access or choose another folder.",
            )

            true -> Unit
        }
        val realPath = flags["NYX_WS_REALPATH"].orEmpty()
        if (!isCanonicalPath(realPath)) {
            return invalid(
                "probe_failed",
                "The workspace probe did not report a canonical absolute path.",
                "Run the workspace check again.",
            )
        }
        val syntax = validateSyntax(WorkspaceRequest(environment, realPath, authorized = true))
        if (syntax !is WorkspaceCheck.Valid) return syntax
        if (expectedPath != null && realPath != expectedPath) {
            return invalid("canonical_scope_changed", "The resolved workspace differs from the approved path; no alias is adopted.", "Select and explicitly approve the canonical project directory.")
        }
        return WorkspaceCheck.Valid(realPath)
    }

    private fun parseFlags(stdout: String): Map<String, String>? {
        val flags = HashMap<String, String>()
        for (line in stdout.lineSequence()) {
            val text = line.trim()
            val separator = text.indexOf('=')
            if (separator <= 0) continue
            val key = text.substring(0, separator)
            if (key !in FLAG_KEYS) continue
            if (key in flags) return null
            flags[key] = text.substring(separator + 1).trim()
        }
        return flags
    }

    private fun isAndroidPath(path: String): Boolean =
        ANDROID_PREFIXES.any { path == it || path.startsWith("$it/") }

    private fun isCanonicalPath(path: String): Boolean =
        path.length in MIN_PATH_CHARS..MAX_PATH_CHARS &&
            path.startsWith('/') &&
            !path.endsWith('/') &&
            path.none { it == '\u0000' } &&
            path.none { it.isISOControl() }

    private fun String?.toFlag(): Boolean? = when (this) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** Quotes a value for POSIX sh. The accepted path alphabet never needs this, but callers may not validate. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun sanitizeDetail(text: String): String? {
        if (text.isEmpty()) return null
        val builder = StringBuilder()
        for (character in text) {
            if (builder.length >= MAX_DETAIL_CHARS) break
            when {
                character == '\n' || character == '\r' || character == '\t' -> {
                    if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
                }

                character.code in 0x20..0x7E -> builder.append(character)
            }
        }
        return builder.toString().trim().ifBlank { null }
    }

    private fun invalid(code: String, message: String, action: String): WorkspaceCheck.Invalid =
        WorkspaceCheck.Invalid(code, message, action)
}

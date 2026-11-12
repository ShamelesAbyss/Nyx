package com.nyx.ai.terminal

/** A request to create and register a new project workspace inside [environment]. */
data class WorkspaceProvisionRequest(
    val environment: TerminalEnvironment,
    val baseDirectory: String,
    val projectName: String,
    val guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS,
)

/** Outcome of a real workspace creation attempt. */
sealed interface WorkspaceProvisionResult {
    data class Created(val path: String, val detail: String) : WorkspaceProvisionResult
    data class Failed(val code: String, val message: String) : WorkspaceProvisionResult
}

/**
 * Creates project directories without asking the user to run `mkdir`. The leaf directory is created
 * with an atomic `mkdir` (never `mkdir -p` on the leaf), so an existing directory can never be
 * silently adopted. A collision is reported by the shell and retried with `-2`, `-3`, ...; only
 * after the filesystem confirms our own new directory is it registered with the app.
 */
object WorkspaceProvisioner {
    const val DEFAULT_DEBIAN_BASE = "/root/nyx-workspaces"
    const val DEFAULT_HOST_FOLDER = "nyx-workspaces"
    const val MAX_NAME_CHARS = 40
    const val MAX_COLLISION_SUFFIX = 20

    private val SAFE_NAME = Regex("[a-z0-9][a-z0-9-]{0,38}[a-z0-9]")
    private val TRIM = Regex("[^a-z0-9]+")

    /** Returns the conventional workspace base for [environment], using [home] when available. */
    fun defaultBase(environment: TerminalEnvironment, home: String?): String? = when (environment) {
        TerminalEnvironment.DEBIAN_PROOT -> DEFAULT_DEBIAN_BASE
        TerminalEnvironment.TERMUX_HOST -> home?.trimEnd('/')?.takeIf { it.startsWith('/') }
            ?.let { "$it/$DEFAULT_HOST_FOLDER" }
    }

    /** Reduces a human project name to a safe single path segment. */
    fun sanitizeName(raw: String): String {
        val lowered = raw.lowercase().trim()
        val slug = lowered.replace(TRIM, "-").trim('-').take(MAX_NAME_CHARS).trim('-')
        val safe = slug.ifBlank { "project" }
        return if (SAFE_NAME.matches(safe)) safe else "project"
    }

    /** The exact path tried for a one-based [attempt]. Attempt 1 has no numeric suffix. */
    fun candidatePath(baseDirectory: String, projectName: String, attempt: Int): String {
        val base = baseDirectory.trimEnd('/')
        val name = sanitizeName(projectName)
        return if (attempt <= 1) "$base/$name" else "$base/$name-$attempt"
    }

    /**
     * Legacy planning helper retained for callers that already know which paths exist. Provisioning
     * does not trust this list: it uses an atomic filesystem check for every candidate.
     */
    fun planPath(baseDirectory: String, projectName: String, existing: Set<String> = emptySet()): String {
        val base = baseDirectory.trimEnd('/')
        val name = sanitizeName(projectName)
        val first = "$base/$name"
        if (first !in existing) return first
        for (index in 2..MAX_COLLISION_SUFFIX) {
            val candidate = "$base/$name-$index"
            if (candidate !in existing) return candidate
        }
        return "$base/$name-${System.currentTimeMillis().toString().takeLast(6)}"
    }

    /**
     * One bounded command that creates the leaf atomically, verifies it is our new directory, and
     * prints its real path. `NYX_WS_EXISTS=1` is the only retry signal; every other failure stops.
     */
    fun createScript(path: String): String = """
        # NYX workspace creation. The parent may be created, but the leaf is atomic.
        export LC_ALL=C
        umask 077
        ws=${shellQuote(path)}
        parent=${'$'}(dirname -- "${'$'}ws") || {
          printf 'NYX_WS_CREATED=0\n'
          printf 'NYX_WS_ERROR=%s\n' 'invalid parent path'
          exit 1
        }
        if ! mkdir -p -- "${'$'}parent" 2>/dev/null; then
          printf 'NYX_WS_CREATED=0\n'
          printf 'NYX_WS_ERROR=%s\n' 'parent directory could not be created'
          exit 1
        fi
        if [ -e "${'$'}ws" ] || [ -L "${'$'}ws" ]; then
          printf 'NYX_WS_EXISTS=1\n'
          exit 3
        fi
        if ! mkdir -- "${'$'}ws" 2>/dev/null; then
          if [ -e "${'$'}ws" ] || [ -L "${'$'}ws" ]; then
            printf 'NYX_WS_EXISTS=1\n'
            exit 3
          fi
          printf 'NYX_WS_CREATED=0\n'
          printf 'NYX_WS_ERROR=%s\n' 'mkdir failed'
          exit 1
        fi
        if [ ! -d "${'$'}ws" ] || [ ! -w "${'$'}ws" ]; then
          printf 'NYX_WS_CREATED=0\n'
          printf 'NYX_WS_ERROR=%s\n' 'directory is not writable'
          exit 1
        fi
        _real=${'$'}(cd "${'$'}ws" 2>/dev/null && pwd -P)
        printf 'NYX_WS_CREATED=1\n'
        printf 'NYX_WS_REALPATH=%s\n' "${'$'}_real"
        exit 0
    """.trimIndent()

    /**
     * Creates [request] and verifies it through [TerminalRepository]. The caller must already have
     * obtained the applicable autonomy authorization; this method does not grant permissions.
     */
    suspend fun provision(
        repository: TerminalRepository,
        request: WorkspaceProvisionRequest,
        objectiveId: String? = null,
    ): WorkspaceProvisionResult {
        val baseCheck = WorkspaceValidator.validateSyntax(
            WorkspaceRequest(request.environment, request.baseDirectory, authorized = true)
        )
        if (baseCheck is WorkspaceCheck.Invalid) {
            return WorkspaceProvisionResult.Failed(baseCheck.code, baseCheck.message)
        }
        for (attempt in 1..MAX_COLLISION_SUFFIX) {
            val path = candidatePath(request.baseDirectory, request.projectName, attempt)
            val syntax = WorkspaceValidator.validateSyntax(
                WorkspaceRequest(request.environment, path, authorized = true)
            )
            if (syntax is WorkspaceCheck.Invalid) {
                return WorkspaceProvisionResult.Failed(syntax.code, syntax.message)
            }
            val result = repository.execute(
                command = createScript(path),
                environment = request.environment,
                workingDirectory = null,
                timeoutSeconds = 30,
                approvedByUser = true,
                objectiveId = objectiveId,
                useTimeoutBinary = false,
                guestAlias = request.guestAlias,
            )
            val exists = result.stdout.lineSequence()
                .firstOrNull { it.startsWith("NYX_WS_EXISTS=") }
                ?.substringAfter('=')
                ?.trim()
            if (exists == "1") {
                // The filesystem already owned this name. Try the next suffix; never adopt it.
                continue
            }
            val created = result.stdout.lineSequence()
                .firstOrNull { it.startsWith("NYX_WS_CREATED=") }
                ?.substringAfter('=')
                ?.trim()
            if (created != "1") {
                val detail = result.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: result.message
                    ?: "the environment did not confirm the directory"
                return WorkspaceProvisionResult.Failed("create_failed", detail.take(300))
            }
            val verified = repository.validateWorkspace(request.environment, path, request.guestAlias)
            return when (verified) {
                is WorkspaceCheck.Valid -> WorkspaceProvisionResult.Created(
                    verified.normalizedPath,
                    "Created as a new directory and write-verified.",
                )
                is WorkspaceCheck.Invalid -> WorkspaceProvisionResult.Failed(verified.code, verified.message)
            }
        }
        return WorkspaceProvisionResult.Failed(
            "collision_limit",
            "No unused workspace name was available after $MAX_COLLISION_SUFFIX attempts. Nothing was reused.",
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

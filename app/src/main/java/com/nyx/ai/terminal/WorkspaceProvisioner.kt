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
 * Creates project directories without asking the user to run `mkdir`. Planning is pure and
 * collision-safe; [provision] performs one bounded shell command and then re-probes the directory,
 * so a claimed creation is only reported after the filesystem confirms it.
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

    /**
     * Builds an absolute path under [baseDirectory], adding `-2`, `-3`, ... when [existing] already
     * contains the candidate. No path is ever reused, so an existing project is never overwritten.
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

    /** One bounded command that creates the directory, verifies writability, and prints its real path. */
    fun createScript(path: String): String = """
        # NYX workspace creation. Creates only the requested directory tree.
        export LC_ALL=C
        umask 077
        ws=${shellQuote(path)}
        if ! mkdir -p -- "${'$'}ws" 2>/dev/null; then
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
        val path = planPath(request.baseDirectory, request.projectName)
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
            is WorkspaceCheck.Valid -> WorkspaceProvisionResult.Created(verified.normalizedPath, "Created and write-verified.")
            is WorkspaceCheck.Invalid -> WorkspaceProvisionResult.Failed(verified.code, verified.message)
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

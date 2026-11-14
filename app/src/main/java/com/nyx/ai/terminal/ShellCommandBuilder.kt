package com.nyx.ai.terminal

/**
 * Manual shell compatibility construction. Model/structured tools use [controlledRequest], never
 * these login-shell wrappers. Passing one argv string prevents host interpolation, not shell authority.
 * Working directories are validated and quoted independently.
 */
object ShellCommandBuilder {
    const val TERMUX_SHELL = "\$PREFIX/bin/bash"
    const val PROOT_SHELL = "/bin/bash"

    private val safePath = Regex("""^[A-Za-z0-9._/@+=:,~$ -]+$""")

    fun isValidWorkingDirectory(path: String): Boolean =
        path.isNotBlank() &&
            path.length <= 4_096 &&
            safePath.matches(path) &&
            !path.contains("..") &&
            (path.startsWith("/") || path.startsWith("~/") || path.startsWith("\$HOME") || path.startsWith("\$PREFIX"))

    /** POSIX single-quote escaping for embedding a value in a shell word. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Manual wrapper with coreutils timeout. Wrapper completion/exit 124 or 137 does not prove all
     * descendants stopped; cancellation and uncertainty still require retained ownership/review.
     */
    fun wrap(
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Long?,
        useTimeoutBinary: Boolean,
    ): String {
        val body = if (workingDirectory.isNullOrBlank()) {
            command
        } else {
            require(isValidWorkingDirectory(workingDirectory)) { "Unsafe working directory." }
            "cd -- ${quote(workingDirectory)} && $command"
        }
        if (!useTimeoutBinary || timeoutSeconds == null || timeoutSeconds <= 0) return body
        val seconds = timeoutSeconds.coerceIn(1, TerminalCommandRequest.MAX_TIMEOUT_MILLIS / 1000L)
        return "timeout -k 5 $seconds bash -lc ${quote(body)}"
    }

    fun controlledRequest(command: String, environment: TerminalEnvironment, workingDirectory: String?, timeoutSeconds: Long,
                          useTimeoutBinary: Boolean, distroAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS): TerminalCommandRequest =
        ControlledShell.request(command, environment, workingDirectory, timeoutSeconds, useTimeoutBinary, distroAlias)

    fun hostRequest(
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Long?,
        useTimeoutBinary: Boolean,
        authorized: Boolean = true,
    ): TerminalCommandRequest = TerminalCommandRequest(
        environment = TerminalEnvironment.TERMUX_HOST,
        commandPath = TERMUX_SHELL,
        arguments = listOf("-lc", wrap(command, workingDirectory, timeoutSeconds, useTimeoutBinary)),
        workingDirectory = null,
        authorized = authorized,
        timeoutMillis = (timeoutSeconds ?: 120L).coerceIn(1, TerminalCommandRequest.MAX_TIMEOUT_MILLIS / 1000L) * 1000L,
    )

    fun prootRequest(
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Long?,
        useTimeoutBinary: Boolean,
        authorized: Boolean = true,
        distroAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS,
    ): TerminalCommandRequest = TerminalCommandRequest(
        environment = TerminalEnvironment.DEBIAN_PROOT,
        commandPath = PROOT_SHELL,
        arguments = listOf("-lc", wrap(command, workingDirectory, timeoutSeconds, useTimeoutBinary)),
        workingDirectory = null,
        distroAlias = distroAlias,
        authorized = authorized,
        timeoutMillis = (timeoutSeconds ?: 120L).coerceIn(1, TerminalCommandRequest.MAX_TIMEOUT_MILLIS / 1000L) * 1000L,
    )
}

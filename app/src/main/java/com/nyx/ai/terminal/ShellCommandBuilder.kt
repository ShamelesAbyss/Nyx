package com.nyx.ai.terminal

/**
 * Builds the argv actually handed to Termux. Commands run through bash so users and agents can use
 * normal shell syntax, but the command string is passed as a single argv element: no host-side string
 * interpolation occurs. Working directories are validated and quoted independently.
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
     * Wraps a command with an optional working directory and an optional hard timeout enforced by
     * coreutils `timeout`, which terminates the child process rather than merely abandoning the wait.
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
        val seconds = timeoutSeconds.coerceIn(1, 600)
        return "timeout -k 5 $seconds bash -lc ${quote(body)}"
    }

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
        timeoutMillis = ((timeoutSeconds ?: 120L) * 1000L).coerceIn(1_000L, TerminalCommandRequest.MAX_TIMEOUT_MILLIS),
    )

    fun prootRequest(
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Long?,
        useTimeoutBinary: Boolean,
        authorized: Boolean = true,
    ): TerminalCommandRequest = TerminalCommandRequest(
        environment = TerminalEnvironment.DEBIAN_PROOT,
        commandPath = PROOT_SHELL,
        arguments = listOf("-lc", wrap(command, workingDirectory, timeoutSeconds, useTimeoutBinary)),
        workingDirectory = null,
        authorized = authorized,
        timeoutMillis = ((timeoutSeconds ?: 120L) * 1000L).coerceIn(1_000L, TerminalCommandRequest.MAX_TIMEOUT_MILLIS),
    )
}

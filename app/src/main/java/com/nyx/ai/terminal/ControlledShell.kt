package com.nyx.ai.terminal

/** Sanitize BEFORE bash starts. env/bash/coreutils remain trusted package executables, not OS isolation. */
object ControlledShell {
    private fun bin(environment: TerminalEnvironment) = if (environment == TerminalEnvironment.TERMUX_HOST) "${HostShell.TERMUX_PREFIX}/bin" else "/usr/bin"
    fun executable(environment: TerminalEnvironment, name: String): String {
        require(name.matches(Regex("[a-z0-9]+")))
        return "${bin(environment)}/$name"
    }
    fun request(command: String, environment: TerminalEnvironment, workingDirectory: String?, timeoutSeconds: Long,
                useTimeoutBinary: Boolean, guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS): TerminalCommandRequest {
        require(command.isNotBlank() && '\u0000' !in command)
        val path = if (environment == TerminalEnvironment.TERMUX_HOST) "${bin(environment)}:/system/bin" else "/usr/bin:/bin"
        val shell = if (environment == TerminalEnvironment.TERMUX_HOST) "${bin(environment)}/bash" else "/bin/bash"
        val home = if (environment == TerminalEnvironment.TERMUX_HOST) HostShell.TERMUX_HOME else "/root"
        val temporary = if (environment == TerminalEnvironment.TERMUX_HOST) "${HostShell.TERMUX_PREFIX}/tmp" else "/tmp"
        val body = ShellCommandBuilder.wrap(command, workingDirectory, null, false)
        val seconds = timeoutSeconds.coerceIn(1, TerminalCommandRequest.MAX_TIMEOUT_MILLIS / 1000)
        val invocation = if (useTimeoutBinary) listOf(executable(environment, "timeout"), "-k", "5", seconds.toString(), shell) else listOf(shell)
        return TerminalCommandRequest(environment, executable(environment, "env"),
            listOf("-i", "PATH=$path", "HOME=$home", "TMPDIR=$temporary", "LC_ALL=C", "PREFIX=${HostShell.TERMUX_PREFIX}") +
                invocation + listOf("--noprofile", "--norc", "-c", body), distroAlias = guestAlias,
            authorized = true, timeoutMillis = seconds * 1000)
    }
    /** Discovery may canonicalize /bin to /usr/bin; JVM package launchers resolve into /usr/lib/jvm. */
    fun trustedTool(environment: TerminalEnvironment, name: String, path: String, overrides: Map<String, String>): Boolean {
        if (path.length > 1024 || !path.matches(Regex("/[A-Za-z0-9._+@ /-]+")) || path.contains("//") || path.split('/').any { it == "." || it == ".." }) return false
        if (overrides[name] == path && name in setOf("java", "javac", "kotlinc", "gradle", "node", "npm", "python3", "cargo", "rustc", "go", "make", "cc", "cmake")) return true
        return if (environment == TerminalEnvironment.TERMUX_HOST) path.startsWith("${HostShell.TERMUX_PREFIX}/bin/")
            else path.startsWith("/usr/bin/") || path.startsWith("/bin/") ||
                (name in setOf("java", "javac") && path.startsWith("/usr/lib/jvm/") && path.endsWith("/bin/$name"))
    }
}

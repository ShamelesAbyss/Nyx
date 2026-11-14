package com.nyx.ai.terminal

/**
 * Builds a Termux-host invocation that cannot be diverted into a PRoot guest by the user's startup
 * profile.
 *
 * Termux's documented RUN_COMMAND API expands `$PREFIX/` in the command path and starts the program
 * directly. A fixed env executable clears hooks/PATH before bash starts; --noprofile/--norc
 * separately disable login/interactive profiles. This trusts Termux's package-owned executables,
 * not the integrity of a user-controlled Termux installation or an OS sandbox.
 */
object HostShell {
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val MAX_SCRIPT_CHARS = 48_000

    /** `$PREFIX` is expanded by Termux itself; the validator already accepts this form. */
    const val SHELL_PATH = "\$PREFIX/bin/bash"

    fun isolatedRequest(script: String, timeoutSeconds: Long = 45): TerminalCommandRequest {
        require(script.isNotBlank()) { "Host script must not be blank" }
        require(script.length <= MAX_SCRIPT_CHARS) { "Host script exceeds $MAX_SCRIPT_CHARS characters" }
        require('\u0000' !in script) { "Host script must not contain NUL" }
        return ControlledShell.request(script, TerminalEnvironment.TERMUX_HOST, null, timeoutSeconds.coerceIn(1, 600), false)
    }

    data class HostIdentity(
        val prefix: String?,
        val home: String?,
        val uname: String?,
        val androidHost: Boolean,
    )

    fun identityScript(): String = """
        printf 'NYX_PREFIX=%s\n' "${'$'}{PREFIX:-}"
        printf 'NYX_HOME=%s\n' "${'$'}{HOME:-}"
        printf 'NYX_UNAME=%s\n' "$(uname -a 2>/dev/null)"
        if command -v getprop >/dev/null 2>&1; then printf 'NYX_ANDROID_HOST=1\n'; else printf 'NYX_ANDROID_HOST=0\n'; fi
    """.trimIndent()

    fun parseIdentity(stdout: String): HostIdentity {
        var prefix: String? = null
        var home: String? = null
        var uname: String? = null
        var android = false
        for (line in stdout.lineSequence()) {
            when {
                line.startsWith("NYX_PREFIX=") -> prefix = line.removePrefix("NYX_PREFIX=").trim().ifBlank { null }
                line.startsWith("NYX_HOME=") -> home = line.removePrefix("NYX_HOME=").trim().ifBlank { null }
                line.startsWith("NYX_UNAME=") -> uname = line.removePrefix("NYX_UNAME=").trim().ifBlank { null }
                line.startsWith("NYX_ANDROID_HOST=") -> android = line.removePrefix("NYX_ANDROID_HOST=").trim() == "1"
            }
        }
        return HostIdentity(prefix, home, uname, android)
    }

    /** A distro alias is only ever interpolated after this check, so it cannot carry shell syntax. */
    fun isSafeAlias(alias: String): Boolean =
        alias.length in 1..40 && alias.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

    /** Runs the guest probe inside [alias]; the guest script itself contains no single quotes. */
    fun guestVerificationScript(alias: String): String {
        require(isSafeAlias(alias)) { "Unsafe distribution alias" }
        val guest = GuestProbeParser.script()
        return "proot-distro login $alias -- /bin/sh -c ${ShellCommandBuilder.quote(guest)}"
    }
}

package com.nyx.ai.terminal

/**
 * Outcome of a `proot-distro list` invocation.
 *
 * A failed command is never represented as an empty installation list: [Failed] and [NestedProot]
 * are distinct from a genuinely empty [Installed] result.
 */
sealed interface DistroListResult {
    data class Installed(val aliases: List<String>, val format: String) : DistroListResult
    data class NestedProot(val detail: String) : DistroListResult
    data class Failed(val detail: String, val exitCode: Int?) : DistroListResult
}

/**
 * Parses every `proot-distro list` shape NYX has observed: the sectioned table, the `--quiet`
 * alias list, and older bullet/annotation variants. Pure and unit-testable.
 */
object ProotDistroListParser {
    private val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,40}")
    private val SECTION_HEADER = Regex("^[A-Za-z][A-Za-z ]{2,60}:?$")
    private val ANNOTATION = Regex("""\s*\([^)]*\)\s*$""")

    fun parse(stdout: String, stderr: String, exitCode: Int?): DistroListResult {
        val errorText = (stderr + "\n" + stdout).lowercase()
        if ("proot session" in errorText || "attempted to run proot-distro in a proot" in errorText) {
            return DistroListResult.NestedProot(
                "proot-distro refused to run because the command executed inside an existing PRoot guest. " +
                    "NYX must run discovery from the Termux host, not from inside Debian.",
            )
        }
        val aliases = parseAliases(stdout)
        if (exitCode != null && exitCode != 0) {
            val detail = (stderr.ifBlank { stdout }).trim().lineSequence()
                .firstOrNull { it.isNotBlank() }?.take(300)
                ?: "proot-distro list exited with code $exitCode"
            return DistroListResult.Failed(detail, exitCode)
        }
        if (exitCode == null) {
            return DistroListResult.Failed("No process exit code was reported for proot-distro list.", null)
        }
        return DistroListResult.Installed(aliases, detectFormat(stdout))
    }

    /** Compatibility entry point retained for existing callers and tests. */
    fun parseInstalled(stdout: String): List<String> = parseAliases(stdout)

    private fun detectFormat(stdout: String): String = when {
        stdout.lineSequence().any { it.trim().lowercase().startsWith("installed distribution") } -> "sectioned"
        stdout.lineSequence().any { it.trim().startsWith("*") || it.trim().startsWith("-") } -> "bulleted"
        else -> "quiet"
    }

    private fun parseAliases(stdout: String): List<String> {
        val sectioned = stdout.lineSequence().any { it.trim().lowercase().startsWith("installed distribution") }
        val result = linkedSetOf<String>()
        var inInstalled = !sectioned
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            when {
                lower.startsWith("installed distribution") -> { inInstalled = true; continue }
                lower.startsWith("supported distribution") -> { inInstalled = false; continue }
                lower.startsWith("error") || lower.startsWith("warning") -> continue
                !sectioned && lower.endsWith(":") && SECTION_HEADER.matches(line) -> continue
            }
            if (!inInstalled) continue
            val cleaned = line
                .removePrefix("*").removePrefix("-").removePrefix("•").removePrefix("[*]").trim()
                .replace(ANNOTATION, "")
                .substringBefore(' ').substringBefore('\t').trim()
            if (ALIAS.matches(cleaned) && cleaned.lowercase() !in RESERVED) result += cleaned
        }
        return result.toList()
    }

    private val RESERVED = setOf(
        "supported", "installed", "distributions", "distribution", "available", "none",
    )
}

/** Facts reported by a real command executed inside a PRoot guest. */
data class GuestProbe(
    val verified: Boolean,
    val home: String? = null,
    val workingDirectory: String? = null,
    val shell: String? = null,
    val osName: String? = null,
    val uid: String? = null,
    val tools: Map<String, String> = emptyMap(),
    val detail: String? = null,
)

object GuestProbeParser {
    const val MARKER = "NYX_GUEST_OK"

    /** Builds a quote-free guest script so it can be embedded safely in a single-quoted host word. */
    fun script(tools: List<String> = listOf("gcc", "cc", "clang", "make", "cmake", "python3", "git", "javac", "gradle", "pkg", "apt")): String =
        buildString {
            append("printf '").append(MARKER).append("\\n'; ")
            append("printf 'NYX_GUEST_HOME=%s\\n' \"\$HOME\"; ")
            append("printf 'NYX_GUEST_PWD=%s\\n' \"\$(pwd)\"; ")
            append("printf 'NYX_GUEST_SHELL=%s\\n' \"\${SHELL:-}\"; ")
            append("printf 'NYX_GUEST_OS=%s\\n' \"\$(uname -s 2>/dev/null)\"; ")
            append("printf 'NYX_GUEST_UID=%s\\n' \"\$(id -u 2>/dev/null)\"; ")
            append("for t in ").append(tools.joinToString(" ")).append("; do ")
            append("p=\$(command -v \"\$t\" 2>/dev/null); ")
            append("[ -n \"\$p\" ] && printf 'NYX_GUEST_TOOL:%s=%s\\n' \"\$t\" \"\$p\"; ")
            append("done")
        }

    fun parse(stdout: String, stderr: String, exitCode: Int?): GuestProbe {
        if (exitCode == null) return GuestProbe(false, detail = "Guest probe returned no process exit code.")
        if (exitCode != 0) {
            val detail = (stderr.ifBlank { stdout }).trim().lineSequence()
                .firstOrNull { it.isNotBlank() }?.take(300)
                ?: "Guest probe exited with code $exitCode"
            return GuestProbe(false, detail = detail)
        }
        if (!stdout.lineSequence().any { it.trim() == MARKER }) {
            return GuestProbe(false, detail = "Guest probe did not print the $MARKER marker.")
        }
        var home: String? = null
        var pwd: String? = null
        var shell: String? = null
        var os: String? = null
        var uid: String? = null
        val tools = linkedMapOf<String, String>()
        for (line in stdout.lineSequence()) {
            when {
                line.startsWith("NYX_GUEST_HOME=") -> home = line.removePrefix("NYX_GUEST_HOME=").trim().ifBlank { null }
                line.startsWith("NYX_GUEST_PWD=") -> pwd = line.removePrefix("NYX_GUEST_PWD=").trim().ifBlank { null }
                line.startsWith("NYX_GUEST_SHELL=") -> shell = line.removePrefix("NYX_GUEST_SHELL=").trim().ifBlank { null }
                line.startsWith("NYX_GUEST_OS=") -> os = line.removePrefix("NYX_GUEST_OS=").trim().ifBlank { null }
                line.startsWith("NYX_GUEST_UID=") -> uid = line.removePrefix("NYX_GUEST_UID=").trim().ifBlank { null }
                line.startsWith("NYX_GUEST_TOOL:") -> {
                    val body = line.removePrefix("NYX_GUEST_TOOL:")
                    val name = body.substringBefore('=', "").trim()
                    val path = body.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && path.startsWith('/')) tools[name] = path.take(1_024)
                }
            }
        }
        return GuestProbe(
            verified = true,
            home = home,
            workingDirectory = pwd,
            shell = shell,
            osName = os,
            uid = uid,
            tools = tools,
        )
    }
}

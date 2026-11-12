package com.nyx.ai.terminal

/** Result of probing the local Termux installation. All paths are observed, never guessed. */
data class EnvironmentProfile(
    val hostAvailable: Boolean = false,
    val home: String? = null,
    val prefix: String? = null,
    val uname: String? = null,
    val shellPath: String? = null,
    val prootDistroPath: String? = null,
    val timeoutAvailable: Boolean = false,
    val tools: Map<String, String> = emptyMap(),
    val distros: List<String> = emptyList(),
    val checkedAt: Long = System.currentTimeMillis(),
    val detail: String? = null,
) {
    val debianAvailable: Boolean get() = distros.any { it.equals("debian", ignoreCase = true) }
    fun tool(name: String): String? = tools[name]
}

data class HostProbe(
    val home: String? = null,
    val prefix: String? = null,
    val uname: String? = null,
    val tools: Map<String, String> = emptyMap(),
)

/** Pure parsers for the probe scripts; unit-testable without Android or Termux. */
object EnvironmentProbeParser {
    fun parseHost(stdout: String): HostProbe {
        var home: String? = null
        var prefix: String? = null
        var uname: String? = null
        val tools = linkedMapOf<String, String>()
        for (line in stdout.lineSequence()) {
            when {
                line.startsWith("NYX_HOME=") -> home = line.removePrefix("NYX_HOME=").trim().ifBlank { null }
                line.startsWith("NYX_PREFIX=") -> prefix = line.removePrefix("NYX_PREFIX=").trim().ifBlank { null }
                line.startsWith("NYX_UNAME=") -> uname = line.removePrefix("NYX_UNAME=").trim().ifBlank { null }
                line.startsWith("NYX_TOOL:") -> {
                    val body = line.removePrefix("NYX_TOOL:")
                    val name = body.substringBefore('=', "").trim()
                    val path = body.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && path.isNotEmpty()) tools[name] = path
                }
            }
        }
        return HostProbe(home, prefix, uname, tools)
    }

    /**
     * Parses `proot-distro list`, which prints a supported-distribution section and an installed
     * section. Only aliases in the installed section are returned.
     */
    fun parseInstalledDistros(stdout: String): List<String> {
        var inInstalled = false
        val result = linkedSetOf<String>()
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            when {
                lower.startsWith("installed distribution") -> { inInstalled = true; continue }
                lower.startsWith("supported distribution") -> { inInstalled = false; continue }
                lower.endsWith(":") -> { if (!lower.contains("installed")) inInstalled = false; continue }
            }
            if (!inInstalled) continue
            val cleaned = line.removePrefix("*").removePrefix("-").removePrefix("•").trim()
            val alias = cleaned.substringBefore(' ').substringBefore('(').trim()
            if (alias.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,40}"))) result += alias
        }
        return result.toList()
    }

    fun parseSinglePath(stdout: String): String? =
        stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") && '\u0000' !in it && it.length <= 4_096 }
}

/**
 * Runs the documented probe commands through Termux. Discovery is optional: when Termux is absent or
 * unauthorized the profile simply reports that no host is available.
 */
class EnvironmentDiscovery(private val bridge: TerminalBridge) {
    suspend fun discover(prootPathHint: String? = null): EnvironmentProfile {
        val readiness = bridge.readiness()
        if (!readiness.installed) {
            return EnvironmentProfile(hostAvailable = false, detail = "Termux is not installed or not visible to NYX.")
        }
        if (!readiness.runCommandPermissionGranted) {
            return EnvironmentProfile(hostAvailable = false, detail = "Termux RUN_COMMAND permission is not granted to NYX.")
        }
        val probe = bridge.runShell(HOST_PROBE, timeoutSeconds = 30, useTimeoutBinary = false, approved = true)
        if (probe.kind != TerminalResultKind.SUCCEEDED && probe.kind != TerminalResultKind.PROCESS_EXIT) {
            return EnvironmentProfile(hostAvailable = false, detail = probe.message ?: "Termux probe did not return a result.")
        }
        val parsed = EnvironmentProbeParser.parseHost(probe.stdout)
        val prootPath = parsed.tools["proot-distro"]
        bridge.configureDiscoveredProot(prootPath)
        val distros = if (prootPath != null) {
            val listing = bridge.runShell("proot-distro list 2>&1 || true", timeoutSeconds = 45, useTimeoutBinary = parsed.tools.containsKey("timeout"), approved = true)
            EnvironmentProbeParser.parseInstalledDistros(listing.stdout + "\n" + listing.stderr)
        } else emptyList()

        return EnvironmentProfile(
            hostAvailable = probe.kind == TerminalResultKind.SUCCEEDED,
            home = parsed.home,
            prefix = parsed.prefix,
            uname = parsed.uname,
            shellPath = parsed.tools["bash"],
            prootDistroPath = prootPath,
            timeoutAvailable = parsed.tools.containsKey("timeout"),
            tools = parsed.tools,
            distros = distros,
            detail = buildString {
                append(if (probe.kind == TerminalResultKind.SUCCEEDED) "Termux host is reachable." else "Termux host probe returned exit ${probe.exitCode}.")
                if (prootPath == null) append(" proot-distro was not found.")
                if (prootPath != null && distros.isEmpty()) append(" No installed PRoot distribution was reported.")
            },
        )
    }

    private companion object {
        val HOST_PROBE = """
            printf 'NYX_HOME=%s\n' "${'$'}HOME"
            printf 'NYX_PREFIX=%s\n' "${'$'}PREFIX"
            printf 'NYX_UNAME=%s\n' "$(uname -a 2>/dev/null)"
            for t in bash proot-distro timeout git python3 node npm gradle java; do
              p=$(command -v "${'$'}t" 2>/dev/null)
              printf 'NYX_TOOL:%s=%s\n' "${'$'}t" "${'$'}p"
            done
        """.trimIndent()
    }
}

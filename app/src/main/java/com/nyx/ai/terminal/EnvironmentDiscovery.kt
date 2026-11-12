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
    /** True when the probe really ran on the Termux host rather than inside a PRoot guest. */
    val isolatedHost: Boolean = false,
    /** True when Termux dropped the command into an existing PRoot session. */
    val nestedProot: Boolean = false,
    /** Why the distro list could not be read, when it could not. Never a silent empty list. */
    val listError: String? = null,
    /** Guest facts captured by a real command executed inside each distribution. */
    val guests: Map<String, GuestProbe> = emptyMap(),
    val verifiedDistros: List<String> = emptyList(),
) {
    /** Debian appears in the host's installed list. This alone is not verification. */
    val debianListed: Boolean get() = distros.any { it.equals("debian", ignoreCase = true) }

    /** Debian answered a real guest probe with exit 0 and the expected marker. */
    val debianAvailable: Boolean get() = verifiedDistros.any { it.equals("debian", ignoreCase = true) }

    fun tool(name: String): String? = tools[name]
    fun guest(alias: String): GuestProbe? = guests[alias]
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
                    if (name.isNotEmpty() && path.startsWith('/')) tools[name] = path.take(1_024)
                }
            }
        }
        return HostProbe(home, prefix, uname, tools)
    }

    /**
     * Parses `proot-distro list`, which prints a supported-distribution section and an installed
     * section. Only aliases in the installed section are returned.
     */
    fun parseInstalledDistros(stdout: String): List<String> = ProotDistroListParser.parseInstalled(stdout)

    fun parseSinglePath(stdout: String): String? =
        stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") && '\u0000' !in it && it.length <= 4_096 }
}

/**
 * Runs bounded, read-only probes through Termux.
 *
 * Discovery executes on an explicitly isolated host shell (see [HostShell]) so a Termux startup
 * profile that logs into Debian cannot make every probe land inside the guest. A distribution is
 * reported as available only after a real command runs inside it and returns the expected marker.
 */
class EnvironmentDiscovery(private val bridge: TerminalBridge) {
    suspend fun discover(prootPathHint: String? = null): EnvironmentProfile {
        val readiness = bridge.readiness()
        if (!readiness.installed) {
            return EnvironmentProfile(hostAvailable = false, detail = "Termux is not installed or not visible to NYX.")
        }
        if (!readiness.runCommandPermissionGranted) {
            return EnvironmentProfile(
                hostAvailable = false,
                detail = "Termux RUN_COMMAND permission is not granted to NYX. Grant ${TermuxProtocol.PERMISSION_RUN_COMMAND} and set allow-external-apps=true.",
            )
        }

        val identityResult = bridge.runHostIsolated(HostShell.identityScript(), timeoutSeconds = 30)
        if (identityResult.kind != TerminalResultKind.SUCCEEDED && identityResult.kind != TerminalResultKind.PROCESS_EXIT) {
            return EnvironmentProfile(
                hostAvailable = false,
                detail = identityResult.message?.take(300)
                    ?: "Termux did not return a host identity result (${identityResult.kind.name.lowercase()}).",
            )
        }
        val identity = HostShell.parseIdentity(identityResult.stdout)
        if (!identity.androidHost) {
            return EnvironmentProfile(
                hostAvailable = identityResult.exitCode == 0,
                home = identity.home,
                prefix = identity.prefix,
                uname = identity.uname,
                isolatedHost = false,
                nestedProot = true,
                detail = "Termux is executing commands inside a PRoot guest, not on the Termux host. " +
                    "NYX launched bash with --noprofile --norc, so the diversion comes from the Termux " +
                    "session itself. Remove the automatic `proot-distro login` from the startup profile " +
                    "(~/.bashrc, ~/.bash_profile or ~/.profile), then detect again.",
            )
        }

        val probe = bridge.runHostIsolated(HOST_PROBE, timeoutSeconds = 30)
        val parsed = EnvironmentProbeParser.parseHost(probe.stdout)
        val hinted = prootPathHint?.takeIf { it.startsWith('/') && '\u0000' !in it && it.length <= 4_096 }
        val prootPath = parsed.tools["proot-distro"] ?: hinted
        bridge.configureDiscoveredProot(prootPath)

        var listError: String? = null
        var nestedFromList = false
        var distros: List<String> = emptyList()
        if (prootPath == null) {
            listError = "proot-distro was not found on the Termux host PATH."
        } else {
            // No `|| true`: a failed command must stay failed instead of looking like "none installed".
            val listing = bridge.runHostIsolated("proot-distro list --quiet", timeoutSeconds = 45)
            when (val result = ProotDistroListParser.parse(listing.stdout, listing.stderr, listing.exitCode)) {
                is DistroListResult.Installed -> distros = result.aliases
                is DistroListResult.NestedProot -> {
                    nestedFromList = true
                    listError = result.detail
                }
                is DistroListResult.Failed -> listError = result.detail
            }
        }

        val guests = linkedMapOf<String, GuestProbe>()
        val ordered = distros.sortedWith(compareBy({ if (it.equals("debian", ignoreCase = true)) 0 else 1 }, { it }))
        for (alias in ordered.take(MAX_VERIFIED_GUESTS)) {
            if (!HostShell.isSafeAlias(alias)) continue
            val verification = bridge.runHostIsolated(HostShell.guestVerificationScript(alias), timeoutSeconds = 60)
            guests[alias] = GuestProbeParser.parse(verification.stdout, verification.stderr, verification.exitCode)
        }
        val verified = guests.filterValues { it.verified }.keys.toList()

        return EnvironmentProfile(
            hostAvailable = probe.kind == TerminalResultKind.SUCCEEDED || probe.kind == TerminalResultKind.PROCESS_EXIT,
            home = parsed.home ?: identity.home,
            prefix = parsed.prefix ?: identity.prefix,
            uname = parsed.uname ?: identity.uname,
            shellPath = parsed.tools["bash"],
            prootDistroPath = prootPath,
            timeoutAvailable = parsed.tools.containsKey("timeout"),
            tools = parsed.tools,
            distros = distros,
            isolatedHost = true,
            nestedProot = nestedFromList,
            listError = listError,
            guests = guests,
            verifiedDistros = verified,
            detail = buildDetail(probe, prootPath, distros, verified, guests, listError, nestedFromList),
        )
    }

    private fun buildDetail(
        probe: TerminalCommandResult,
        prootPath: String?,
        distros: List<String>,
        verified: List<String>,
        guests: Map<String, GuestProbe>,
        listError: String?,
        nestedFromList: Boolean,
    ): String = buildString {
        append(if (probe.exitCode == 0) "Termux host is reachable on an isolated shell." else "Termux host probe returned exit ${probe.exitCode}.")
        if (prootPath == null) append(" proot-distro was not found.")
        else append(" proot-distro: ").append(prootPath).append('.')
        if (nestedFromList) append(" proot-distro refused to run inside a PRoot session.")
        when {
            distros.isEmpty() && listError == null -> append(" No installed PRoot distribution was reported.")
            distros.isNotEmpty() -> append(" Installed: ").append(distros.joinToString(", ")).append('.')
        }
        if (verified.isNotEmpty()) append(" Verified: ").append(verified.joinToString(", ")).append('.')
        val failed = guests.filterValues { !it.verified }
        if (failed.isNotEmpty()) {
            append(" Not verified: ")
            append(failed.entries.joinToString(", ") { "${it.key} (${it.value.detail?.take(120) ?: "no marker"})" })
            append('.')
        }
        listError?.let { if (distros.isEmpty()) append(' ').append(it.take(300)) }
    }

    private companion object {
        const val MAX_VERIFIED_GUESTS = 4

        val HOST_PROBE = """
            printf 'NYX_HOME=%s\n' "${'$'}HOME"
            printf 'NYX_PREFIX=%s\n' "${'$'}PREFIX"
            printf 'NYX_UNAME=%s\n' "$(uname -a 2>/dev/null)"
            for t in bash proot-distro timeout git java javac kotlinc gradle sdkmanager clang gcc make cmake python3 node npm pkg; do
              p=$(command -v "${'$'}t" 2>/dev/null)
              [ -n "${'$'}p" ] && printf 'NYX_TOOL:%s=%s\n' "${'$'}t" "${'$'}p"
            done
        """.trimIndent()
    }
}

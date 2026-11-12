package com.nyx.ai.ollama

import com.nyx.ai.terminal.HostShell
import com.nyx.ai.terminal.TerminalEnvironment

/**
 * One environment in which an Ollama binary can live.
 *
 * The host and a PRoot guest have different filesystems and `$HOME` values, so a path or PID from
 * one is never valid in the other. [guestAlias] is null for the Termux host and a validated
 * proot-distro alias for a guest.
 */
data class OllamaTarget(
    val environment: TerminalEnvironment,
    val guestAlias: String? = null,
) {
    val isHost: Boolean get() = environment == TerminalEnvironment.TERMUX_HOST

    val environmentId: String
        get() = if (isHost) "termux_host" else "proot:${guestAlias.orEmpty()}"

    val label: String
        get() = if (isHost) "Termux host" else "Debian guest · ${guestAlias.orEmpty()}"

    val valid: Boolean
        get() = if (isHost) {
            guestAlias == null
        } else {
            !guestAlias.isNullOrBlank() && HostShell.isSafeAlias(guestAlias)
        }

    companion object {
        val HOST = OllamaTarget(TerminalEnvironment.TERMUX_HOST)

        fun guest(alias: String): OllamaTarget? =
            alias.takeIf { HostShell.isSafeAlias(it) }?.let { OllamaTarget(TerminalEnvironment.DEBIAN_PROOT, it) }
    }
}

/** A detection result tied to the exact environment that produced it. */
data class OllamaInstallation(
    val target: OllamaTarget,
    val detection: OllamaDetection,
)

/**
 * The complete discovery result. [selected] is the installation NYX may manage; [installations]
 * retains every probe so the UI can explain why another candidate was not chosen.
 */
data class OllamaDiscoveryPlan(
    val installations: List<OllamaInstallation> = emptyList(),
    val selected: OllamaInstallation? = null,
    val detail: String = "",
)

/**
 * Deterministic target selection.
 *
 * A compatible binary in the environment the user is currently working in wins. Otherwise the host
 * wins over a guest, and an incompatible installation is retained only for diagnostics. NYX never
 * claims an installation is absent while an incompatible binary exists.
 */
object OllamaDiscovery {
    fun choose(
        installations: List<OllamaInstallation>,
        preferredEnvironment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
        preferredGuest: String? = null,
    ): OllamaDiscoveryPlan {
        val candidates = installations.filter { it.detection.installed && it.target.valid }
        val compatible = candidates.filter { it.detection.compatible }
        val preferredGuestTarget = preferredGuest
            ?.takeIf { preferredEnvironment == TerminalEnvironment.DEBIAN_PROOT }
            ?.let(OllamaTarget::guest)
        val selected = compatible.firstOrNull { it.target == preferredGuestTarget }
            ?: compatible.firstOrNull { preferredEnvironment == TerminalEnvironment.TERMUX_HOST && it.target.isHost }
            ?: compatible.firstOrNull { it.target.isHost }
            ?: compatible.firstOrNull()
            ?: candidates.firstOrNull { it.target == preferredGuestTarget }
            ?: candidates.firstOrNull { it.target.isHost }
            ?: candidates.firstOrNull()

        val detail = when {
            selected == null && installations.any { it.detection.error.isNotBlank() } ->
                installations.first { it.detection.error.isNotBlank() }.detection.error
            selected == null -> "Ollama is not installed on the Termux host or any verified PRoot guest."
            !selected.detection.compatible ->
                "Ollama was found in ${selected.target.label} but the binary could not identify itself. " +
                    "Reinstall it for this device architecture (${selected.detection.architecture.ifBlank { "unknown" }})."
            else -> "Using Ollama ${selected.detection.version.ifBlank { "installed" }} from ${selected.target.label}."
        }
        return OllamaDiscoveryPlan(candidates, selected, detail)
    }
}

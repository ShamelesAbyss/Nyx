package com.nyx.ai.autonomy

import com.nyx.ai.terminal.CapabilityState

private const val READY_SUMMARY = "Ready"
private const val MAX_SUMMARY_CODES = 5

/**
 * Evidence available before an objective starts. Every field is caller-supplied and evaluated as
 * given: a missing value is never treated as success and nothing is inferred from names.
 *
 * @param environmentKind `termux_host`, `proot:<alias>`, or an explicit unavailable marker.
 * @param capabilities observed tool states, keyed by tool name.
 */
data class PreflightInput(
    val providerConfigured: Boolean,
    val modelSelected: Boolean,
    val requiresTerminal: Boolean,
    val environmentVerified: Boolean,
    val environmentKind: String,
    val workspaceAuthorized: Boolean,
    val workspaceAccessible: Boolean?,
    val requiredTools: Set<String>,
    val capabilities: Map<String, CapabilityState>,
    val availableMemoryMb: Long?,
    val requiresDevelopment: Boolean,
)

/** One concrete reason an objective cannot start. */
data class PreflightBlocker(val code: String, val message: String, val action: String)

/**
 * Deterministic preflight outcome. [summary] is "Ready" or a bounded `N blockers: code, ...` line,
 * so it can be shown in a status bar without truncation logic at the call site.
 */
data class PreflightResult(val ready: Boolean, val blockers: List<PreflightBlocker>) {
    val summary: String
        get() {
            if (blockers.isEmpty()) return READY_SUMMARY
            val codes = blockers.map { it.code }
            val shown = codes.take(MAX_SUMMARY_CODES).joinToString(", ")
            val hidden = codes.size - minOf(codes.size, MAX_SUMMARY_CODES)
            val suffix = if (hidden > 0) ", +$hidden more" else ""
            return "${blockers.size} blockers: $shown$suffix"
        }
}

/**
 * Evaluates readiness without side effects. Terminal, environment, and workspace requirements are
 * only enforced when the objective needs a terminal or development work; every explicitly required
 * tool is checked whenever it is listed.
 */
object Preflight {
    /** Development work needs headroom for a compiler, a build daemon, and the app itself. */
    const val MIN_DEVELOPMENT_MEMORY_MB = 512L

    fun evaluate(input: PreflightInput): PreflightResult {
        val blockers = ArrayList<PreflightBlocker>()
        val label = environmentLabel(input.environmentKind)

        if (!input.providerConfigured) {
            blockers += PreflightBlocker(
                "provider_missing",
                "No AI provider is configured, so no model can be called.",
                "Add a provider in Settings.",
            )
        }
        if (!input.modelSelected) {
            blockers += PreflightBlocker(
                "model_missing",
                "No model is selected for the configured provider.",
                "Select a model in Settings.",
            )
        }

        if (input.requiresTerminal || input.requiresDevelopment) {
            if (!environmentAvailable(input.environmentKind)) {
                blockers += PreflightBlocker(
                    "termux_unavailable",
                    "Termux is not available on this device, so terminal steps cannot run.",
                    "Install Termux and grant the RUN_COMMAND permission.",
                )
            } else if (!input.environmentVerified) {
                blockers += PreflightBlocker(
                    "environment_unverified",
                    "The $label environment has not been verified on this device.",
                    "Run the environment check.",
                )
            }
            if (!input.workspaceAuthorized) {
                blockers += PreflightBlocker(
                    "workspace_unauthorized",
                    "No workspace folder is authorized for this objective.",
                    "Choose and authorize a project folder.",
                )
            } else if (input.workspaceAccessible == false) {
                blockers += PreflightBlocker(
                    "workspace_inaccessible",
                    "The authorized workspace folder is not readable and writable.",
                    "Re-authorize a writable project folder.",
                )
            }
        }

        for (tool in requiredToolNames(input.requiredTools)) {
            val state = input.capabilities[tool] ?: CapabilityState.NOT_CHECKED
            val name = toolLabel(tool)
            when (state) {
                CapabilityState.VERIFIED -> Unit
                CapabilityState.MISSING -> blockers += PreflightBlocker(
                    "tool_missing:$tool",
                    "Required tool '$name' is not installed in $label.",
                    "Install $name.",
                )

                CapabilityState.INCOMPATIBLE -> blockers += PreflightBlocker(
                    "tool_incompatible:$tool",
                    "Required tool '$name' is present in $label but cannot run there.",
                    "Reinstall $name for this environment.",
                )

                CapabilityState.PERMISSION_DENIED -> blockers += PreflightBlocker(
                    "tool_permission_denied:$tool",
                    "Required tool '$name' exists in $label but NYX is not allowed to execute it.",
                    "Fix execute permissions for $name.",
                )

                CapabilityState.NOT_CHECKED -> blockers += PreflightBlocker(
                    "tool_not_checked:$tool",
                    "Required tool '$name' has not been checked in $label.",
                    "Run the environment check.",
                )
            }
        }

        val memoryMb = input.availableMemoryMb
        if (input.requiresDevelopment && memoryMb != null && memoryMb < MIN_DEVELOPMENT_MEMORY_MB) {
            blockers += PreflightBlocker(
                "memory_low",
                "Only $memoryMb MB of memory is available; development work needs at least " +
                    "$MIN_DEVELOPMENT_MEMORY_MB MB.",
                "Close background apps and retry.",
            )
        }

        return PreflightResult(ready = blockers.isEmpty(), blockers = blockers.toList())
    }

    /** Stable ordering keeps the summary and tests deterministic regardless of set iteration order. */
    private fun requiredToolNames(tools: Set<String>): List<String> =
        tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    private fun environmentAvailable(kind: String): Boolean =
        kind.trim().lowercase() !in UNAVAILABLE_KINDS

    private fun environmentLabel(kind: String): String {
        val normalized = kind.trim()
        return when {
            normalized.isEmpty() -> "the terminal environment"
            normalized.equals("termux_host", ignoreCase = true) -> "Termux"
            normalized.startsWith("proot:", ignoreCase = true) -> "PRoot ${normalized.substringAfter(':')}"
            else -> normalized
        }
    }

    /** Tool names reach user-facing text, so keep them short and printable. */
    private fun toolLabel(name: String): String {
        val label = name.filter { it.code in 0x21..0x7E }.take(MAX_TOOL_LABEL_CHARS)
        return label.ifEmpty { "tool" }
    }

    private const val MAX_TOOL_LABEL_CHARS = 40
    private val UNAVAILABLE_KINDS = setOf(
        "",
        "none",
        "unknown",
        "unavailable",
        "not_available",
        "absent",
        "termux_missing",
    )
}

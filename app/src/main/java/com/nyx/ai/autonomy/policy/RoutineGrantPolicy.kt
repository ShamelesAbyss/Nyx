package com.nyx.ai.autonomy.policy

/**
 * Pure construction of the ordinary grants implied by selecting an autonomy mode.
 *
 * A workspace is mandatory. There is no `"*"` fallback: if the path is blank, relative, or root,
 * the policy issues no routine authority at all. Every returned grant is scoped to that workspace
 * and to the selected environment, never to the whole device.
 */
object RoutineGrantPolicy {
    val CAPABILITIES = setOf(
        "workspace.read",
        "workspace.write",
        "workspace.delete",
        "command.readonly",
        "command.mutating",
        "packages.install",
        "git.commit",
        "delegate",
    )

    fun normalizeWorkspace(path: String?): String? {
        val value = path?.trim()?.trimEnd('/') ?: return null
        if (value.length <= 1 || !value.startsWith("/") || value.contains("//")) return null
        return value
    }

    fun build(
        environmentId: String,
        workspacePath: String?,
        now: Long,
        newId: () -> String,
    ): List<CapabilityGrant> {
        val workspace = normalizeWorkspace(workspacePath) ?: return emptyList()
        require(environmentId.isNotBlank()) { "environment id is required" }
        return CAPABILITIES.map { capability ->
            CapabilityGrant(
                id = newId(),
                capability = capability,
                environmentId = environmentId,
                scope = workspace,
                createdAt = now,
            )
        }
    }
}

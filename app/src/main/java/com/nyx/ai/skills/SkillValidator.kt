package com.nyx.ai.skills

import java.util.Collections

/** Validation selects subsets of host authority. It never grants tools or permissions. */
object SkillValidator {
    private val toolPermissions = mapOf(
        "workspace_read" to setOf(SkillPermission.WORKSPACE_READ),
        "workspace_write" to setOf(SkillPermission.WORKSPACE_WRITE),
        "file_read" to setOf(SkillPermission.WORKSPACE_READ),
        "file_list" to setOf(SkillPermission.WORKSPACE_READ),
        "file_search" to setOf(SkillPermission.WORKSPACE_READ),
        "project_detect" to setOf(SkillPermission.WORKSPACE_READ),
        "file_write" to setOf(SkillPermission.WORKSPACE_WRITE),
        "repo_status" to setOf(SkillPermission.TERMINAL_EXECUTE, SkillPermission.WORKSPACE_READ, SkillPermission.GIT_READ),
        "repo_diff" to setOf(SkillPermission.WORKSPACE_READ),
        "terminal_run" to setOf(SkillPermission.TERMINAL_EXECUTE, SkillPermission.WORKSPACE_READ),
        "build_run" to setOf(SkillPermission.BUILD_EXECUTE, SkillPermission.TERMINAL_EXECUTE, SkillPermission.WORKSPACE_WRITE),
        "test_run" to setOf(SkillPermission.TEST_EXECUTE, SkillPermission.TERMINAL_EXECUTE, SkillPermission.WORKSPACE_WRITE),
    )
    private val importTools = setOf("workspace_read", "file_read", "file_list", "file_search", "project_detect")
    private val importPermissions = setOf(SkillPermission.WORKSPACE_READ)

    /** Untrusted declarations remain read-only even when the host has broader permissions. */
    fun validateUntrusted(manifest: SkillManifest, context: SkillValidationContext): SkillValidationResult =
        validate(manifest, context, builtin = false)

    /** This internal path is only called with a fresh declaration from the fixed builtin registry. */
    internal fun validateBuiltin(manifest: SkillManifest, context: SkillValidationContext): SkillValidationResult =
        validate(manifest, context, builtin = true)

    private fun validate(manifest: SkillManifest, context: SkillValidationContext, builtin: Boolean): SkillValidationResult {
        val issues = mutableListOf<SkillValidationIssue>()
        fun reject(code: SkillIssueCode, message: String) { issues += SkillValidationIssue(code, message) }
        if (!manifest.id.matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) || manifest.id.length > 80)
            reject(SkillIssueCode.INVALID_ID, "Use a lowercase, bounded skill identifier")
        if (!builtin && manifest.id.startsWith("builtin."))
            reject(SkillIssueCode.RESERVED_ID, "Imported manifests cannot claim builtin identity")
        if (version(manifest.version) == null) reject(SkillIssueCode.INVALID_VERSION, "Version must be major.minor.patch")
        if (manifest.description.isBlank() || manifest.description.length > 2_000 || '\u0000' in manifest.description)
            reject(SkillIssueCode.INVALID_DESCRIPTION, "Description must contain 1-2000 characters without NUL")
        if (manifest.tools.isEmpty() || manifest.tools.size > 16)
            reject(SkillIssueCode.UNKNOWN_TOOL, "Declare 1-16 existing tools")
        for (tool in manifest.tools.sorted()) {
            val permissions = toolPermissions[tool]
            if (permissions == null) reject(SkillIssueCode.UNKNOWN_TOOL, "Unknown tool: $tool")
            if (tool !in context.allowedTools || (!builtin && tool !in importTools))
                reject(SkillIssueCode.TOOL_ESCALATION, "Tool is outside the authorized skill subset: $tool")
            if (permissions != null && !manifest.requiredPermissions.containsAll(permissions))
                reject(SkillIssueCode.MISSING_PERMISSION, "Tool $tool omits its required permissions")
        }
        for (permission in manifest.requiredPermissions.sortedBy { it.name }) {
            if (permission !in context.grantedPermissions || (!builtin && permission !in importPermissions))
                reject(SkillIssueCode.PERMISSION_ESCALATION, "Permission is not granted to this skill: ${permission.name}")
        }
        if (SkillPrecondition.WORKSPACE_SELECTED !in manifest.preconditions)
            reject(SkillIssueCode.MISSING_PRECONDITION, "Offline skills require an explicit workspace")
        if (manifest.tools.any { it in setOf("terminal_run", "repo_status", "build_run", "test_run") } &&
            SkillPrecondition.TERMINAL_AVAILABLE !in manifest.preconditions)
            reject(SkillIssueCode.MISSING_PRECONDITION, "Terminal tools require terminal availability")
        for (precondition in manifest.preconditions.sortedBy { it.name }) {
            if (precondition !in context.satisfiedPreconditions)
                reject(SkillIssueCode.MISSING_PRECONDITION, "Precondition is not satisfied: ${precondition.name}")
        }
        val constraints = manifest.constraints
        if (!constraints.workspaceOnly || constraints.allowNetwork || constraints.allowDestructive ||
            constraints.allowAutoCommit || !constraints.requireWriteApproval || constraints.maxSteps !in 1..30 ||
            constraints.maxOutputChars !in 1..32_000)
            reject(SkillIssueCode.INVALID_CONSTRAINTS, "Skills cannot relax workspace, offline, approval, destructive, or commit restrictions")
        val failure = manifest.failureHandling
        if (!failure.preserveEvidence || failure.maxRetries !in 0..1 ||
            (failure.mode == SkillFailureMode.STOP_AND_REPORT && failure.maxRetries != 0) ||
            (failure.mode == SkillFailureMode.RETRY_THEN_REPORT && failure.maxRetries != 1))
            reject(SkillIssueCode.INVALID_FAILURE_HANDLING, "Failures must preserve evidence and stop after at most one explicit retry")
        if (manifest.instructions.size > constraints.maxSteps || manifest.instructions.any { it.isBlank() || it.length > 2_000 || '\u0000' in it })
            reject(SkillIssueCode.INVALID_INSTRUCTIONS, "Instructions exceed the bounded step or text budget")
        val compatibility = manifest.compatibility
        val minimum = version(compatibility.minHostVersion)
        val host = version(context.hostVersion)
        if (compatibility.manifestVersion != 1 || minimum == null || host == null || compatibility.maxHostMajor < 0 ||
            compatibility.maxHostMajor < minimum[0] || compareVersions(host, minimum) < 0 || host[0] > compatibility.maxHostMajor)
            reject(SkillIssueCode.INCOMPATIBLE_HOST, "Manifest schema or host version is incompatible")
        if (issues.isNotEmpty()) return SkillValidationResult(null, issues)
        val snapshot = manifest.copy(
            tools = Collections.unmodifiableSet(manifest.tools.toSet()),
            requiredPermissions = Collections.unmodifiableSet(manifest.requiredPermissions.toSet()),
            preconditions = Collections.unmodifiableSet(manifest.preconditions.toSet()),
            instructions = Collections.unmodifiableList(manifest.instructions.toList()),
        )
        return SkillValidationResult(ValidatedSkill(snapshot, if (builtin) SkillOrigin.TRUSTED_BUILTIN else SkillOrigin.UNTRUSTED_IMPORT), emptyList())
    }

    private fun version(value: String): List<Int>? {
        if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) || value.length > 32) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (index in 0..2) if (a[index] != b[index]) return a[index].compareTo(b[index])
        return 0
    }
}

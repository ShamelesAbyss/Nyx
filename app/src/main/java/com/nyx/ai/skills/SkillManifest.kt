package com.nyx.ai.skills

enum class SkillPermission { WORKSPACE_READ, WORKSPACE_WRITE, TERMINAL_EXECUTE, BUILD_EXECUTE, TEST_EXECUTE, GIT_READ }
enum class SkillPrecondition { WORKSPACE_SELECTED, TERMINAL_AVAILABLE, PROJECT_DETECTED, GIT_REPOSITORY }
enum class SkillFailureMode { STOP_AND_REPORT, RETRY_THEN_REPORT }

data class SkillConstraints(
    val workspaceOnly: Boolean = true,
    val allowNetwork: Boolean = false,
    val allowDestructive: Boolean = false,
    val allowAutoCommit: Boolean = false,
    val requireWriteApproval: Boolean = true,
    val maxSteps: Int = 12,
    val maxOutputChars: Int = 8_000,
)

data class SkillFailureHandling(
    val mode: SkillFailureMode = SkillFailureMode.STOP_AND_REPORT,
    val maxRetries: Int = 0,
    val preserveEvidence: Boolean = true,
)

data class SkillCompatibility(
    val manifestVersion: Int = 1,
    val minHostVersion: String = "1.0.0",
    val maxHostMajor: Int = 1,
)

/** Data and bounded procedural guidance only. Importing this object does not install executable code. */
data class SkillManifest(
    val id: String,
    val version: String,
    val description: String,
    val tools: Set<String>,
    val requiredPermissions: Set<SkillPermission>,
    val preconditions: Set<SkillPrecondition>,
    val constraints: SkillConstraints = SkillConstraints(),
    val failureHandling: SkillFailureHandling = SkillFailureHandling(),
    val compatibility: SkillCompatibility = SkillCompatibility(),
    val instructions: List<String> = emptyList(),
)

/** Host state is supplied by the existing execution/approval layer, never by a manifest. */
data class SkillValidationContext(
    val allowedTools: Set<String>,
    val grantedPermissions: Set<SkillPermission>,
    val satisfiedPreconditions: Set<SkillPrecondition>,
    val hostVersion: String = "1.0.0",
)

enum class SkillIssueCode {
    INVALID_FORMAT, UNKNOWN_FIELD, INVALID_ID, INVALID_VERSION, INVALID_DESCRIPTION,
    UNKNOWN_TOOL, TOOL_ESCALATION, PERMISSION_ESCALATION, MISSING_PERMISSION,
    MISSING_PRECONDITION, INVALID_CONSTRAINTS, INVALID_FAILURE_HANDLING,
    INCOMPATIBLE_HOST, RESERVED_ID, INVALID_INSTRUCTIONS,
}

data class SkillValidationIssue(val code: SkillIssueCode, val message: String)
enum class SkillOrigin { TRUSTED_BUILTIN, UNTRUSTED_IMPORT }

/** Construction is restricted; no public manifest-controlled trusted flag exists. */
class ValidatedSkill internal constructor(val manifest: SkillManifest, val origin: SkillOrigin) {
    val allowedTools: Set<String> get() = manifest.tools
    val requiredPermissions: Set<SkillPermission> get() = manifest.requiredPermissions
}

data class SkillValidationResult(val skill: ValidatedSkill?, val issues: List<SkillValidationIssue>) {
    val isValid: Boolean get() = skill != null && issues.isEmpty()
}

data class SkillParseResult(val manifest: SkillManifest?, val issues: List<SkillValidationIssue>) {
    val isValid: Boolean get() = manifest != null && issues.isEmpty()
}

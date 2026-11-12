package com.nyx.ai.skills

/**
 * The only trusted skill source. No registration/import API exists, and callers cannot validate an
 * arbitrary manifest as builtin. These declarations do not execute tools or bypass CommandPolicy.
 */
object BuiltinSkillRegistry {
    const val WORKSPACE_INSPECTION = "builtin.workspace-inspection"
    const val SOURCE_REPAIR = "builtin.source-repair"
    const val BUILD_TEST = "builtin.build-test"
    const val GIT_INSPECTION = "builtin.git-inspection"
    const val LOCAL_FILESYSTEM_RESEARCH = "builtin.local-filesystem-research"

    /** Returns new declarations so mutating a returned collection cannot change registry authority. */
    fun all(): List<SkillManifest> = listOf(
        SkillManifest(
            WORKSPACE_INSPECTION, "1.0.0", "Inspect the selected workspace and identify source and project markers without editing files.",
            setOf("file_list", "file_read", "project_detect"), setOf(SkillPermission.WORKSPACE_READ),
            setOf(SkillPrecondition.WORKSPACE_SELECTED),
            instructions = listOf("List files only within the selected workspace.", "Read bounded project markers and relevant source files.",
                "Report project type, paths, and evidence references; do not guess missing build commands."),
        ),
        SkillManifest(
            SOURCE_REPAIR, "1.0.0", "Make a narrowly scoped, explicitly approved source repair and preserve original evidence.",
            setOf("file_read", "file_search", "file_write"), setOf(SkillPermission.WORKSPACE_READ, SkillPermission.WORKSPACE_WRITE),
            setOf(SkillPrecondition.WORKSPACE_SELECTED),
            instructions = listOf("Read the affected sources and capture original evidence with source hashes.",
                "Describe the minimal repair and obtain existing write approval before modifying files.",
                "Write only approved source paths; stop if the source hash changed after inspection.",
                "Re-read the changes, report the diff and outstanding verification; do not claim unrun tests passed."),
        ),
        SkillManifest(
            BUILD_TEST, "1.0.0", "Run authorized project build and tests using existing terminal tools and record actual exit evidence.",
            setOf("project_detect", "build_run", "test_run"),
            setOf(SkillPermission.WORKSPACE_READ, SkillPermission.WORKSPACE_WRITE, SkillPermission.TERMINAL_EXECUTE,
                SkillPermission.BUILD_EXECUTE, SkillPermission.TEST_EXECUTE),
            setOf(SkillPrecondition.WORKSPACE_SELECTED, SkillPrecondition.TERMINAL_AVAILABLE, SkillPrecondition.PROJECT_DETECTED),
            instructions = listOf("Confirm detected build and test commands and explicit execution approval.",
                "Use offline command flags where supported; stop and report if dependencies require network access.",
                "Run the approved build and then tests through the existing policy-protected tools.",
                "Preserve exit codes and bounded logs, checkpoint findings, and report failures without automatic dependency installation."),
        ),
        SkillManifest(
            GIT_INSPECTION, "1.0.0", "Inspect local Git status, diff, and history only. Never automatically stage, commit, fetch, push, or reset.",
            setOf("repo_status", "repo_diff", "file_read"),
            setOf(SkillPermission.WORKSPACE_READ, SkillPermission.TERMINAL_EXECUTE, SkillPermission.GIT_READ),
            setOf(SkillPrecondition.WORKSPACE_SELECTED, SkillPrecondition.TERMINAL_AVAILABLE, SkillPrecondition.GIT_REPOSITORY),
            instructions = listOf("Confirm a repository exists in the selected workspace.",
                "Use the fixed repo_status probe and recorded repo_diff tool; do not supply shell text.",
                "Report changed paths and bounded evidence; never stage, commit, modify history, or contact remotes."),
        ),
        SkillManifest(
            LOCAL_FILESYSTEM_RESEARCH, "1.0.0", "Research local files within an explicit workspace scope, cite evidence, and avoid stale observations.",
            setOf("file_list", "file_search", "file_read"), setOf(SkillPermission.WORKSPACE_READ),
            setOf(SkillPrecondition.WORKSPACE_SELECTED),
            instructions = listOf("Select explicit in-workspace paths; do not traverse symlinks or search outside the scope.",
                "Use deterministic indexed paths and budgeted reads, citing source path and full-content SHA-256.",
                "Invalidate changed sources before reusing observations; keep historical original evidence separately.",
                "Summarize findings and create a bounded checkpoint without changing researched files."),
        ),
    )

    fun find(id: String): SkillManifest? = all().firstOrNull { it.id == id }

    /** Accepts only an installed ID, not a caller-supplied manifest or trust claim. */
    fun validate(id: String, context: SkillValidationContext): SkillValidationResult {
        val builtin = find(id) ?: return SkillValidationResult(null,
            listOf(SkillValidationIssue(SkillIssueCode.INVALID_ID, "No trusted builtin with this identifier")))
        return SkillValidator.validateBuiltin(builtin, context)
    }
}

package com.nyx.ai.skills

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure JVM coverage for the Arsenal subsystem. No Android Context is constructed: the file-backed
 * [ArsenalRegistry] and the stateless [ArsenalSelector]/[ArsenalPrompt] carry every rule the Android
 * [ArsenalRepository] delegates to.
 */
class ArsenalRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    // -------------------------------------------------------------------------------------------------
    // Catalog truthfulness
    // -------------------------------------------------------------------------------------------------

    @Test
    fun builtinsCoverNineDistinctSkillsWithRealToolsAndOfflineConstraints() {
        val builtins = ArsenalSkills.builtins()
        assertEquals(9, builtins.size)
        assertEquals(9, builtins.map { it.id }.toSet().size)
        assertEquals(ArsenalSkills.IDS, builtins.map { it.id }.toSet())
        assertTrue(ArsenalSkills.RESERVED_IDS.containsAll(builtins.map { it.id }))
        assertTrue(ArsenalSkills.RESERVED_IDS.contains(BuiltinSkillRegistry.WORKSPACE_INSPECTION))
        assertTrue(ArsenalSkills.DECLARABLE_TOOLS.intersect(ArsenalSkills.HOST_ONLY_TOOLS).isEmpty())

        for (manifest in builtins) {
            assertTrue("${manifest.id} must declare real tools", manifest.tools.isNotEmpty())
            assertTrue(
                "${manifest.id} declares a host-only tool: ${manifest.tools - realAppTools}",
                manifest.tools.all { it in realAppTools && it in ArsenalSkills.DECLARABLE_TOOLS },
            )
            assertTrue(manifest.id, SkillPrecondition.WORKSPACE_SELECTED in manifest.preconditions)
            assertTrue(manifest.id, manifest.instructions.isNotEmpty())
            assertTrue(manifest.id, manifest.instructions.size <= manifest.constraints.maxSteps)
            assertTrue(manifest.id, manifest.version.matches(Regex("\\d+\\.\\d+\\.\\d+")))
            val constraints = manifest.constraints
            assertTrue(manifest.id, constraints.workspaceOnly)
            assertFalse(manifest.id, constraints.allowNetwork)
            assertFalse(manifest.id, constraints.allowDestructive)
            assertFalse(manifest.id, constraints.allowAutoCommit)
            assertTrue(manifest.id, constraints.requireWriteApproval)
            assertTrue(manifest.id, constraints.maxSteps in 1..8)
            assertTrue(manifest.id, constraints.maxOutputChars in 1..32_000)
            assertTrue(manifest.id, manifest.failureHandling.preserveEvidence)
            assertTrue(manifest.id, manifest.failureHandling.maxRetries in 0..1)
        }
    }

    @Test
    fun everyBuiltinValidatesAsTrustedAndTerminalSkillsDeclareTheirPreconditions() {
        val registry = ArsenalRegistry(temporary.newFolder())
        for (manifest in ArsenalSkills.builtins()) {
            val result = registry.validate(manifest.id, ArsenalHostPolicy.trustedBuiltins())
            assertTrue("${manifest.id}: ${result.issues}", result.isValid)
            assertEquals(SkillOrigin.TRUSTED_BUILTIN, result.skill?.origin)
            assertEquals(manifest.tools, result.skill?.allowedTools)
            assertEquals(SkillOrigin.TRUSTED_BUILTIN, registry.find(manifest.id)?.origin)
        }

        val git = ArsenalSkills.find(ArsenalSkills.GIT_OPERATIONS)!!
        assertTrue(SkillPrecondition.TERMINAL_AVAILABLE in git.preconditions)
        assertTrue(SkillPrecondition.GIT_REPOSITORY in git.preconditions)
        assertTrue(SkillPermission.GIT_READ in git.requiredPermissions)
        for (id in listOf(ArsenalSkills.TESTING, ArsenalSkills.DEBUGGING, ArsenalSkills.ARTIFACT_GENERATION)) {
            assertTrue(id, SkillPrecondition.TERMINAL_AVAILABLE in ArsenalSkills.find(id)!!.preconditions)
            assertTrue(id, SkillPrecondition.PROJECT_DETECTED in ArsenalSkills.find(id)!!.preconditions)
        }
        assertFalse(ArsenalSkills.builtins().any { "terminal_run" in it.tools })
    }

    @Test
    fun researchSkillKeepsNetworkOffAndDelegatesBoundedFetchToTheHost() {
        val research = ArsenalSkills.find(ArsenalSkills.RESEARCH)!!
        assertFalse(research.constraints.allowNetwork)
        assertFalse("fetch_url" in research.tools)
        assertFalse("terminal_run" in research.tools)
        assertTrue(research.description.contains("host", ignoreCase = true))
        assertTrue(research.description.contains("five"))
        val text = research.instructions.joinToString(" ")
        assertTrue(text.contains("supervised network fetch"))
        assertTrue(text.contains("untrusted"))
    }

    @Test
    fun changingReturnedBuiltinDoesNotChangeTheFixedCatalog() {
        val first = ArsenalSkills.builtins().first { it.id == ArsenalSkills.CODING }
        runCatching { (first.tools as MutableSet<String>).add("terminal_run") }
        assertFalse("terminal_run" in ArsenalSkills.find(ArsenalSkills.CODING)!!.tools)
    }

    // -------------------------------------------------------------------------------------------------
    // Relevance selection
    // -------------------------------------------------------------------------------------------------

    @Test
    fun selectionRanksCoreEngineeringGoalsToTheExpectedSkill() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val expectations = mapOf(
            "Review the git status and diff before the next commit" to ArsenalSkills.GIT_OPERATIONS,
            "Run the unit tests and report failures" to ArsenalSkills.TESTING,
            "Update the gradle dependencies and lockfile offline" to ArsenalSkills.DEPENDENCY_MANAGEMENT,
            "Build a release APK artifact for the project" to ArsenalSkills.ARTIFACT_GENERATION,
            "Implement a feature in the code" to ArsenalSkills.CODING,
            "Inspect the project structure and detect the build system" to ArsenalSkills.PROJECT_INSPECTION,
        )
        for ((goal, expected) in expectations) {
            val selected = registry.selectForGoal(goal, limit = 3)
            assertTrue("$goal -> $selected", selected.isNotEmpty())
            assertEquals(goal, expected, selected.first().id)
        }
    }

    @Test
    fun selectionRanksKnowledgeQualityAndDiagnosisGoalsToTheExpectedSkill() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val expectations = mapOf(
            "Write documentation for the public API" to ArsenalSkills.DOCUMENTATION,
            "Fix a failing null pointer exception in the parser" to ArsenalSkills.DEBUGGING,
            "Research best practices from public web sources" to ArsenalSkills.RESEARCH,
        )
        for ((goal, expected) in expectations) {
            val selected = registry.selectForGoal(goal, limit = 3)
            assertTrue("$goal -> $selected", selected.isNotEmpty())
            assertEquals(goal, expected, selected.first().id)
        }
    }

    @Test
    fun selectionExcludesDisabledSkillsFromSelectionAndPrompt() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val goal = "Review the git status and diff before the next commit"
        assertTrue(registry.setEnabled(ArsenalSkills.GIT_OPERATIONS, false))
        val selected = registry.selectForGoal(goal, limit = 3)
        assertTrue(selected.none { it.id == ArsenalSkills.GIT_OPERATIONS })
        assertFalse(registry.promptBlock(goal).contains(ArsenalSkills.GIT_OPERATIONS))
        assertFalse(registry.list().first { it.id == ArsenalSkills.GIT_OPERATIONS }.enabled)
    }

    @Test
    fun selectionIsBoundedAndNeverReturnsEveryInstalledSkill() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val everySubject = "implement code, review git status, inspect project structure, research web sources, " +
            "write documentation, run unit tests, debug exceptions, update gradle dependencies, build apk artifacts"
        assertTrue(registry.selectForGoal(everySubject, limit = 99).size <= ArsenalLimits.MAX_SELECTED)
        assertTrue(registry.selectForGoal(everySubject, limit = 99).size < 9)
        assertTrue(registry.selectForGoal(everySubject).size <= 3)
        assertTrue(registry.selectForGoal(everySubject, limit = 0).size <= 1)
        assertTrue(registry.selectForGoal(everySubject, limit = -7).size <= 1)
    }

    @Test
    fun selectorRequiresPositiveRelevanceAndFallsBackToInspection() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val unrelated = registry.selectForGoal("zzzz qqqq", limit = 3)
        assertEquals(listOf(ArsenalSkills.PROJECT_INSPECTION), unrelated.map { it.id })

        assertTrue(registry.setEnabled(ArsenalSkills.PROJECT_INSPECTION, false))
        assertTrue(registry.selectForGoal("zzzz qqqq").isEmpty())

        val ranked = ArsenalSelector.rank("Review the git status and diff", registry.list())
        assertTrue(ranked.all { it.score > 0 })
        assertEquals(ranked.map { it.score }, ranked.map { it.score }.sortedDescending())
    }

    @Test
    fun selectorScoringIsDeterministicAndStableAcrossCalls() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val goal = "Run the unit tests and report failures"
        val first = registry.selectForGoal(goal)
        repeat(5) { assertEquals(first.map { it.id }, registry.selectForGoal(goal).map { it.id }) }
        assertEquals(first.map { it.id }, ArsenalSelector.select(goal, registry.list().reversed()).map { it.id })
    }

    // -------------------------------------------------------------------------------------------------
    // Prompt rendering
    // -------------------------------------------------------------------------------------------------

    @Test
    fun promptBlockListsOnlySelectedIdVersionAndInstructions() {
        val registry = ArsenalRegistry(temporary.newFolder())
        assertNull(registry.configure(ArsenalSkills.GIT_OPERATIONS, "profile", "secret-value"))
        val prompt = registry.promptBlock("Review the git status and diff before the next commit", limit = 1)
        assertTrue(prompt.contains(ArsenalSkills.GIT_OPERATIONS))
        assertTrue(prompt.contains("@1.0.0"))
        assertTrue(prompt.contains("Call repo_status"))
        assertTrue(prompt.contains(ArsenalPrompt.DISCLAIMER))
        assertTrue(prompt.contains("guidance, not authority"))
        assertFalse(prompt.contains("secret-value"))
        assertFalse(prompt.contains("Inspect Git repository status"))
        assertFalse(prompt.contains("requiredPermissions"))
        assertFalse(prompt.contains("WORKSPACE_READ"))
        assertFalse(prompt.contains("TERMINAL_EXECUTE"))
        assertFalse(prompt.contains("approval-gated write"))
        assertFalse(prompt.contains("[2]"))
        for (manifest in ArsenalSkills.builtins().filter { it.id != ArsenalSkills.GIT_OPERATIONS }) {
            assertFalse(manifest.id, prompt.contains(manifest.id))
        }
    }

    @Test
    fun promptBlockIsBoundedForHugeGoalsAndLimits() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val hugeGoal = "git tests dependencies artifact documentation research debug coding inspect ".repeat(200)
        val prompt = registry.promptBlock(hugeGoal, limit = 99)
        assertTrue(prompt.length <= ArsenalLimits.MAX_PROMPT_CHARS)
        assertTrue(prompt.contains(ArsenalPrompt.DISCLAIMER))

        val narrowed = registry.promptBlock(hugeGoal, limit = 3)
        assertTrue(narrowed.length <= ArsenalLimits.MAX_PROMPT_CHARS)
        assertTrue(narrowed.lines().count { it.startsWith("[") } <= 3)
    }

    @Test
    fun promptRenderingHonoursAnExplicitSmallerBudgetAndEmptySelections() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val skill = registry.find(ArsenalSkills.CODING)!!
        assertTrue(ArsenalPrompt.render(listOf(skill), maxChars = 300).length <= 300)
        assertTrue(ArsenalPrompt.render(listOf(skill), maxChars = 1).length <= 1)

        val empty = ArsenalPrompt.render(emptyList())
        assertTrue(empty.contains(ArsenalPrompt.DISCLAIMER))
        assertTrue(empty.contains("No installed skill matched"))
    }

    // -------------------------------------------------------------------------------------------------
    // Import validation
    // -------------------------------------------------------------------------------------------------

    @Test
    fun importRejectsOversizedPayloadBeforeParsing() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val oversized = registry.importSkill("x".repeat(ArsenalLimits.MAX_IMPORT_CHARS + 1))
        assertFalse(oversized.ok)
        assertNull(oversized.skill)
        assertEquals(listOf(ArsenalIssueCode.IMPORT_TOO_LARGE), oversized.issues.map { it.code })

        val atBound = registry.importSkill(" ".repeat(ArsenalLimits.MAX_IMPORT_CHARS))
        assertFalse(atBound.ok)
        assertEquals(listOf(ArsenalIssueCode.INVALID_FORMAT), atBound.issues.map { it.code })
    }

    @Test
    fun importRejectsMalformedAndUnknownFieldManifestsWithStableCodes() {
        val registry = ArsenalRegistry(temporary.newFolder())
        assertEquals(
            listOf(ArsenalIssueCode.INVALID_FORMAT),
            registry.importSkill("{ not json").issues.map { it.code },
        )
        assertEquals(
            listOf(ArsenalIssueCode.INVALID_FORMAT),
            registry.importSkill("{\"id\":\"local.example\"}").issues.map { it.code },
        )
        val unknownField = registry.importSkill(
            JSONObject(manifestJson()).put("trusted", true).toString(),
        )
        assertEquals(listOf(ArsenalIssueCode.UNKNOWN_FIELD), unknownField.issues.map { it.code })
        assertTrue(registry.list().none { it.id == "local.example" })
    }

    @Test
    fun importRejectsUnknownToolsAndRealToolsOutsideTheImportSubset() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val unknown = registry.importSkill(manifestJson(tools = listOf("quantum_teleport")))
        assertFalse(unknown.ok)
        assertTrue(unknown.issues.any { it.code == ArsenalIssueCode.UNKNOWN_TOOL })

        for (tool in listOf("terminal_run", "file_write", "workspace_write", "build_run", "test_run")) {
            val result = registry.importSkill(
                manifestJson(
                    tools = listOf(tool),
                    permissions = listOf("WORKSPACE_READ", "WORKSPACE_WRITE", "TERMINAL_EXECUTE"),
                ),
            )
            assertFalse(tool, result.ok)
            assertTrue(tool, result.issues.any { it.code == ArsenalIssueCode.TOOL_ESCALATION })
        }

        val escalated = registry.importSkill(
            manifestJson(tools = listOf("file_read"), permissions = listOf("WORKSPACE_READ", "GIT_READ")),
        )
        assertFalse(escalated.ok)
        assertTrue(escalated.issues.any { it.code == ArsenalIssueCode.PERMISSION_ESCALATION })
        assertTrue(registry.list().none { it.origin == SkillOrigin.UNTRUSTED_IMPORT })
    }

    @Test
    fun importRejectsReservedAndDuplicateIdentifiers() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val reserved = registry.importSkill(manifestJson(id = ArsenalSkills.CODING))
        assertFalse(reserved.ok)
        assertEquals(listOf(ArsenalIssueCode.RESERVED_ID), reserved.issues.map { it.code })

        val legacy = registry.importSkill(manifestJson(id = BuiltinSkillRegistry.GIT_INSPECTION))
        assertFalse(legacy.ok)
        assertEquals(listOf(ArsenalIssueCode.RESERVED_ID), legacy.issues.map { it.code })

        val first = registry.importSkill(manifestJson())
        assertTrue(first.issues.toString(), first.ok)
        assertEquals(SkillOrigin.UNTRUSTED_IMPORT, first.skill?.origin)
        assertFalse(first.skill!!.trusted)

        val duplicate = registry.importSkill(manifestJson(version = "1.0.1"))
        assertFalse(duplicate.ok)
        assertEquals(listOf(ArsenalIssueCode.DUPLICATE_ID), duplicate.issues.map { it.code })
    }

    @Test
    fun importRejectsEscalatingConstraintsAndIncompatibleSchemas() {
        val registry = ArsenalRegistry(temporary.newFolder())
        val cases = listOf(
            ArsenalIssueCode.INVALID_CONSTRAINTS to manifestJson(constraints = constraintsJson(allowNetwork = true)),
            ArsenalIssueCode.INVALID_CONSTRAINTS to manifestJson(constraints = constraintsJson(maxSteps = 0)),
            ArsenalIssueCode.INVALID_CONSTRAINTS to manifestJson(constraints = constraintsJson(requireWriteApproval = false)),
            ArsenalIssueCode.INVALID_INSTRUCTIONS to manifestJson(instructions = listOf("")),
            ArsenalIssueCode.INVALID_VERSION to manifestJson(version = "1.0"),
            ArsenalIssueCode.INVALID_DESCRIPTION to manifestJson(description = ""),
            ArsenalIssueCode.INCOMPATIBLE_HOST to manifestJson(compatibility = compatibilityJson(manifestVersion = 2)),
            ArsenalIssueCode.MISSING_PRECONDITION to manifestJson(preconditions = emptyList()),
        )
        for ((expected, json) in cases) {
            val result = registry.importSkill(json)
            assertFalse(expected.name, result.ok)
            assertTrue("$expected -> ${result.issues}", result.issues.any { it.code == expected })
        }
        assertTrue(registry.list().none { it.origin == SkillOrigin.UNTRUSTED_IMPORT })
    }

    // -------------------------------------------------------------------------------------------------
    // Persistence, enablement, configuration, removal
    // -------------------------------------------------------------------------------------------------

    @Test
    fun importStoresAVersionedManifestAndSurvivesRestart() {
        val root = temporary.newFolder()
        val registry = ArsenalRegistry(root)
        val result = registry.importSkill(manifestJson(tools = listOf("workspace_read", "file_list")))
        assertTrue(result.issues.toString(), result.ok)

        val file = File(File(root, "skills"), "local.example@1.0.0.json")
        assertTrue(file.isFile)
        val stored = file.readText()
        assertTrue(SkillManifestParser.parse(stored).isValid)
        assertTrue(stored.contains("\"version\":\"1.0.0\""))

        val reloaded = ArsenalRegistry(root).list().first { it.id == "local.example" }
        assertEquals("1.0.0", reloaded.version)
        assertEquals(SkillOrigin.UNTRUSTED_IMPORT, reloaded.origin)
        assertTrue(reloaded.enabled)
    }

    @Test
    fun corruptOrEscalatingStoredManifestsAreIgnoredInsteadOfLoaded() {
        val root = temporary.newFolder()
        val registry = ArsenalRegistry(root)
        val skills = File(root, "skills").apply { mkdirs() }
        File(skills, "broken.json").writeText("{ not json")
        File(skills, "escalating.json").writeText(
            manifestJson(id = "local.escalating", tools = listOf("file_write"), permissions = listOf("WORKSPACE_WRITE")),
        )
        assertTrue(ArsenalRegistry(root).list().none { it.origin == SkillOrigin.UNTRUSTED_IMPORT })
        assertTrue(registry.importSkill(manifestJson(id = "local.example")).ok)
        assertEquals(1, ArsenalRegistry(root).list().count { it.origin == SkillOrigin.UNTRUSTED_IMPORT })
    }

    @Test
    fun enableAndConfigurationPersistAcrossRegistryInstances() {
        val root = temporary.newFolder()
        val first = ArsenalRegistry(root)
        assertTrue(first.importSkill(manifestJson()).ok)
        assertFalse(first.setEnabled("missing.skill", true))
        assertTrue(first.setEnabled("local.example", false))
        assertNull(first.configure("local.example", "profile", "strict"))
        assertNull(first.configure("local.example", "budget", "120"))
        assertTrue(first.setEnabled(ArsenalSkills.GIT_OPERATIONS, false))

        val second = ArsenalRegistry(root)
        val imported = second.find("local.example")!!
        assertFalse(imported.enabled)
        assertEquals(mapOf("profile" to "strict", "budget" to "120"), imported.config)
        assertFalse(second.find(ArsenalSkills.GIT_OPERATIONS)!!.enabled)
        assertTrue(second.find(ArsenalSkills.CODING)!!.enabled)
    }

    @Test
    fun configurationRejectsUnboundedKeysValuesAndUnknownSkills() {
        val registry = ArsenalRegistry(temporary.newFolder())
        assertTrue(registry.importSkill(manifestJson()).ok)

        assertEquals(ArsenalIssueCode.NOT_FOUND, registry.configure("missing.skill", "k", "v")?.code)
        assertEquals(ArsenalIssueCode.INVALID_CONFIG, registry.configure("local.example", "Bad Key", "v")?.code)
        assertEquals(ArsenalIssueCode.INVALID_CONFIG, registry.configure("local.example", "k".repeat(49), "v")?.code)
        assertEquals(ArsenalIssueCode.INVALID_CONFIG, registry.configure("local.example", "key", "v".repeat(201))?.code)

        repeat(ArsenalLimits.MAX_CONFIG_KEYS) { index ->
            assertNull(registry.configure("local.example", "key$index", "value$index"))
        }
        assertEquals(ArsenalIssueCode.INVALID_CONFIG, registry.configure("local.example", "overflow", "value")?.code)
        assertNull(registry.configure("local.example", "key0", ""))
        assertEquals(ArsenalLimits.MAX_CONFIG_KEYS - 1, registry.find("local.example")!!.config.size)
    }

    @Test
    fun removalDeletesImportedSkillsButNeverBuiltins() {
        val root = temporary.newFolder()
        val registry = ArsenalRegistry(root)
        assertTrue(registry.importSkill(manifestJson()).ok)
        assertFalse(registry.remove(ArsenalSkills.GIT_OPERATIONS))
        assertNotNull(ArsenalRegistry(root).find(ArsenalSkills.GIT_OPERATIONS))

        assertTrue(registry.remove("local.example"))
        assertNull(ArsenalRegistry(root).find("local.example"))
        assertFalse(registry.remove("local.example"))
        assertFalse(File(File(root, "skills"), "local.example@1.0.0.json").exists())
    }

    @Test
    fun revalidationUsesTheSuppliedHostPolicy() {
        val registry = ArsenalRegistry(temporary.newFolder())
        assertTrue(registry.importSkill(manifestJson()).ok)
        assertTrue(registry.validate("local.example", ArsenalHostPolicy.untrustedImport()).isValid)
        val narrowed = registry.validate(
            "local.example",
            ArsenalHostPolicy.untrustedImport().copy(allowedTools = setOf("workspace_read")),
        )
        assertFalse(narrowed.isValid)
        assertTrue(narrowed.issues.any { it.code == SkillIssueCode.TOOL_ESCALATION })

        val unknown = registry.validate("missing.skill")
        assertFalse(unknown.isValid)
        assertEquals(SkillIssueCode.INVALID_ID, unknown.issues.single().code)
    }

    // -------------------------------------------------------------------------------------------------
    // Execution logs
    // -------------------------------------------------------------------------------------------------

    @Test
    fun logsAreCappedAtThreeHundredAndReturnNewestFirst() {
        val root = temporary.newFolder()
        var tick = 1_000L
        val registry = ArsenalRegistry(root) { tick++ }
        repeat(ArsenalLimits.MAX_LOGS + 5) { index ->
            assertTrue(
                registry.recordExecution(
                    skillId = ArsenalSkills.TESTING,
                    objectiveId = "obj-$index",
                    success = index % 2 == 0,
                    detail = "exit $index",
                    evidence = listOf("exit:$index"),
                ),
            )
        }
        assertEquals(ArsenalLimits.MAX_LOGS, registry.logFile.readLines().count { it.isNotBlank() })

        val newest = registry.recentLogs(5)
        assertEquals(5, newest.size)
        assertEquals("obj-304", newest.first().objectiveId)
        assertEquals("obj-300", newest.last().objectiveId)
        assertTrue(newest.first().timestamp > newest.last().timestamp)
        assertTrue(newest.first().success)

        assertEquals(ArsenalLimits.MAX_LOGS, registry.recentLogs(1_000).size)
        assertTrue(registry.recentLogs(0).isEmpty())
        assertTrue(registry.recentLogs(-3).isEmpty())
        assertTrue(registry.recentLogs(ArsenalLimits.MAX_LOGS + 50).none { it.objectiveId == "obj-0" })
    }

    @Test
    fun logsBoundDetailAndEvidenceAndTolerateCorruptLines() {
        val root = temporary.newFolder()
        val registry = ArsenalRegistry(root)
        assertTrue(
            registry.recordExecution(
                skillId = ArsenalSkills.CODING,
                objectiveId = "o".repeat(400),
                success = false,
                detail = "x".repeat(1_000) + "\nsecond line",
                evidence = (1..12).map { "ref-$it-" + "y".repeat(500) },
            ),
        )
        val entry = registry.recentLogs(1).single()
        assertEquals(ArsenalLimits.MAX_LOG_DETAIL_CHARS, entry.detail.length)
        assertFalse(entry.detail.contains("\n"))
        assertEquals(ArsenalLimits.MAX_OBJECTIVE_CHARS, entry.objectiveId.length)
        assertEquals(ArsenalLimits.MAX_LOG_EVIDENCE, entry.evidence.size)
        assertTrue(entry.evidence.all { it.length <= ArsenalLimits.MAX_LOG_EVIDENCE_CHARS })

        registry.logFile.appendText("not json\n")
        assertEquals(1, registry.recentLogs(10).size)
        assertFalse(registry.recordExecution("", "obj", true, "detail"))
        assertFalse(registry.recordExecution("   ", "obj", true, "detail"))
    }

    // -------------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------------

    private val realAppTools = setOf(
        // AgentTools
        "calculator", "memory_read", "memory_write", "plan_task", "workspace_read", "workspace_write",
        // TerminalAgentTools
        "terminal_run", "file_read", "file_write", "file_list", "file_search", "project_detect",
        "project_inspect", "source_search", "repo_diff", "repo_status", "project_run", "build_run", "test_run",
        // NetworkAgentTools
        "fetch_url",
    )

    private fun constraintsJson(
        workspaceOnly: Boolean = true,
        allowNetwork: Boolean = false,
        allowDestructive: Boolean = false,
        allowAutoCommit: Boolean = false,
        requireWriteApproval: Boolean = true,
        maxSteps: Int = 12,
        maxOutputChars: Int = 8_000,
    ): JSONObject = JSONObject()
        .put("workspaceOnly", workspaceOnly)
        .put("allowNetwork", allowNetwork)
        .put("allowDestructive", allowDestructive)
        .put("allowAutoCommit", allowAutoCommit)
        .put("requireWriteApproval", requireWriteApproval)
        .put("maxSteps", maxSteps)
        .put("maxOutputChars", maxOutputChars)

    private fun failureHandling(): JSONObject = JSONObject()
        .put("mode", "STOP_AND_REPORT")
        .put("maxRetries", 0)
        .put("preserveEvidence", true)

    private fun compatibilityJson(manifestVersion: Int = 1): JSONObject = JSONObject()
        .put("manifestVersion", manifestVersion)
        .put("minHostVersion", "1.0.0")
        .put("maxHostMajor", 1)

    private fun manifestJson(
        id: String = "local.example",
        version: String = "1.0.0",
        description: String = "Read bounded local evidence",
        tools: List<String> = listOf("file_read"),
        permissions: List<String> = listOf("WORKSPACE_READ"),
        preconditions: List<String> = listOf("WORKSPACE_SELECTED"),
        constraints: JSONObject = constraintsJson(),
        compatibility: JSONObject = compatibilityJson(),
        instructions: List<String> = listOf("Read bounded local evidence."),
    ): String = JSONObject()
        .put("id", id)
        .put("version", version)
        .put("description", description)
        .put("tools", tools)
        .put("requiredPermissions", permissions)
        .put("preconditions", preconditions)
        .put("constraints", constraints)
        .put("failureHandling", failureHandling())
        .put("compatibility", compatibility)
        .put("instructions", instructions)
        .toString()
}

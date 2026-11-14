package com.nyx.ai.autonomy

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class PlanningPromptPolicyTest {
    private val tools = setOf("workspace_read", "workspace_write", "terminal_run", "file_read", "file_write", "test_run", "build_run")
    private val kotlinGoal = "Create a standalone Kotlin command-line calculator in the approved workspace. Include TestMain.kt behavioral assertions. Build and run the tests. Do not create an Android app."

    @Test fun kotlinCliWithExplicitAndroidNegationUsesCompactPlan() {
        val profile = CompactPlanningPolicy.profile(kotlinGoal, true, tools, 4096)
        assertTrue(profile.compact)
        assertFalse(profile.singleStep)
        assertEquals(384, profile.maxOutputTokens)
        assertEquals(2048, profile.maxContextTokens)
        assertTrue("development" in profile.allowedWorkers!!)
        assertFalse(CompactPlanningPolicy.profile("Create an Android app with a database.").compact)
    }

    @Test fun standaloneJvmNarrowingNeverAdvertisesUnavailableDevelopment() {
        val profile = CompactPlanningPolicy.profile(kotlinGoal, true, tools, 4096)
        assertEquals(setOf("inference", "development"), profile.allowedWorkers)
        val disabled = CompactPlanningPolicy.profile(kotlinGoal, false, tools, 4096)
        assertFalse("development" in disabled.allowedWorkers!!)
        val java = CompactPlanningPolicy.profile("Create a standalone Java command-line calculator.", true, tools, 4096)
        assertEquals(setOf("inference", "development"), java.allowedWorkers)
        val forged = PlanningProfile(true, 384, setOf("development"))
        try { CompactPlanningPolicy.prompt(forged, false, tools); fail("Unavailable worker subset") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun negationIsClauseLocalAndDoesNotMaskLaterPositiveComplexWork() {
        listOf("Do not create an Android app.", "Never deploy production.", "No architecture migration.").forEach {
            assertTrue(it, CompactPlanningPolicy.profile(it).compact)
        }
        listOf("Do not delete files; create an Android app.", "Don't refactor, but deploy production.",
            "No migration. Build an app.", "Do not deploy and refactor the architecture.").forEach {
            assertFalse(it, CompactPlanningPolicy.profile(it).compact)
        }
    }

    @Test fun longMultilineAndComplexGoalsKeepFullDagWithoutTruncation() {
        listOf("x".repeat(801), "One\nTwo\nThree\nFour", "Refactor the architecture.").forEach {
            val profile = CompactPlanningPolicy.profile(it, true, tools, 16384)
            assertFalse(profile.compact)
            assertNull(profile.schema)
            assertEquals(1024, profile.maxOutputTokens)
            assertEquals(8192, profile.maxContextTokens)
        }
    }

    @Test fun explicitTextOnlyGoalSelectsOneFieldSchemaEvenWhenToolsExist() {
        val profile = CompactPlanningPolicy.profile("Write a short poem about the moon.", true, tools, 4096)
        assertTrue(profile.singleStep)
        assertEquals(setOf("inference"), profile.allowedWorkers)
        assertEquals(192, profile.maxOutputTokens)
        val schema = JSONObject(profile.schema!!)
        assertEquals(listOf("do"), schema.getJSONArray("required").strings())
        assertFalse(schema.getJSONObject("properties").has("steps"))
        val prompt = CompactPlanningPolicy.prompt(profile, true, tools)
        assertTrue(prompt.contains("NOT factual correctness"))
        assertFalse(prompt.contains("file_exists"))
        assertFalse(prompt.contains("terminal_run"))
        assertTrue(profile.schema!!.toByteArray(StandardCharsets.UTF_8).size < 250)
    }

    @Test fun resourceBearingTextAndAmbiguousGoalsRetainAvailableWorkers() {
        listOf("Write a poem and save it to note.txt.", "Explain the files in this workspace.", "Please help with this task.").forEach {
            val profile = CompactPlanningPolicy.profile(it, true, tools, 4096)
            assertFalse(it, profile.singleStep)
            assertTrue("filesystem" in profile.allowedWorkers!!)
        }
    }

    @Test fun capabilitySelectionRequiresBothActualNamesAndTerminalAvailability() {
        assertEquals(setOf("inference"), CompactPlanningPolicy.availableWorkers(true, emptySet()))
        assertEquals(setOf("inference", "filesystem", "verification"), CompactPlanningPolicy.availableWorkers(false, tools))
        assertFalse("development" in CompactPlanningPolicy.availableWorkers(true, setOf("terminal_run", "file_read")))
        assertTrue("development" in CompactPlanningPolicy.availableWorkers(true, setOf("terminal_run", "file_read", "file_write")))
        assertEquals(setOf("inference"), CompactPlanningPolicy.availableWorkers(true, setOf("TERMINAL_RUN", "terminal_run\ninject")))
    }

    @Test fun schemaContainsOnlySelectedWorkersAndNoCommandEnumsWhenUnavailable() {
        val profile = CompactPlanningPolicy.profile("Create a file in this workspace.", false, setOf("workspace_write"), 4096)
        val item = JSONObject(profile.schema!!).getJSONObject("properties").getJSONObject("steps").getJSONObject("items")
        assertEquals(setOf("filesystem", "inference"), item.getJSONObject("properties").getJSONObject("worker").getJSONArray("enum").strings().toSet())
        assertFalse(profile.schema!!.contains("exit_code"))
        val prompt = CompactPlanningPolicy.prompt(profile, false, setOf("workspace_write"))
        assertFalse(prompt.contains("development=edit"))
        assertFalse(prompt.contains("terminal=commands"))
    }

    @Test fun verificationExitCheckRequiresFixedTestCapabilityNotJustFileInspection() {
        val raw = """{"steps":[{"do":"Inspect note.txt","worker":"verification","check":{"kind":"exit_code","expected":"0"}}]}"""
        val tasks = ObjectivePlanValidator.parse(raw, "obj", "caller")
        val readOnly = CompactPlanningPolicy.profile("Inspect note.txt.", false, setOf("workspace_read"), 4096)
        assertFalse(readOnly.schema!!.contains("exit_code"))
        try { CompactPlanningPolicy.validateWorkers(tasks, readOnly); fail("Unobservable exit check") }
        catch (_: ObjectivePlanValidationException) { }
        val tested = CompactPlanningPolicy.profile("Run the fixed tests.", true, setOf("test_run"), 4096)
        CompactPlanningPolicy.validateWorkers(tasks, tested)
        assertTrue(tested.schema!!.contains("exit_code"))
    }

    @Test fun objectiveAndMandatoryContractRemainVerbatimInPreparedInput() {
        val profile = CompactPlanningPolicy.profile(kotlinGoal, true, tools, 4096)
        val system = CompactPlanningPolicy.prompt(profile, true, tools)
        val input = CompactPlanningPolicy.prepareInput(kotlinGoal, system, profile)
        assertEquals(system, input.systemPrompt)
        assertEquals("Objective:\n$kotlinGoal", input.objectivePrompt)
        assertNull(input.repairPrompt)
        assertEquals(bytes(system) + bytes(input.objectivePrompt), input.metrics.promptBytes)
        assertEquals(bytes(profile.schema!!), input.metrics.schemaBytes)
        assertEquals((input.metrics.promptBytes + input.metrics.schemaBytes + 3) / 4, input.metrics.estimatedInputTokens)
        assertTrue(input.metrics.summary().contains("estimated"))
        assertFalse(input.metrics.summary().contains(kotlinGoal))
    }

    @Test fun repairContextIsBoundedUnicodeSafeAndDoesNotReplaceObjective() {
        val profile = CompactPlanningPolicy.profile(kotlinGoal, true, tools, 4096)
        val original = "😀".repeat(4000) + "do something unsafe"
        val failure = PlanningFailure(PlanningFailureKind.INVALID_JSON, "Missing brace token=secret123")
        val input = CompactPlanningPolicy.prepareInput(kotlinGoal, CompactPlanningPolicy.prompt(profile, true, tools), profile, original to failure)
        assertTrue(input.metrics.repairCompacted)
        assertEquals("Objective:\n$kotlinGoal", input.objectivePrompt)
        assertTrue(input.repairPrompt!!.contains("untrusted data"))
        assertFalse(input.repairPrompt!!.contains("secret123"))
        val excerpt = input.repairPrompt!!.substringAfter("(excerpt may be omitted):\n")
        assertTrue(bytes(excerpt) <= 512)
        assertFalse(excerpt.lastOrNull()?.isHighSurrogate() == true)
        assertTrue(input.metrics.repairBytes < 1000)
    }

    @Test fun repairIsCompactedFurtherAtContextBoundaryRatherThanTruncatingObjective() {
        val profile = PlanningProfile(true, 128, setOf("inference"), 512, true)
        val system = "s".repeat(270)
        val goal = "g".repeat(10)
        val input = CompactPlanningPolicy.prepareInput(goal, system, profile,
            "x".repeat(4000) to PlanningFailure(PlanningFailureKind.SCHEMA_REJECTED, "bad"))
        assertEquals(system, input.systemPrompt)
        assertEquals("Objective:\n$goal", input.objectivePrompt)
        assertTrue(input.metrics.repairCompacted)
        assertTrue(input.metrics.promptBytes + input.metrics.schemaBytes <= (512 - 128 - 128) * 3)
    }

    @Test fun repairCannotReplaceMandatoryContractWhenItsHeaderDoesNotFit() {
        val profile = PlanningProfile(true, 128, setOf("inference"), 512, true)
        val system = "s".repeat(540)
        val goal = "g".repeat(10)
        assertEquals(system, CompactPlanningPolicy.prepareInput(goal, system, profile).systemPrompt)
        try {
            CompactPlanningPolicy.prepareInput(goal, system, profile,
                "broken" to PlanningFailure(PlanningFailureKind.INVALID_JSON, "bad"))
            fail("Repair contract must fit; no dropped objective/system context")
        } catch (error: PlanningFailureException) {
            assertEquals(PlanningFailureKind.CONTEXT_EXHAUSTED, error.failure.kind)
        }
    }

    @Test fun essentialInputOverBudgetFailsExplicitlyWithoutProviderOrPrefixPlanning() {
        val profile = PlanningProfile(true, 192, setOf("inference"), 512, true)
        try {
            CompactPlanningPolicy.prepareInput("full objective", "s".repeat(4000), profile)
            fail("Expected explicit context-policy rejection")
        } catch (error: PlanningFailureException) {
            assertEquals(PlanningFailureKind.CONTEXT_EXHAUSTED, error.failure.kind)
            assertTrue(error.message!!.contains("estimated, not provider tokens"))
            assertTrue(error.message!!.contains("no objective prefix"))
        }
        try {
            CompactPlanningPolicy.prepareInput("x".repeat(8001), "system", profile)
            fail("Expected objective-size rejection")
        } catch (error: PlanningFailureException) {
            assertEquals(PlanningFailureKind.SCHEMA_REJECTED, error.failure.kind)
        }
    }

    @Test fun smallConfiguredContextAndOutputAreNeverRaised() {
        val profile = CompactPlanningPolicy.profile("Explain gravity.", false, emptySet(), 512)
        assertEquals(512, profile.maxContextTokens)
        assertEquals(128, profile.maxOutputTokens)
        try { CompactPlanningPolicy.profile("Explain gravity.", false, emptySet(), 0); fail("invalid context") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun boundedRepairValidatesWholeResponseAndCapabilitySubsetBeforeReturn() = runBlocking {
        val profile = CompactPlanningPolicy.profile("Explain gravity.", false, emptySet(), 4096)
        var calls = 0
        var rejected = 0
        val tasks = PlanningRecovery.validatedPlan("obj", "caller", request = { attempt, repair ->
            calls++
            if (attempt == 1) { assertNull(repair); """{"do":"Explain gravity""" }
            else { assertNotNull(repair); """{"do":"Explain gravity concisely"}""" }
        }, onRejected = { _, _ -> rejected++ }, validate = { CompactPlanningPolicy.validateWorkers(it, profile) })
        assertEquals(2, calls)
        assertEquals(1, rejected)
        assertEquals(1, tasks.size)
        assertEquals("caller", tasks.single().agentId)
    }

    @Test fun unavailableWorkerCannotEscapeViaLegacyFullSchemaAndRepairIsBounded() = runBlocking {
        val profile = CompactPlanningPolicy.profile("Explain gravity.", false, emptySet(), 4096)
        val toolPlan = """{"tasks":[{"id":"tool","title":"Run","instructions":"Run a command","verification":"Exit observed","worker":"terminal","dependsOn":[],"evidence":[{"kind":"exit_code","expected":"0"}]}]}"""
        var calls = 0
        try {
            PlanningRecovery.validatedPlan("obj", "caller", request = { _, _ -> calls++; toolPlan },
                validate = { CompactPlanningPolicy.validateWorkers(it, profile) })
            fail("Unavailable worker must never be returned")
        } catch (error: PlanningFailureException) {
            assertEquals(PlanningFailureKind.SCHEMA_REJECTED, error.failure.kind)
        }
        assertEquals(2, calls)
    }

    @Test fun transportFailureDoesNotTriggerSchemaRepair() = runBlocking {
        var calls = 0
        try {
            PlanningRecovery.validatedPlan("obj", "caller", request = { _, _ -> calls++; throw PlanningTimeoutException("silent") })
            fail("Expected timeout")
        } catch (_: PlanningTimeoutException) { }
        assertEquals(1, calls)
    }

    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8).size
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}

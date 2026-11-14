package com.nyx.ai.autonomy

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactPlanningPolicyTest {
    @Test fun simpleObjectivesUse512TokensAndComplexObjectivesKeepFullSchema() {
        assertEquals(PlanningProfile(true, 512), CompactPlanningPolicy.profile("Write a short poem about the moon."))
        assertEquals(PlanningProfile(false, 1024), CompactPlanningPolicy.profile("Build an application with authentication and a database."))
        assertFalse(CompactPlanningPolicy.profile("x".repeat(801)).compact)
        assertNotNull(CompactPlanningPolicy.profile("Create a note").schema)
        assertNull(CompactPlanningPolicy.profile("Refactor the architecture").schema)
    }

    @Test fun compactPromptAndSchemaAreBoundedAndDoNotDuplicateFullDag() {
        val prompt = CompactPlanningPolicy.prompt(false, setOf("workspace_read", "workspace_write"))
        assertTrue(prompt.length < 2_200)
        assertFalse(prompt.contains("dependsOn"))
        assertTrue(prompt.contains("Terminal tools are disabled"))
        assertEquals(3, JSONObject(CompactPlanningPolicy.responseSchema).getJSONObject("properties").getJSONObject("steps").getInt("maxItems"))
    }

    @Test fun compactPlanExpandsSequentiallyWithCallerIdentityAndEvidence() {
        val raw = """{"steps":[{"do":"Create note.txt in the authorized workspace","worker":"filesystem","check":{"kind":"file_exists","target":"note.txt"}},{"do":"Explain the observed result","worker":"inference"}]}"""
        val tasks = ObjectivePlanValidator.parse(raw, "objective-a", "authorized-agent")
        assertEquals(2, tasks.size)
        assertEquals(listOf("step_1", "step_2"), tasks.map { it.key })
        assertEquals(listOf(tasks.first().id), tasks.last().dependsOn)
        assertTrue(tasks.all { it.agentId == "authorized-agent" })
        assertEquals(EvidenceKind.FILE_EXISTS, tasks.first().evidence.single().kind)
        assertEquals("note.txt", tasks.first().evidence.single().target)
        assertEquals(EvidenceKind.ARTIFACT_NONEMPTY, tasks.last().evidence.single().kind)
        assertTrue(tasks.last().verification.contains("semantic correctness is not proven"))
        assertEquals(tasks.map { it.id }, ObjectivePlanValidator.parse(raw, "objective-a", "another-agent").map { it.id })
        assertNotEquals(tasks.first().id, ObjectivePlanValidator.parse(raw, "objective-b", "authorized-agent").first().id)
    }

    @Test fun compactEvidenceNeedsRealObservationNotModelSuccessClaim() = runBlocking {
        val task = ObjectivePlanValidator.parse("""{"steps":[{"do":"Run an approved check","worker":"terminal","check":{"kind":"exit_code","expected":"0"}}]}""", "obj", "agent").single()
        assertFalse(TaskVerifier.verify(task.evidence, ObservationBundle(agentOutput = "It succeeded, exit 0")).passed)
        assertTrue(TaskVerifier.verify(task.evidence, ObservationBundle(exitCode = 0)).passed)
    }

    @Test fun compactRejectsMissingOrWeakToolEvidenceAndUnsupportedWorkers() {
        reject("""{"steps":[{"do":"Run it","worker":"terminal"}]}""")
        reject("""{"steps":[{"do":"Run it","worker":"terminal","check":{"kind":"artifact_nonempty"}}]}""")
        reject("""{"steps":[{"do":"Run it","worker":"filesystem","check":{"kind":"file_exists"}}]}""")
        reject("""{"steps":[{"do":"Run it","worker":"terminal","check":{"kind":"exit_code","expected":"success"}}]}""")
        reject("""{"steps":[{"do":"Run it","worker":"unrestricted"}]}""")
        reject("""{"steps":[{"do":"Explain it","worker":"inference","check":{"kind":"exit_code","expected":"0"}}]}""")
    }

    @Test fun compactRejectsExtraIdentityDependenciesAndMixedSchemas() {
        reject("""{"steps":[{"do":"Explain it","worker":"inference","agentId":"attacker"}]}""")
        reject("""{"steps":[{"do":"Explain it","worker":"inference","dependsOn":[]}]}""")
        reject("""{"tasks":[],"steps":[{"do":"Explain it","worker":"inference"}]}""")
        reject("""{"steps":[{"do":"Explain it","worker":"inference","do":"Other instruction"}]}""")
    }

    @Test fun compactRejectsEmptyOversizedAndOverlongStepsWithoutReturningPrefix() {
        reject("""{"steps":[]}""")
        reject("""{"steps":[${List(4) { """{"do":"Explain it","worker":"inference"}""" }.joinToString(",") }]}""")
        reject(JSONObject().put("steps", org.json.JSONArray().put(JSONObject().put("do", "x".repeat(1201)).put("worker", "inference"))).toString())
        reject("""{"steps":[{"do":"Valid first step","worker":"inference"},{"do":"Broken second step"}]}""")
    }

    @Test fun extractsOneCompleteObjectFromFenceOrProseWithoutChangingItsStrings() {
        val json = """{"steps":[{"do":"Explain a {brace} and an escaped \"quote\"","worker":"inference"}]}"""
        val plain = ObjectivePlanValidator.parseResponse(json, "obj", "agent")
        assertEquals(plain, ObjectivePlanValidator.parseResponse("```json\n$json\n```", "obj", "agent"))
        assertEquals(plain, ObjectivePlanValidator.parseResponse("Here is the plan:\n$json\nEnd of plan.", "obj", "agent"))
    }

    @Test fun extractionRejectsMultipleObjectsTrailingJsonAndTruncatedResponses() {
        val json = """{"steps":[{"do":"Explain it","worker":"inference"}]}"""
        rejectResponse("$json $json")
        rejectResponse("Plan: $json then $json")
        rejectResponse("Plan: $json null")
        rejectResponse("Plan: $json 42")
        rejectResponse("Plan: $json \"another value\"")
        rejectResponse("null $json")
        rejectResponse("\"A quoted envelope $json\"")
        rejectResponse("[$json]")
        rejectResponse("```json\n$json")
        rejectResponse("```json\n$json\n```\n```json\n$json\n```")
        rejectResponse("Plan: ${json.dropLast(1)}")
        rejectResponse("Plan: {\"steps\":[{\"do\":\"x\",\"worker\":\"inference\"},")
    }

    @Test fun fullDagRemainsCompatibleWithBranchingAndStableIds() {
        val raw = """{"tasks":[{"id":"last","title":"Combine","instructions":"Combine results","verification":"Review output","worker":"inference","dependsOn":["first"]},{"id":"first","title":"Start","instructions":"Analyze input","verification":"Review output","worker":"inference","dependsOn":[]}]}"""
        val tasks = ObjectivePlanValidator.parseResponse(raw, "obj", "agent")
        assertEquals(listOf("first", "last"), tasks.map { it.key })
        assertEquals(listOf(tasks.first().id), tasks.last().dependsOn)
    }

    @Test fun fullToolPlanIsRejectedBeforeExecutionWhenEvidenceIsMissing() {
        reject("""{"tasks":[{"id":"a","title":"Run","instructions":"Run it","verification":"Done","worker":"terminal","dependsOn":[]}]}""")
    }

    @Test fun malformedGrammarIsClassifiedAsJsonEvenWithoutJsonInMessage() {
        try {
            ObjectivePlanValidator.parse("""{"steps" [{"do":"Explain","worker":"inference"}]}""", "obj", "agent")
            fail("Expected malformed grammar rejection")
        } catch (error: ObjectivePlanValidationException) {
            assertEquals(PlanningFailureKind.INVALID_JSON, PlanningRecovery.classify(error).kind)
        }
    }

    private fun reject(raw: String) {
        try { ObjectivePlanValidator.parse(raw, "obj", "agent"); fail("Expected rejection") }
        catch (_: ObjectivePlanValidationException) { }
    }
    private fun rejectResponse(raw: String) {
        try { ObjectivePlanValidator.parseResponse(raw, "obj", "agent"); fail("Expected rejection") }
        catch (_: ObjectivePlanValidationException) { }
    }
}

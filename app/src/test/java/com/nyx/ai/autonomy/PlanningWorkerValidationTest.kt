package com.nyx.ai.autonomy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanningWorkerValidationTest {
    @Test fun minimalPlanHasStableCallerOwnedIdentityAndHonestInferenceEvidence() {
        val raw = """{"do":"Explain the requested result concisely"}"""
        val task = ObjectivePlanValidator.parseResponse(raw, "objective", "authorized-agent").single()
        assertEquals("inference", task.workerKind)
        assertEquals("step_1", task.key)
        assertEquals("authorized-agent", task.agentId)
        assertTrue(task.dependsOn.isEmpty())
        assertEquals(EvidenceKind.ARTIFACT_NONEMPTY, task.evidence.single().kind)
        assertTrue(task.verification.contains("semantic correctness is not proven"))
        assertEquals(task.id, ObjectivePlanValidator.parseResponse(raw, "objective", "other-agent").single().id)
        assertNotEquals(task.id, ObjectivePlanValidator.parseResponse(raw, "other-objective", "authorized-agent").single().id)
    }

    @Test fun minimalPlanRejectsAnyToolIdentityDependencyOrMixedSchemaFields() {
        listOf(
            """{"do":"Run it","worker":"terminal"}""",
            """{"do":"Run it","check":{"kind":"exit_code","expected":"0"}}""",
            """{"do":"Explain it","steps":[]}""",
            """{"do":"Explain it","tasks":[]}""",
            """{"do":"Explain it","agentId":"attacker"}""",
            """{"do":"Explain it","dependsOn":[]}""",
            """{"do":"first","do":"second"}""",
            """{"do":null}""", """{"do":"  "}""",
        ).forEach(::reject)
        reject(JSONObject().put("do", "x".repeat(1201)).toString())
    }

    @Test fun minimalPlanMustBeCompleteAndCannotExecuteStreamPrefix() {
        reject("""{"do":"Explain it"""")
        reject("""{"do":"Explain it"} {"do":"Another plan"}""")
        reject("""{"do":"Explain it"} false""")
        reject("""[{"do":"Explain it"}]""")
        reject("""{"steps":[{"do":"Valid first","worker":"inference"},{"do":"unfinished"""")
    }

    @Test fun fullDagMissingWorkerRemainsInferenceButExplicitBadWorkerNeverDowngrades() {
        val task = fullTask().apply { remove("worker"); remove("evidence") }
        assertEquals("inference", ObjectivePlanValidator.parse(plan(task), "obj", "caller").single().workerKind)
        listOf<Any>("unrestricted", "", 1, JSONObject.NULL).forEach { value ->
            reject(plan(fullTask().put("worker", value)))
        }
    }

    @Test fun fullToolEvidenceCannotUseArtifactExistenceAsProof() {
        reject(plan(fullTask().put("evidence", JSONArray().put(JSONObject().put("kind", "artifact_nonempty")))))
        reject(plan(fullTask().put("evidence", JSONObject().put("kind", "exit_code").put("expected", "0"))))
        reject(plan(fullTask().put("evidence", JSONObject.NULL)))
        reject(plan(fullTask().put("evidence", JSONArray().put("invalid"))))
    }

    @Test fun fullEvidenceRejectsMissingDefaultsUnknownChecksAndPartialSalvage() {
        listOf(
            """{"kind":"exit_code"}""", """{"kind":"exit_code","expected":"success"}""",
            """{"kind":"output_contains","expected":""}""", """{"kind":"output_not_contains"}""",
            """{"kind":"file_exists"}""", """{"kind":"file_contains","target":"note.txt"}""",
            """{"kind":"checksum_sha256","target":"note.txt","expected":"abc"}""",
            """{"kind":"invented","expected":"0"}""",
        ).forEach { bad ->
            val checks = JSONArray().put(JSONObject().put("kind", "exit_code").put("expected", "0")).put(JSONObject(bad))
            reject(plan(fullTask().put("evidence", checks)))
        }
    }

    @Test fun fullEvidenceIsNotTruncatedAtSpecOrFieldBounds() {
        val checks = JSONArray()
        repeat(EvidenceSpecParser.MAX_SPECS + 1) { checks.put(JSONObject().put("kind", "exit_code").put("expected", "0")) }
        reject(plan(fullTask().put("evidence", checks)))
        reject(plan(fullTask().put("evidence", JSONArray().put(JSONObject().put("kind", "output_contains").put("expected", "x".repeat(513))))))
    }

    @Test fun validRealEvidenceAndAllValidatedTasksRemainIntact() {
        val task = ObjectivePlanValidator.parse(plan(fullTask()), "obj", "caller").single()
        assertEquals(EvidenceKind.EXIT_CODE, task.evidence.single().kind)
        assertEquals("0", task.evidence.single().expected)
        val validFirst = fullTask().put("worker", "inference").put("evidence", JSONArray())
        val badSecond = fullTask().put("id", "b").put("evidence", JSONArray().put(JSONObject().put("kind", "exit_code")))
        reject(JSONObject().put("tasks", JSONArray().put(validFirst).put(badSecond)).toString())
    }

    @Test fun normalizedEvidenceKindIsNotDroppedAndObservationTextIsNotTrimmed() {
        val check = JSONObject().put("kind", " output_contains ").put("expected", " expected text ")
        val task = ObjectivePlanValidator.parse(plan(fullTask().put("evidence", JSONArray().put(check))), "obj", "caller").single()
        assertEquals(1, task.evidence.size)
        assertEquals(EvidenceKind.OUTPUT_CONTAINS, task.evidence.single().kind)
        assertEquals(" expected text ", task.evidence.single().expected)
    }

    @Test fun filesystemCannotPlanCommandExitEvidence() {
        reject("""{"steps":[{"do":"Write a file","worker":"filesystem","check":{"kind":"exit_code","expected":"0"}}]}""")
    }

    @Test fun compactProfileCannotBeBypassedByReturningTooManyFullDagTasks() {
        val profile = CompactPlanningPolicy.profile("Explain gravity.", false, emptySet(), 4096)
        val tasks = ObjectivePlanValidator.parse("""{"steps":[{"do":"First","worker":"inference"},{"do":"Second","worker":"inference"}]}""", "obj", "caller")
        try { CompactPlanningPolicy.validateWorkers(tasks, profile); fail("single-step boundary") }
        catch (_: ObjectivePlanValidationException) { }
    }

    private fun fullTask() = JSONObject().put("id", "a").put("title", "Run approved test")
        .put("instructions", "Run the authorized command").put("verification", "Observe its exit code")
        .put("worker", "terminal").put("dependsOn", JSONArray())
        .put("evidence", JSONArray().put(JSONObject().put("kind", "exit_code").put("expected", "0")))
    private fun plan(task: JSONObject) = JSONObject().put("tasks", JSONArray().put(task)).toString()
    private fun reject(raw: String) {
        try { ObjectivePlanValidator.parseResponse(raw, "obj", "caller"); fail("Expected complete-plan rejection") }
        catch (_: ObjectivePlanValidationException) { }
    }
}

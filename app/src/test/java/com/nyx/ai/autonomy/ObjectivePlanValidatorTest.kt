package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectivePlanValidatorTest {
    @Test
    fun validatesBranchingDagAndReturnsStableTopologicalIds() {
        val json = """
            {
              "tasks": [
                {"key":"finish","title":"Finish","instruction":"Join results","dependsOn":["left","right"],"verify":"Combined output exists"},
                {"key":"right","title":"Right branch","instruction":"Do right","dependsOn":["start"],"verify":"Right result exists"},
                {"key":"start","title":"Start","instruction":"Prepare input","dependsOn":[],"verify":"Input is ready"},
                {"key":"left","title":"Left branch","instruction":"Do left","dependsOn":["start"],"verify":"Left result exists"}
              ]
            }
        """.trimIndent()

        val tasks = ObjectivePlanValidator.parse(json, "objective-42", "fixed-agent")
        val byKey = tasks.associateBy { it.key }
        assertEquals(listOf("start", "right", "left", "finish"), tasks.map { it.key })
        assertEquals("fixed-agent", tasks.first().agentId)
        assertEquals(listOf(byKey.getValue("start").id), byKey.getValue("right").dependsOn)
        assertEquals(
            listOf(byKey.getValue("left").id, byKey.getValue("right").id),
            byKey.getValue("finish").dependsOn
        )
        assertTrue(tasks.all { it.id.isNotBlank() })

        val reparsed = ObjectivePlanValidator.parse(json, "objective-42", "different-caller-agent")
        assertEquals(tasks.map { it.id }, reparsed.map { it.id })
        assertEquals("different-caller-agent", reparsed.first().agentId)
        assertNotEquals(
            byKey.getValue("start").id,
            ObjectivePlanValidator.parse(json, "another-objective", "fixed-agent").associateBy { it.key }.getValue("start").id
        )
    }

    @Test
    fun rejectsMissingSelfAndDuplicateDependencies() {
        expectInvalid(plan(task("a", deps = listOf("absent"))))
        expectInvalid(plan(task("a", deps = listOf("a"))))
        expectInvalid(plan(task("a"), task("b", deps = listOf("a", "a"))))
    }

    @Test
    fun rejectsDuplicateTaskKeysAndDependencyCycles() {
        expectInvalid(plan(task("same"), task("same")))
        expectInvalid(plan(task("a", deps = listOf("b")), task("b", deps = listOf("a"))))
    }

    @Test
    fun rejectsOversizedJsonAndMalformedJson() {
        val oversized = "{\"tasks\":[${task("a")}],\"ignored\":\"${"x".repeat(ObjectivePlanValidator.MAX_JSON_BYTES)}\"}"
        expectInvalid(oversized)
        expectInvalid("{\"tasks\":[${task("a")}],}")
    }

    @Test
    fun ignoresHostileUnknownToolAndActionPropertiesAndUsesCallerAgent() {
        val json = """
            {
              "agentId":"model-agent",
              "model":"model-selected-model",
              "tool":"delete_everything",
              "action":"execute_arbitrary_command",
              "tools":["shell","filesystem"],
              "tasks":[{
                "key":"safe",
                "title":"Safe task",
                "instruction":"Read-only work",
                "dependsOn":[],
                "verify":"Review result",
                "agentId":"attacker-agent",
                "tool":"shell",
                "action":{"command":"rm -rf /"},
                "tools":["unrestricted"]
              }]
            }
        """.trimIndent()

        val task = ObjectivePlanValidator.parse(json, "objective-safe", "caller-authorized-agent").single()
        assertEquals("caller-authorized-agent", task.agentId)
        assertEquals("safe", task.key)
        assertTrue(task.dependsOn.isEmpty())
    }

    @Test
    fun acceptsCoordinatorFieldAliasesButDerivesTheTaskIdLocally() {
        val json = """
            {"tasks":[{
              "id":"model-chosen-key",
              "title":"Alias task",
              "instructions":"Use existing coordinator wording",
              "dependsOn":[],
              "verification":"Result is present"
            }]}
        """.trimIndent()

        val task = ObjectivePlanValidator.parse(json, "objective-alias", "authorized-agent").single()
        assertEquals("model-chosen-key", task.key)
        assertEquals("Use existing coordinator wording", task.instructions)
        assertEquals("Result is present", task.verification)
        assertNotEquals("model-chosen-key", task.id)
        assertEquals("authorized-agent", task.agentId)
    }

    @Test
    fun rejectsBlankAndOverlongAllowedFields() {
        expectInvalid(plan(task("a", title = "  ")))
        expectInvalid(plan(task("a", instruction = "i".repeat(ObjectivePlanValidator.MAX_INSTRUCTION_LENGTH + 1))))
    }

    private fun plan(vararg tasks: String): String = "{\"tasks\":[${tasks.joinToString(",")}] }"

    private fun task(
        key: String,
        deps: List<String> = emptyList(),
        title: String = "Title $key",
        instruction: String = "Do task $key"
    ): String {
        val dependencyJson = deps.joinToString(",") { "\"$it\"" }
        return "{\"key\":\"$key\",\"title\":\"$title\",\"instruction\":\"$instruction\",\"dependsOn\":[$dependencyJson],\"verify\":\"Verified $key\"}"
    }

    private fun expectInvalid(json: String) {
        try {
            ObjectivePlanValidator.parse(json, "objective-test", "authorized-agent")
            throw AssertionError("Expected invalid objective plan to be rejected")
        } catch (_: ObjectivePlanValidationException) {
            // Expected validation rejection.
        }
    }
}

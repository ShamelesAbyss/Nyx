package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalPlanningTest {
    private val profile = PlanningProfile(
        compact = true,
        maxOutputTokens = 384,
        allowedWorkers = setOf("inference", "terminal", "filesystem"),
        maxContextTokens = 2_048,
        singleStep = false,
        exitCodeWorkers = setOf("terminal"),
        maxSteps = 2,
    )

    @Test
    fun `direct plan is one deterministic inference task with output evidence`() {
        val task = IncrementalPlanning.directPlan("Explain photosynthesis in one paragraph", "agent-1")
        assertEquals("inference", task.workerKind)
        assertEquals("agent-1", task.agentId)
        assertTrue(task.dependsOn.isEmpty())
        assertEquals(1, task.evidence.size)
        assertEquals(EvidenceKind.ARTIFACT_NONEMPTY, task.evidence.single().kind)
        assertTrue(task.instructions.contains("photosynthesis"))
    }

    @Test
    fun `guided prompt states the single-line contract and completion`() {
        val prompt = IncrementalPlanning.prompt("Summarize findings", listOf("Read notes"), setOf("inference"), guided = true)
        assertTrue(prompt.contains("NEXT <worker>"))
        assertTrue(prompt.contains("DONE"))
        assertTrue(prompt.contains("Read notes"))
    }

    @Test
    fun `json next step becomes one validated dependent task`() {
        val raw = """{"next":{"do":"List the workspace files","worker":"terminal","check":{"kind":"exit_code","expected":"0"}}}"""
        val task = IncrementalPlanning.parseNext(raw, guided = false, profile = profile, objectiveId = "objective-1", agentId = "agent-1", dependsOn = listOf("previous-task"))
        requireNotNull(task)
        assertEquals("terminal", task.workerKind)
        assertEquals(listOf("previous-task"), task.dependsOn)
        assertEquals(EvidenceKind.EXIT_CODE, task.evidence.single().kind)
        assertTrue(task.id.isNotBlank())
    }

    @Test
    fun `json done declares completion without inventing work`() {
        assertNull(IncrementalPlanning.parseNext("""{"done":true}""", false, profile, "objective-1", "agent-1", emptyList()))
    }

    @Test(expected = ObjectivePlanValidationException::class)
    fun `json decision cannot select an unavailable worker`() {
        IncrementalPlanning.parseNext(
            """{"next":{"do":"Deploy the service","worker":"development"}}""",
            guided = false, profile = profile, objectiveId = "objective-1", agentId = "agent-1", dependsOn = emptyList(),
        )
    }

    @Test(expected = ObjectivePlanValidationException::class)
    fun `tool step without local evidence is rejected`() {
        IncrementalPlanning.parseNext(
            """{"next":{"do":"List files","worker":"terminal"}}""",
            guided = false, profile = profile, objectiveId = "objective-1", agentId = "agent-1", dependsOn = emptyList(),
        )
    }

    @Test
    fun `guided next line becomes an inference task`() {
        val task = IncrementalPlanning.parseNext(
            "NEXT inference: Summarize the verified results for the user",
            guided = true, profile = profile, objectiveId = "objective-1", agentId = "agent-1", dependsOn = listOf("t1"),
        )
        requireNotNull(task)
        assertEquals("inference", task.workerKind)
        assertEquals(listOf("t1"), task.dependsOn)
    }

    @Test
    fun `guided done is accepted case-insensitively`() {
        assertNull(IncrementalPlanning.parseNext("done", true, profile, "objective-1", "agent-1", emptyList()))
    }

    @Test(expected = ObjectivePlanValidationException::class)
    fun `guided prose is rejected rather than interpreted`() {
        IncrementalPlanning.parseNext(
            "I think the next step would be to inspect the files.",
            guided = true, profile = profile, objectiveId = "objective-1", agentId = "agent-1", dependsOn = emptyList(),
        )
    }
}

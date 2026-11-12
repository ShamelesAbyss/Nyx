package com.nyx.ai.context

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMemoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun evidence(recordId: String = "record-1"): MemoryEvidenceRef =
        MemoryEvidenceRef(recordId = recordId, path = "src/Main.kt", sha256 = "a".repeat(64))

    @Test
    fun verifiedOutcomeRequiresEvidenceAndRanksBeforeUnverified() {
        val memory = ProjectMemory(temporaryFolder.newFolder("verified"))
        val unverified = memory.recordOutcome(
            title = "Kotlin compiler flag",
            summary = "Kotlin compiler flag may fix the build",
            success = true,
            objectiveId = "obj",
        )
        val verified = memory.recordOutcome(
            title = "Kotlin compiler flag evidence",
            summary = "Kotlin compiler flag fixed the build",
            success = true,
            evidenceRefs = listOf(evidence()),
            objectiveId = "obj",
        )

        assertEquals(MemoryType.TASK_OUTCOME, unverified.type)
        assertEquals(MemoryType.VERIFIED_RESULT, verified.type)
        assertTrue(verified.hasUsableEvidence)

        val block = memory.buildPromptBlock("kotlin compiler build", 4_000)
        assertTrue(block.startsWith("PROJECT MEMORY"))
        assertTrue(block.contains("EVIDENCE IS REQUIRED"))
        assertTrue(block.contains("NOT proof"))
        assertTrue(block.contains("sha256=" + "a".repeat(64)))
        val verifiedAt = block.indexOf("[VERIFIED_RESULT]")
        val outcomeAt = block.indexOf("[TASK_OUTCOME]")
        assertTrue(verifiedAt >= 0)
        assertTrue(outcomeAt > verifiedAt)
        assertTrue(block.length <= 4_000)
    }

    @Test
    fun failedClaimsAreExcludedFromPromptsButRetainedForHistory() {
        val memory = ProjectMemory(temporaryFolder.newFolder("failures"))
        val failure = memory.recordOutcome(
            title = "Gradle daemon crash",
            summary = "Gradle daemon crash interrupted the build",
            success = false,
            objectiveId = "obj",
        )

        assertEquals(MemoryType.FAILURE, failure.type)
        assertFalse(memory.buildPromptBlock("gradle daemon build", 4_000).contains("Gradle daemon crash"))
        assertEquals(1, memory.all().count { it.type == MemoryType.FAILURE })
    }

    @Test
    fun nearIdenticalEntriesAreDeduplicatedAndEvidenceIsMerged() {
        val memory = ProjectMemory(temporaryFolder.newFolder("dedupe"))
        val first = memory.record(
            type = MemoryType.DECISION,
            title = "Use Room for local storage",
            summary = "Use Room for durable local storage.",
            evidenceRefs = listOf(evidence("record-1")),
            objectiveId = "obj",
        )
        val second = memory.record(
            type = MemoryType.DECISION,
            title = "Use Room for local storage",
            summary = "Use Room for durable local storage.",
            evidenceRefs = listOf(evidence("record-2")),
            objectiveId = "obj",
        )

        assertEquals(first.id, second.id)
        val stored = memory.all().single()
        assertEquals(2, stored.evidenceRefs.size)
        assertEquals(setOf("record-1", "record-2"), stored.evidenceRefs.mapNotNull { it.recordId }.toSet())
    }

    @Test
    fun entryCountAndTextFieldsAreBounded() {
        val memory = ProjectMemory(temporaryFolder.newFolder("caps"))
        val longTitle = "title ".repeat(100)
        val longSummary = "summary ".repeat(500)
        val longTag = "tag".repeat(50)
        repeat(205) { index ->
            memory.record(
                type = MemoryType.TASK_OUTCOME,
                title = "entry${100 + index} $longTitle",
                summary = "$longSummary $index",
                tags = listOf(longTag, "tag$index"),
                now = 1_000L + index,
            )
        }

        val all = memory.all()
        assertEquals(200, all.size)
        assertTrue(all.all { it.title.length <= 120 })
        assertTrue(all.all { it.summary.length <= 1_200 })
        assertTrue(all.all { it.tags.size <= 12 && it.tags.all { tag -> tag.length <= 32 } })
    }

    @Test
    fun documentIsWorkspaceKeyedAndReloadsAtomically() {
        val root = temporaryFolder.newFolder("reload")
        val memory = ProjectMemory(root)
        val saved = memory.record(
            type = MemoryType.DECISION,
            title = "Durable decision",
            summary = "Keep the module boundary.",
            objectiveId = "obj",
            tags = listOf("architecture"),
        )

        assertTrue(memory.documentFile.name.matches(Regex("[0-9a-f]{64}\\.json")))
        assertTrue(memory.documentFile.isFile)
        val document = JSONObject(memory.documentFile.readText())
        assertEquals(2, document.getInt("version"))
        assertEquals(memory.workspaceKey, document.getString("workspaceKey"))

        val reopened = ProjectMemory(root)
        val restored = reopened.all().single()
        assertEquals(saved.id, restored.id)
        assertEquals(saved.summary, restored.summary)
        assertEquals(listOf("architecture"), restored.tags)
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun corruptDocumentIsQuarantinedAndMemoryCanContinue() {
        val root = temporaryFolder.newFolder("corrupt")
        val memory = ProjectMemory(root)
        memory.record(MemoryType.DECISION, "Before corruption", "Durable before damage")
        memory.documentFile.writeText("{ definitely not json")

        val reopened = ProjectMemory(root)
        assertTrue(reopened.all().isEmpty())
        val fresh = reopened.record(MemoryType.DECISION, "After corruption", "Durable after damage")
        assertEquals(fresh.id, ProjectMemory(root).all().single().id)
        assertTrue(root.walkTopDown().any { it.name.contains("malformed") })
    }

    @Test
    fun lessonsVerifiedResultsAndBuildInstructionsAreFiltered() {
        val memory = ProjectMemory(temporaryFolder.newFolder("views"))
        memory.record(
            type = MemoryType.LESSON,
            title = "Gradle daemon memory",
            summary = "Increase Gradle daemon heap for Kotlin builds",
        )
        memory.record(MemoryType.LESSON, "Gardening", "Water the tomatoes")
        memory.record(
            type = MemoryType.VERIFIED_RESULT,
            title = "Gradle build passed",
            summary = "Gradle build passed after the heap increase",
            evidenceRefs = listOf(evidence("build-log")),
        )
        memory.record(
            type = MemoryType.BUILD_INSTRUCTION,
            title = "Run Gradle offline",
            summary = "Use Gradle offline mode in CI",
        )

        val lessons = memory.lessonsFor("gradle kotlin build")
        assertEquals(1, lessons.size)
        assertEquals("Gradle daemon memory", lessons.single().title)
        assertEquals(1, memory.verifiedResults().size)
        assertEquals(1, memory.buildInstructions().size)
        assertEquals(4, memory.all().size)
    }

    @Test
    fun promptIsBoundedAndOmitsIrrelevantHistory() {
        val memory = ProjectMemory(temporaryFolder.newFolder("bounded"))
        memory.record(
            type = MemoryType.VERIFIED_RESULT,
            title = "Gradle build cache verified",
            summary = "Gradle build cache cut compile time",
            evidenceRefs = listOf(evidence("cache-log")),
        )
        memory.record(MemoryType.DECISION, "Gardening", "Tomatoes need soil")
        memory.record(
            type = MemoryType.BUILD_INSTRUCTION,
            title = "Gradle offline mode",
            summary = "Run Gradle with offline dependencies",
        )

        for (budget in listOf(0, 1, 40, 120, 400, 4_000)) {
            val block = memory.buildPromptBlock("gradle build", budget)
            assertTrue("budget=$budget length=${block.length}", block.length <= budget)
        }
        val full = memory.buildPromptBlock("gradle build", 4_000)
        assertTrue(full.contains("Gradle build cache verified"))
        assertTrue(full.contains("Gradle offline mode"))
        assertFalse(full.contains("Gardening"))
        assertTrue(full.indexOf("[VERIFIED_RESULT]") < full.indexOf("[BUILD_INSTRUCTION]"))
    }

    @Test
    fun directVerifiedRecordWithoutUsableEvidenceIsDowngraded() {
        val memory = ProjectMemory(temporaryFolder.newFolder("downgrade"))
        val missing = memory.record(MemoryType.VERIFIED_RESULT, "Unbacked claim", "No evidence references")
        val malformed = memory.record(
            type = MemoryType.VERIFIED_RESULT,
            title = "Malformed hash claim",
            summary = "The supplied hash is not a SHA-256",
            evidenceRefs = listOf(MemoryEvidenceRef(recordId = "record", sha256 = "not-a-hash")),
        )

        assertEquals(MemoryType.TASK_OUTCOME, missing.type)
        assertEquals(MemoryType.TASK_OUTCOME, malformed.type)
        assertTrue(memory.verifiedResults().isEmpty())
    }
}

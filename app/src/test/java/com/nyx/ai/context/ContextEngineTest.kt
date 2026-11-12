package com.nyx.ai.context

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeReadRoundTripUsesUtf8SummaryAndReadLimit() {
        val storeRoot = temporaryFolder.newFolder("round-trip")
        val store = ContextStore(storeRoot)
        val text = "Résumé and 東京\nsecond line"
        val record = store.write("objective-1", "task-1", "observation", "A note", text)

        assertEquals(text, store.read(record))
        assertEquals("Résumé and 東京 second line", record.summary)
        assertEquals("Résumé", store.read(record, 6))
        assertTrue(File(storeRoot, record.filePath).isFile)
        assertFalse(File(record.filePath).isAbsolute)
    }

    @Test
    fun rejectsTraversalInObjectiveAndKindSegments() {
        val store = ContextStore(temporaryFolder.newFolder("traversal"))
        expectIllegalArgument { store.write("../outside", null, "goal", "Title", "text") }
        expectIllegalArgument { store.write("objective", null, "../goal", "Title", "text") }
        assertFalse(File(temporaryFolder.root, "outside").exists())
    }

    @Test
    fun writesAreAtomicAndRepeatedWritesKeepCompleteIndependentRecords() {
        val root = temporaryFolder.newFolder("atomic")
        val store = ContextStore(root)
        val first = store.write("objective", "task", "artifact", "same title", "old-value")
        val second = store.write("objective", "task", "artifact", "same title", "new-value".repeat(100))

        assertNotEquals(first.filePath, second.filePath)
        assertEquals("old-value", store.read(first))
        assertEquals("new-value".repeat(100), store.read(second))
        assertEquals(2, store.list("objective").size)
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun listReconstructsRecordsFromMetadata() {
        val store = ContextStore(temporaryFolder.newFolder("listing"))
        val expected = store.write("objective", "task-7", "summary", "Daily brief", "Persisted text")

        val actual = store.list("objective").single()
        assertEquals(expected.id, actual.id)
        assertEquals(expected.objectiveId, actual.objectiveId)
        assertEquals(expected.taskId, actual.taskId)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.title, actual.title)
        assertEquals(expected.filePath, actual.filePath)
        assertEquals(expected.summary, actual.summary)
        assertEquals(expected.createdAt, actual.createdAt)
    }

    @Test
    fun deleteObjectiveRemovesContentsAndBytes() {
        val store = ContextStore(temporaryFolder.newFolder("delete"))
        store.write("objective", null, "goal", "Goal", "12345")
        store.write("objective", "task", "task", "Task", "67890")
        store.write("another-objective", null, "goal", "Other", "keep")
        assertEquals(10L, store.totalBytes("objective"))

        store.deleteObjective("objective")
        assertTrue(store.list("objective").isEmpty())
        assertEquals(0L, store.totalBytes("objective"))
        assertEquals(1, store.list("another-objective").size)
    }

    @Test
    fun restartedStoreInstanceReadsPersistedRecords() {
        val root = temporaryFolder.newFolder("restart")
        val firstStore = ContextStore(root)
        val saved = firstStore.write("objective", "task-2", "observation", "Restart note", "durable text")

        val restarted = ContextStore(root)
        val restored = restarted.list("objective").single()
        assertEquals(saved.id, restored.id)
        assertEquals("durable text", restarted.read(restored))
        assertEquals(firstStore.totalBytes("objective"), restarted.totalBytes("objective"))
    }

    @Test
    fun dependencyResultsAreIncludedNewestFirst() {
        val store = ContextStore(temporaryFolder.newFolder("dependencies"))
        val oldest = store.write("objective", "dep-a", "task", "Old dependency", "old output")
        Thread.sleep(3)
        val newest = store.write("objective", "dep-b", "task", "New dependency", "new output")
        store.write("objective", "unrelated-task", "task", "Unrelated", "do not include")

        val result = ContextEngine.build(
            ContextRequest("objective", "Goal", "Current", "Use outputs", null,
                dependencyTaskIds = listOf("dep-a", "dep-b")),
            store,
        )
        assertTrue(result.includedRecordIds.containsAll(listOf(oldest.id, newest.id)))
        assertTrue(result.includedRecordIds.indexOf(newest.id) < result.includedRecordIds.indexOf(oldest.id))
        assertTrue(result.text.indexOf("New dependency") < result.text.indexOf("Old dependency"))
        assertFalse(result.text.contains("do not include"))
    }

    @Test
    fun relevantHistoryRanksKeywordOverlapAndExcludesIrrelevantRecords() {
        val store = ContextStore(temporaryFolder.newFolder("relevance"))
        val weak = store.write("objective", null, "observation", "Kotlin note", "A general Kotlin update")
        val strong = store.write("objective", null, "artifact", "Kotlin compiler diagnostics", "Compiler diagnostics for Kotlin build")
        store.write("objective", null, "observation", "Gardening", "Tomatoes and soil")

        val result = ContextEngine.build(
            ContextRequest("objective", "Keep building", "Kotlin compiler", "Review compiler diagnostics", null,
                maxHistoryRecords = 2),
            store,
        )
        assertEquals(strong.id, result.includedRecordIds.first())
        assertTrue(result.includedRecordIds.contains(weak.id))
        assertFalse(result.text.contains("Tomatoes and soil"))
    }

    @Test
    fun includesFailureHistoryAndRepeatsGoalReminder() {
        val store = ContextStore(temporaryFolder.newFolder("failures"))
        val failure = store.write("objective", "failed-task", "failure", "Failed verification", "Expected file was missing")
        val result = ContextEngine.build(
            ContextRequest("objective", "Deliver a verified report", "Write report", "Create and save it", "File exists"),
            store,
        )

        assertTrue(result.text.contains("FAILURE HISTORY"))
        assertTrue(result.text.contains("Expected file was missing"))
        assertTrue(result.text.contains("GOAL REMINDER: Deliver a verified report"))
        assertTrue(result.includedRecordIds.contains(failure.id))
        assertTrue(result.text.contains("VERIFICATION\nFile exists"))
    }

    @Test
    fun strictBudgetAndTruncationAreReported() {
        val store = ContextStore(temporaryFolder.newFolder("budget"))
        store.write("objective", null, "failure", "Long failure", "x".repeat(2_000))
        val result = ContextEngine.build(
            ContextRequest("objective", "A very important goal", "Task", "Instructions ".repeat(200), null,
                budgetChars = 180),
            store,
        )

        assertTrue(result.text.length <= 180)
        assertEquals(result.text.length, result.usedChars)
        assertTrue(result.truncated)
        assertTrue(result.text.startsWith("GOAL"))
    }

    @Test
    fun perRecordCapSetsTruncationFlagAndDefaultHistoryLimitApplies() {
        val store = ContextStore(temporaryFolder.newFolder("record-cap"))
        val large = store.write("objective", null, "failure", "Long failure", "x".repeat(1_000))
        val result = ContextEngine.build(
            ContextRequest("objective", "Goal", "Unrelated task", "No matching history", null),
            store,
        )

        assertTrue(result.text.contains("FAILURE HISTORY"))
        assertTrue(result.truncated)
        assertTrue(result.includedRecordIds.contains(large.id))
        assertTrue(result.usedChars <= 12_000)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected rejection.
        }
    }
}

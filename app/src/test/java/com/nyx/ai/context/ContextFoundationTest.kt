package com.nyx.ai.context

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextFoundationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun sparseLargeFileReadIsBoundedAndUtf8IsPreserved() {
        val root = temporaryFolder.newFolder("bounded")
        val store = ContextStore(root)
        val record = store.write("objective", null, "observation", "large", "Résumé 東京")
        RandomAccessFile(File(root, record.filePath), "rw").use { it.setLength(128L * 1_024 * 1_024) }
        assertEquals("Résumé", store.read(record, 6))
        assertEquals("", store.read(record, 0))
        assertEquals("", store.read(record, -1))
    }

    @Test fun oversizedMalformedMetadataDoesNotBlockOtherRecords() {
        val root = temporaryFolder.newFolder("metadata")
        val store = ContextStore(root)
        val good = store.write("objective", null, "observation", "good", "valid")
        val bad = store.write("objective", null, "observation", "bad", "invalid metadata")
        File(root, bad.filePath + ".json").writeText("x".repeat(70_000))
        assertEquals(listOf(good.id), store.list("objective").map { it.id })
    }

    @Test fun legacySidecarsRemainReadable() {
        val root = temporaryFolder.newFolder("legacy")
        val store = ContextStore(root)
        val record = store.write("objective", null, "observation", "legacy", "unchanged")
        val metadata = File(root, record.filePath + ".json")
        val json = JSONObject(metadata.readText())
        json.remove("evidence"); json.remove("sourceReferences"); json.remove("contentSha256")
        metadata.writeText(json.toString())
        val loaded = ContextStore(root).list("objective").single()
        assertNull(loaded.contentSha256)
        assertTrue(loaded.evidence.isEmpty())
        assertEquals("unchanged", store.read(loaded))
        assertTrue(store.isUsable(loaded))
    }

    @Test fun compactionRetainsDurableOriginalEvidenceAfterRestart() {
        val root = temporaryFolder.newFolder("compaction")
        val store = ContextStore(root)
        val original = store.write("objective", "task", "observation", "source diagnostics", "full original evidence\n".repeat(500))
        val summary = store.compact("objective", "task", "source diagnostics summary", "compact diagnostic summary", listOf(original.id), 12)
        val reopened = ContextStore(root)
        val restored = reopened.list("objective").first { it.id == summary.id }
        assertEquals("compact diag", reopened.read(restored))
        assertEquals(original.id, restored.evidence.single().recordId)
        assertEquals("full original evidence", reopened.readEvidence(restored.evidence.single(), 22))
        assertTrue(File(root, original.filePath).isFile)
        assertTrue(reopened.isUsable(restored))
        assertEquals(2, reopened.list("objective").size)
    }

    @Test fun nestedCompactionRetainsTransitiveOriginalEvidence() {
        val root = temporaryFolder.newFolder("nested")
        val store = ContextStore(root)
        val original = store.write("objective", null, "observation", "original", "full original")
        val first = store.compact("objective", null, "first summary", "first finding", listOf(original.id))
        val nested = store.compact("objective", null, "nested summary", "nested finding", listOf(first.id))
        val restored = ContextStore(root).list("objective").first { it.id == nested.id }
        assertEquals(setOf(original.id, first.id), restored.evidence.map { it.recordId }.toSet())
        assertEquals("full original", store.readEvidence(restored.evidence.first { it.recordId == original.id }))
        assertTrue(store.isUsable(restored))
    }

    @Test fun deletingObjectiveDeletesLinksButNeverTheirExternalTargets() {
        val root = temporaryFolder.newFolder("delete-links")
        val outside = temporaryFolder.newFolder("outside-links")
        val external = File(outside, "preserved.txt").apply { writeText("preserved") }
        val store = ContextStore(root)
        store.write("objective", null, "observation", "record", "inside")
        val directory = File(root, "objectives/objective")
        Files.createSymbolicLink(File(directory, "linked-directory").toPath(), outside.toPath())
        Files.createSymbolicLink(File(directory, "linked-file.txt").toPath(), external.toPath())
        assertEquals(6L, store.totalBytes("objective"))
        assertEquals(1, store.list("objective").size)
        store.deleteObjective("objective")
        assertFalse(directory.exists())
        assertEquals("preserved", external.readText())
    }

    @Test fun missingOrChangedOriginalInvalidatesSummaryButNotOtherRecords() {
        val root = temporaryFolder.newFolder("tamper")
        val store = ContextStore(root)
        val original = store.write("objective", null, "observation", "Kotlin", "evidence")
        val summary = store.compact("objective", null, "Kotlin summary", "Kotlin finding", listOf(original.id))
        File(root, original.filePath).writeText("modified")
        assertFalse(store.isUsable(summary))
        assertEquals("", store.readEvidence(summary.evidence.single()))
        val result = ContextEngine.build(ContextRequest("objective", "Goal", "Kotlin", "finding", null), store)
        assertTrue(result.invalidatedRecordIds.containsAll(listOf(original.id, summary.id)))
        assertFalse(result.text.contains("Kotlin finding"))
    }

    @Test fun checkpointIsBoundedAndReloadedWithoutDeletingEvidence() {
        val root = temporaryFolder.newFolder("checkpoint")
        val store = ContextStore(root)
        val original = store.write("objective", "task", "task", "output", "original result")
        val checkpoint = store.checkpoint("objective", "task", "checkpoint ".repeat(100), listOf(original.id), 80)
        val restarted = ContextStore(root)
        assertEquals(checkpoint.id, restarted.latestCheckpoint("objective", "task")?.id)
        assertEquals(80, restarted.read(checkpoint).length)
        val result = ContextEngine.build(ContextRequest("objective", "Goal", "Task", "Continue", null, budgetChars = 500), restarted)
        assertTrue(result.text.contains("CHECKPOINT"))
        assertTrue(checkpoint.id in result.includedRecordIds)
        assertTrue(result.usedChars <= 500)
        assertTrue(File(root, original.filePath).isFile)
    }

    @Test fun crossObjectiveEvidenceAndInvalidBudgetsAreRejected() {
        val store = ContextStore(temporaryFolder.newFolder("cross-objective"))
        val other = store.write("other", null, "observation", "Other", "private")
        rejects { store.compact("objective", null, "summary", "text", listOf(other.id)) }
        rejects { store.checkpoint("objective", null, "text", budgetChars = 0) }
    }

    @Test fun deterministicIndexContainsFullHashMtimeAndScopedReference() {
        val root = temporaryFolder.newFolder("project")
        File(root, "z.kt").writeText("z")
        File(root, "a.kt").writeText("abc")
        File(root, "build").mkdir(); File(root, "build/generated.kt").writeText("exclude")
        val index = ProjectIndex(root)
        val first = index.build()
        val second = index.build()
        assertEquals(first, second)
        assertEquals(listOf("a.kt", "z.kt"), first.sources.map { it.relativePath })
        val source = first.sources.first()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", source.sha256)
        assertEquals(File(root, "a.kt").lastModified(), source.modifiedAt)
        assertEquals(index.scopeId, source.scopeId)
        assertEquals(3L, source.sizeBytes)
    }

    @Test fun contentChangeWithSameMtimeAndSizeInvalidatesObservationsAndCompactedSummary() {
        val root = temporaryFolder.newFolder("changing-project")
        val file = File(root, "source.kt").apply { writeText("old source") }
        val index = ProjectIndex(root)
        val before = index.build()
        val source = before.sources.single()
        val store = ContextStore(temporaryFolder.newFolder("observations"))
        val observation = store.writeObservation("objective", null, "source", "old source finding", source)
        val summary = store.compact("objective", null, "source summary", "old source finding", listOf(observation.id))
        file.writeText("new source")
        assertTrue(file.setLastModified(source.modifiedAt))
        assertEquals(source.sizeBytes, file.length())
        assertFalse(index.isCurrent(source))
        val refreshed = index.refresh(before)
        assertEquals(listOf("source.kt"), refreshed.changedPaths)
        assertEquals(listOf(source), refreshed.invalidatedSources)
        val result = ContextEngine.build(ContextRequest("objective", "Goal", "source", "finding", null,
            sourceValidator = index::isCurrent), store)
        assertTrue(result.invalidatedRecordIds.containsAll(listOf(observation.id, summary.id)))
        assertFalse(result.text.contains("old source finding"))
        // Historical bytes remain deliberately available; they are not current project observations.
        assertEquals("old source finding", store.readEvidence(summary.evidence.single()))
    }

    @Test fun sourcedObservationsFailClosedWithoutCurrentValidator() {
        val root = temporaryFolder.newFolder("source-project")
        File(root, "a.kt").writeText("source")
        val index = ProjectIndex(root)
        val store = ContextStore(temporaryFolder.newFolder("source-store"))
        val record = store.writeObservation("objective", null, "source", "unique observation", index.build().sources.single())
        val result = ContextEngine.build(ContextRequest("objective", "Goal", "source", "unique", null), store)
        assertEquals(listOf(record.id), result.invalidatedRecordIds)
        assertFalse(result.text.contains("unique observation"))
    }

    @Test fun scopeRejectsTraversalSymlinksAndWrongIndex() {
        val root = temporaryFolder.newFolder("scope")
        File(root, "src").mkdir(); File(root, "src/a.kt").writeText("allowed")
        File(root, "outside.kt").writeText("not in scope")
        val outside = temporaryFolder.newFile("secret.txt").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "src/link.txt").toPath(), outside.toPath())
        val index = ProjectIndex(root, setOf("src"))
        val snapshot = index.build()
        assertEquals(listOf("src/a.kt"), snapshot.sources.map { it.relativePath })
        assertFalse(snapshot.complete)
        assertFalse(index.isCurrent(snapshot.sources.single().copy(relativePath = "../secret.txt")))
        rejects { ProjectIndex(root, setOf("../")) }
        rejects { ProjectIndex(root).refresh(snapshot) }
    }

    @Test fun fileAndByteBudgetsNeverProducePartialHashesAndDeletedFilesAreInvalidated() {
        val root = temporaryFolder.newFolder("index-limits")
        val first = File(root, "a.txt").apply { writeText("aaaa") }
        File(root, "b.txt").writeText("bbbb")
        val index = ProjectIndex(root)
        val snapshot = index.build()
        val limited = index.build(ProjectIndexLimits(maxFiles = 1, maxTotalBytes = 4))
        assertEquals(listOf("a.txt"), limited.sources.map { it.relativePath })
        assertFalse(limited.complete)
        assertEquals(listOf("b.txt"), limited.skippedPaths)
        val none = index.build(ProjectIndexLimits(maxFileBytes = 3))
        assertTrue(none.sources.isEmpty())
        first.delete()
        assertEquals(listOf("a.txt"), index.refresh(snapshot).removedPaths)
    }

    @Test fun retrievalHonorsExactBudgetIsDeterministicAndDoesNotReuseStaleSnapshot() {
        val root = temporaryFolder.newFolder("retrieval")
        File(root, "other.txt").writeText("other text")
        val file = File(root, "compiler.kt").apply { writeText("compiler output ".repeat(100)) }
        val index = ProjectIndex(root)
        val snapshot = index.build()
        val first = index.retrieve(snapshot, "compiler", budgetChars = 220, maxFiles = 1, maxFileChars = 500)
        assertEquals(first, index.retrieve(snapshot, "compiler", budgetChars = 220, maxFiles = 1, maxFileChars = 500))
        assertEquals("compiler.kt", first.sources.single().relativePath)
        assertTrue(first.usedChars <= 220)
        assertEquals(first.text.length, first.usedChars)
        assertTrue(first.truncated)
        file.writeText("changed")
        val changed = index.retrieve(snapshot, "compiler", budgetChars = 500)
        assertTrue("compiler.kt" in changed.invalidatedPaths)
        assertFalse(changed.text.contains("compiler output"))
        assertEquals("", index.retrieve(snapshot, "compiler", budgetChars = 0).text)
    }

    @Test fun excessiveDirectoryEnumerationAndDepthFailClosedDeterministically() {
        val root = temporaryFolder.newFolder("traversal-limits")
        repeat(12) { File(root, "$it.txt").writeText("bounded") }
        val index = ProjectIndex(root)
        val limits = ProjectIndexLimits(maxVisitedEntries = 4)
        val first = index.build(limits)
        assertEquals(first, index.build(limits))
        assertTrue(first.sources.isEmpty())
        assertFalse(first.complete)
        assertTrue("." in first.skippedPaths)
        val deep = temporaryFolder.newFolder("depth-limits")
        File(deep, "a/b").mkdirs(); File(deep, "a/b/source.kt").writeText("deep")
        val limited = ProjectIndex(deep).build(ProjectIndexLimits(maxDepth = 1))
        assertTrue(limited.sources.isEmpty())
        assertFalse(limited.complete)
    }

    @Test fun selectedPathCannotTraverseEvenAnInternalSymlinkAncestor() {
        val root = temporaryFolder.newFolder("ancestor-link")
        File(root, "actual").mkdir(); File(root, "actual/file.kt").writeText("inside")
        Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "actual").toPath())
        val index = ProjectIndex(root, setOf("alias/file.kt"))
        assertTrue(index.build().sources.isEmpty())
        assertFalse(index.build().complete)
    }

    @Test fun compactedSummaryIsSelectedInsteadOfItsRepresentedOriginals() {
        val store = ContextStore(temporaryFolder.newFolder("selection"))
        val original = store.write("objective", null, "observation", "compiler diagnostics", "compiler diagnostics original verbose evidence")
        val summary = store.compact("objective", null, "compiler summary", "compiler finding", listOf(original.id))
        val result = ContextEngine.build(ContextRequest("objective", "Goal", "compiler", "diagnostics", null, maxHistoryRecords = 1), store)
        assertEquals(listOf(summary.id), result.includedRecordIds)
        assertFalse(result.text.contains("original verbose evidence"))
        assertTrue(result.text.contains(original.id))
    }

    @Test fun projectContextIsRevalidatedAndAllBudgetsRemainStrict() {
        val root = temporaryFolder.newFolder("project-context")
        val file = File(root, "source.kt").apply { writeText("unique indexed evidence") }
        val index = ProjectIndex(root)
        val project = index.retrieve(index.build(), "source", budgetChars = 400)
        val store = ContextStore(temporaryFolder.newFolder("project-context-store"))
        val request = ContextRequest("objective", "Goal", "Task", "Use source", null,
            sourceValidator = index::isCurrent, projectContext = project)
        assertTrue(ContextEngine.build(request, store).text.contains("unique indexed evidence"))
        for (budget in 0..250) {
            val built = ContextEngine.build(request.copy(budgetChars = budget), store)
            assertTrue("budget=$budget", built.usedChars <= budget)
            assertEquals(built.text.length, built.usedChars)
        }
        file.writeText("changed source")
        assertFalse(ContextEngine.build(request, store).text.contains("unique indexed evidence"))
        assertFalse(ContextEngine.build(request.copy(sourceValidator = { throw IllegalStateException("probe unavailable") }), store)
            .text.contains("unique indexed evidence"))
        assertFalse(ContextEngine.build(request.copy(projectContext = project.copy(sources = emptyList())), store)
            .text.contains("unique indexed evidence"))
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}

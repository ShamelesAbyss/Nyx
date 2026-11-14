package com.nyx.ai.workbench

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkbenchProvenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun approval() = WorkbenchAuthorization(true, true, listOf("https://example.com/"), mapOf("report.md" to ArtifactFormat.MARKDOWN)).validate()
    @Test fun completedFilesCannotHideMissingResearch() {
        val store = WorkbenchStore(temporary.newFolder(), "objective")
        val file = store.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "A model claimed it researched this.".toByteArray())
        val witnesses = listOf(WorkbenchCompletionPolicy.WriteWitness("task", file.fileName, file.sha256))
        assertEquals(listOf("source:https://example.com/", "citation:https://example.com/"), WorkbenchCompletionPolicy.missing(approval(), store, setOf("task"), witnesses))
        store.recordSource(ResearchSource("https://example.com/", status = "FAILED", note = "Offline"))
        assertFalse(WorkbenchCompletionPolicy.missingSources(approval(), store).isEmpty())
    }
    @Test fun realRetainedSourceRequiredAndReopened() {
        val root = temporary.newFolder()
        val store = WorkbenchStore(root, "objective")
        store.recordSource(ResearchSource("https://example.com/", title = "Example", status = "FETCHED"), "actual source bytes".toByteArray())
        assertTrue(WorkbenchCompletionPolicy.missingSources(approval(), store).isEmpty())
        assertTrue(WorkbenchCompletionPolicy.missingSources(approval(), WorkbenchStore(root, "objective")).isEmpty())
    }
}

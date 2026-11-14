package com.nyx.ai.workbench

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkbenchSourceIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = "good".toByteArray()
    private fun source() = ResearchSource("https://example.com/source", fetchedAt = 1)

    @Test fun duplicateCorruptPayloadFailsWithoutRestoringOrChangingMetadata() {
        val root = temporary.newFolder("corrupt-source")
        val store = WorkbenchStore(root, "objective")
        val retained = store.recordSource(source(), payload)
        val file = File(root, "objective/sources/${retained.id}.txt")
        file.writeText("evil") // Same length: catches a size-only verification fix.
        val manifest = File(root, "objective/meta/manifest.json")
        val before = manifest.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.recordSource(source(), payload) }
        assertEquals("evil", file.readText())
        assertArrayEquals(before, manifest.readBytes())
        val reopened = WorkbenchStore(root, "objective")
        assertThrows(IllegalArgumentException::class.java) { reopened.recordSource(source(), payload) }
        // A metadata-only duplicate must not silently bless existing corrupt bytes either.
        assertThrows(IllegalArgumentException::class.java) { reopened.recordSource(retained) }
        assertThrows(IllegalArgumentException::class.java) { reopened.readSource(retained.id) }
    }

    @Test fun validDuplicateKeepsIdentityAndMissingMatchingPayloadPromotesAtomically() {
        val root = temporary.newFolder("duplicate-source")
        val store = WorkbenchStore(root, "objective")
        val metadataOnly = store.recordSource(source().copy(bytes = 4, sha256 = WorkbenchStore.sha256(payload)))
        assertNull(store.readSource(metadataOnly.id))
        val promoted = store.recordSource(source(), payload)
        assertEquals(metadataOnly.id, promoted.id)
        assertArrayEquals(payload, store.readSource(promoted.id))
        assertEquals(promoted, store.recordSource(source(), payload))
        File(root, "objective/sources/${promoted.id}.txt").delete()
        val restoredMissing = store.recordSource(source(), payload)
        assertEquals(promoted, restoredMissing)
        assertArrayEquals(payload, WorkbenchStore(root, "objective").readSource(promoted.id))
    }

    @Test fun symlinkAndNonregularDuplicatesAreRejectedWithoutTouchingExternalEvidence() {
        listOf(false, true).forEachIndexed { index, directory ->
            val root = temporary.newFolder("unsafe-source-$index")
            val store = WorkbenchStore(root, "objective")
            val retained = store.recordSource(source(), payload)
            val path = File(root, "objective/sources/${retained.id}.txt").toPath()
            Files.delete(path)
            val external = temporary.newFile("external-$index").apply { writeText("private") }
            if (directory) Files.createDirectory(path) else Files.createSymbolicLink(path, external.toPath())
            assertThrows(Exception::class.java) { store.recordSource(source(), payload) }
            assertEquals("private", external.readText())
            assertTrue(Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test fun duplicateFailedSourcesNeverPromoteTheirStatus() {
        val store = WorkbenchStore(temporary.newFolder("failed-source"), "objective")
        val failed = source().copy(status = "FAILED", note = "fixture denial")
        val record = store.recordSource(failed, payload)
        assertEquals(record, store.recordSource(failed, payload))
        assertEquals("FAILED", store.listSources().single().status)
        assertArrayEquals(payload, store.readSource(record.id))
        assertTrue(store.listArtifacts().isEmpty())
    }
}

package com.nyx.ai.workbench

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class VerifiedArtifactExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = "verified local artifact bytes".toByteArray()

    private class FailingDestination(private val failure: String) : OutputStream() {
        val partial = ByteArrayOutputStream()
        var closed = false
        override fun write(b: Int) {
            if (failure == "write" && partial.size() == 3) throw IOException("fixture interrupted write")
            partial.write(b)
        }
        override fun flush() { if (failure == "flush") throw IOException("fixture flush failure") }
        override fun close() { closed = true; if (failure == "close") throw IOException("fixture close failure") }
    }

    @Test fun successfulStreamContainsExactVerifiedSnapshot() {
        val store = WorkbenchStore(temporary.newFolder("successful-export"), "objective")
        val record = store.writeArtifact("report.txt", ArtifactFormat.TEXT, payload)
        val destination = FailingDestination("none")
        VerifiedArtifactExport.write(store, record) { destination }
        assertTrue(destination.closed)
        assertArrayEquals(payload, destination.partial.toByteArray())
        assertArrayEquals(payload, store.readArtifact(record))
        // This checks a host stream, NOT Android provider durability or picker permissions.
    }

    @Test fun partialWriteFlushAndCloseFailuresPropagateAndNeverDamageRetainedBytes() {
        listOf("write", "flush", "close").forEach { phase ->
            val root = temporary.newFolder("failed-export-$phase")
            val store = WorkbenchStore(root, "objective")
            val record = store.writeArtifact("report.txt", ArtifactFormat.TEXT, payload)
            val manifest = File(root, "objective/meta/manifest.json").readBytes()
            val destination = FailingDestination(phase)
            val error = assertThrows(IOException::class.java) { VerifiedArtifactExport.write(store, record) { destination } }
            assertTrue(error.message!!.contains("partial data"))
            assertNotNull(error.cause)
            assertTrue(destination.closed)
            if (phase == "write") assertEquals(3, destination.partial.size())
            assertArrayEquals(manifest, File(root, "objective/meta/manifest.json").readBytes())
            assertArrayEquals(payload, WorkbenchStore(root, "objective").readArtifact(record))
        }
    }

    @Test fun corruptOrForgedRecordIsRejectedBeforeDestinationOpens() {
        val root = temporary.newFolder("corrupt-export")
        val store = WorkbenchStore(root, "objective")
        val record = store.writeArtifact("report.txt", ArtifactFormat.TEXT, payload)
        var opened = false
        val open = { opened = true; ByteArrayOutputStream() }
        assertThrows(Exception::class.java) { VerifiedArtifactExport.write(store, record.copy(sha256 = "0".repeat(64)), open) }
        assertFalse(opened)
        File(root, "objective/artifacts/report.txt").writeText("corrupt local bytes")
        assertThrows(Exception::class.java) { VerifiedArtifactExport.write(store, record, open) }
        assertFalse(opened)
    }

    @Test fun absentProviderStreamAndOpenFailureAreExplicitFailures() {
        val store = WorkbenchStore(temporary.newFolder("unavailable-export"), "objective")
        val record = store.writeArtifact("report.txt", ArtifactFormat.TEXT, payload)
        assertThrows(IOException::class.java) { VerifiedArtifactExport.write(store, record) { null } }
        assertThrows(IOException::class.java) { VerifiedArtifactExport.write(store, record) { throw IOException("fixture open failure") } }
        assertArrayEquals(payload, store.readArtifact(record))
    }
}

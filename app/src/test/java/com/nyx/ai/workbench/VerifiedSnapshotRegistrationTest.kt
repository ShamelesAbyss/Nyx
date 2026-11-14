package com.nyx.ai.workbench

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VerifiedSnapshotRegistrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactSnapshotRegistersRealBytesAndSurvivesReopenWithoutCallerMutation() {
        val root = temporary.newFolder("verified-snapshot")
        val store = WorkbenchStore(root, "objective")
        val payload = "{\"tests\":\"passed\",\"exitCode\":0}".toByteArray()
        val original = payload.copyOf()
        val hash = WorkbenchStore.sha256(payload)
        val record = store.registerVerifiedSnapshot("results.json", ArtifactFormat.JSON, payload, payload.size.toLong(), hash)
        assertEquals(record, store.registerVerifiedSnapshot("results.json", ArtifactFormat.JSON, payload, payload.size.toLong(), hash))
        payload.fill(0)
        val reopened = WorkbenchStore(root, "objective")
        assertEquals("objective", reopened.objectiveId)
        assertArrayEquals(original, reopened.readArtifact(record))
        assertEquals(hash, record.sha256)
        assertEquals(original.size.toLong(), record.bytes)
        // Fixture JSON is not evidence that any external compiler/test process actually ran.
    }

    @Test fun mismatchedOrMalformedEvidenceRejectsBeforeCreatingAnyPayload() {
        val root = temporary.newFolder("invalid-snapshot")
        val store = WorkbenchStore(root, "objective")
        val payload = "{\"value\":1}".toByteArray()
        val hash = WorkbenchStore.sha256(payload)
        listOf(0L to hash, Long.MAX_VALUE to hash, (payload.size + 1L) to hash,
            payload.size.toLong() to "0".repeat(64), payload.size.toLong() to hash.uppercase(),
            payload.size.toLong() to "not-a-digest").forEach { (size, digest) ->
            assertThrows(IllegalArgumentException::class.java) {
                store.registerVerifiedSnapshot("results.json", ArtifactFormat.JSON, payload, size, digest)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.registerVerifiedSnapshot("results.json", ArtifactFormat.JSON, "{bad}".toByteArray(), 5, WorkbenchStore.sha256("{bad}".toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.registerVerifiedSnapshot("../results.json", ArtifactFormat.JSON, payload, payload.size.toLong(), hash)
        }
        assertTrue(store.listArtifacts().isEmpty())
        assertTrue(File(root, "objective/artifacts").listFiles()!!.isEmpty())
    }

    @Test fun changedDuplicateCannotBecomeARegisteredSuccess() {
        val root = temporary.newFolder("stale-snapshot")
        val store = WorkbenchStore(root, "objective")
        val payload = "good".toByteArray()
        val record = store.registerVerifiedSnapshot("results.txt", ArtifactFormat.TEXT, payload, 4, WorkbenchStore.sha256(payload))
        File(root, "objective/artifacts/results.txt").writeText("evil")
        assertThrows(IllegalArgumentException::class.java) {
            store.registerVerifiedSnapshot("results.txt", ArtifactFormat.TEXT, payload, 4, WorkbenchStore.sha256(payload))
        }
        assertFalse(store.verifyArtifact(record))
        assertEquals("evil", File(root, "objective/artifacts/results.txt").readText())
    }

    @Test fun configuredQuotaStillAppliesToVerifiedTransportBytes() {
        val root = temporary.newFolder("quota-snapshot")
        val store = WorkbenchStore(root, "objective", WorkbenchLimits(maxArtifactBytes = 4, maxTotalBytes = 5))
        val payload = "good".toByteArray()
        store.registerVerifiedSnapshot("one.txt", ArtifactFormat.TEXT, payload, 4, WorkbenchStore.sha256(payload))
        assertThrows(IllegalArgumentException::class.java) {
            store.registerVerifiedSnapshot("two.txt", ArtifactFormat.TEXT, payload, 4, WorkbenchStore.sha256(payload))
        }
        assertFalse(File(root, "objective/artifacts/two.txt").exists())
        assertEquals(1, store.listArtifacts().size)
    }
}

package com.nyx.ai.workbench

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pure JVM tests: no Context, FileProvider, document picker, chooser, or device assumptions. */
class HandoffCachePolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun base() = File(temporary.newFolder(), "handoff")
    private fun policy(now: Long = 10_000, count: Int = 3, bytes: Long = 30) =
        HandoffCachePolicy(maxAgeMs = 1_000, maxSnapshots = count, maxBytes = bytes, clock = { now })
    private fun stage(policy: HandoffCachePolicy, base: File, text: String = "file") =
        policy.snapshot(base, "report.md", text.toByteArray()) { it }
    private fun legacy(base: File, timestamp: Long, text: String? = "old"): File {
        base.mkdirs()
        val folder = File(base, UUID.randomUUID().toString()).apply { mkdir() }
        if (text != null) File(folder, "report.md").writeText(text)
        Files.setLastModifiedTime(folder.toPath(), FileTime.fromMillis(timestamp))
        return folder
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected closed rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun successfulSnapshotIsExactIndependentAndRetainedAfterPreparation() {
        val base = base()
        val first = stage(policy(bytes = 100), base, "verified content")
        val second = stage(policy(bytes = 100), base, "verified content")
        assertNotEquals(first.parentFile, second.parentFile)
        assertEquals("report.md", first.name)
        assertEquals("verified content", first.readText())
        assertEquals("verified content", second.readText())
        assertEquals(10_000L, requireNotNull(first.parentFile).lastModified())
        assertEquals(0, policy().cleanup(base))
        assertTrue(first.isFile)
        assertTrue(second.isFile)
    }

    @Test fun ageBoundaryRemovesOnlyExpiredIncludingLegacyAndEmptyInterruptedSnapshots() {
        val base = base()
        val expired = legacy(base, 9_000)
        val empty = legacy(base, 8_999, null)
        val fresh = legacy(base, 9_001, "fresh")
        assertEquals(2, policy().cleanup(base))
        assertFalse(expired.exists())
        assertFalse(empty.exists())
        assertEquals("fresh", File(fresh, "report.md").readText())
        assertEquals(0, policy().cleanup(base))
    }

    @Test fun expiredSnapshotsArePrunedBeforeAdmittingANewShare() {
        val base = base()
        val old = legacy(base, 9_000, "0123456789")
        val fresh = legacy(base, 9_999, "fresh")
        val added = stage(policy(count = 2, bytes = 10), base, "new")
        assertFalse(old.exists())
        assertEquals("fresh", File(fresh, "report.md").readText())
        assertEquals("new", added.readText())
        assertEquals(2, base.listFiles()!!.size)
    }

    @Test fun fullCountRejectsWithoutEvictingAnyFreshRecipient() {
        val base = base()
        val first = stage(policy(count = 1), base)
        rejected { stage(policy(count = 1), base, "second") }
        assertEquals("file", first.readText())
        assertEquals(1, base.listFiles()!!.size)
    }

    @Test fun fullBytesRejectsWithoutEvictingAnyFreshRecipient() {
        val base = base()
        val first = stage(policy(bytes = 5), base, "12345")
        rejected { stage(policy(bytes = 5), base, "6") }
        assertEquals("12345", first.readText())
        assertEquals(1, base.listFiles()!!.size)
    }

    @Test fun existingFreshCacheAboveNewByteLimitFailsClosed() {
        val base = base()
        val first = stage(policy(bytes = 10), base, "123456")
        rejected { stage(policy(bytes = 5), base, "x") }
        assertEquals("123456", first.readText())
        assertEquals(1, base.listFiles()!!.size)
    }

    @Test fun oversizedIncomingBytesAndUnsafeNamesNeverCreateSnapshots() {
        val base = base()
        rejected { stage(policy(bytes = 3), base, "four") }
        for (name in listOf("../outside.md", "a/b.md", "a\\b.md", "a..md", ".md")) {
            rejected { policy().snapshot(base, name, byteArrayOf(1)) { it } }
        }
        assertFalse(base.exists())
    }

    @Test fun preparationFailureRollsBackOnlyNewSnapshot() {
        val base = base()
        val existing = stage(policy(), base, "first")
        var failed: File? = null
        try {
            policy().snapshot(base, "report.md", "next".toByteArray()) {
                failed = it
                assertEquals("next", it.readText())
                throw IllegalStateException("URI preparation failed")
            }
            fail("Expected preparation failure")
        } catch (error: IllegalStateException) { assertEquals("URI preparation failed", error.message) }
        assertFalse(failed!!.exists())
        assertFalse(requireNotNull(failed!!.parentFile).exists())
        assertEquals("first", existing.readText())
        assertEquals(1, base.listFiles()!!.size)
    }

    @Test fun reopeningPolicyRetainsFreshFilesAndExpiresThemLater() {
        val base = base()
        val first = stage(policy(), base)
        assertEquals(0, policy(now = 10_999).cleanup(base))
        rejected { stage(policy(now = 10_999, count = 1), base) }
        assertEquals("file", first.readText())
        assertEquals(1, policy(now = 11_000).cleanup(base))
        assertFalse(first.exists())
    }

    @Test fun clockRollbackDoesNotEvictFutureDatedRecipient() {
        val base = base()
        val future = legacy(base, 20_000, "future")
        assertEquals(0, policy().cleanup(base))
        rejected { stage(policy(count = 1), base) }
        assertEquals("future", File(future, "report.md").readText())
    }

    @Test fun rootAndAncestorSymlinksAreRejectedWithoutTouchingTargets() {
        val external = temporary.newFolder()
        val sentinel = File(external, "private.txt").apply { writeText("private") }
        val parent = temporary.newFolder()
        val link = File(parent, "link").toPath()
        Files.createSymbolicLink(link, external.toPath())
        rejected { stage(policy(), link.toFile()) }
        rejected { stage(policy(), File(link.toFile(), "child")) }
        rejected { policy().cleanup(link.toFile()) }
        assertEquals("private", sentinel.readText())
        assertFalse(File(external, "child").exists())
        Files.delete(link)
    }

    @Test fun symlinkSnapshotOrPayloadBlocksCleanupBeforeAnyDeletion() {
        for (directoryLink in listOf(true, false)) {
            val base = base().apply { mkdir() }
            val expired = legacy(base, 1_000, "must retain")
            val external = temporary.newFolder()
            val sentinel = File(external, "private.md").apply { writeText("private") }
            val link = if (directoryLink) File(base, UUID.randomUUID().toString()).toPath()
                else File(legacy(base, 1_000, null), "report.md").toPath()
            Files.createSymbolicLink(link, if (directoryLink) external.toPath() else sentinel.toPath())
            rejected { policy().cleanup(base) }
            rejected { stage(policy(), base) }
            assertEquals("must retain", File(expired, "report.md").readText())
            assertEquals("private", sentinel.readText())
            Files.delete(link)
        }
    }

    @Test fun nestedOrUnknownEntriesAreNotRecursivelyDeleted() {
        val base = base()
        val folder = legacy(base, 1_000, null)
        val nested = File(folder, "nested").apply { mkdir() }
        val sentinel = File(nested, "private.txt").apply { writeText("private") }
        rejected { policy().cleanup(base) }
        assertEquals("private", sentinel.readText())
        val unknown = File(base, "unrecognized.txt").apply { writeText("keep") }
        rejected { policy().cleanup(base) }
        assertEquals("keep", unknown.readText())
    }

    @Test fun cleanupScanIsBoundedAndFailsWithoutDeletingOversizedLayout() {
        val base = base()
        repeat(513) { legacy(base, 1_000, null) }
        rejected { policy().cleanup(base) }
        assertEquals(513, base.listFiles()!!.size)
    }

    @Test fun concurrentInstancesCannotOverbookFreshCountOrBytes() {
        val base = base()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..8).map {
                Callable { try { stage(policy(count = 3, bytes = 12), base) } catch (_: IllegalArgumentException) { null } }
            }).map { it.get() }
            assertEquals(3, results.count { it != null })
            assertEquals(3, base.listFiles()!!.size)
            results.filterNotNull().forEach { assertEquals("file", it.readText()) }
        } finally { executor.shutdownNow() }
    }

    @Test fun invalidBoundsAndClockAreRejected() {
        rejected { HandoffCachePolicy(maxAgeMs = 0) }
        rejected { HandoffCachePolicy(maxSnapshots = 0) }
        rejected { HandoffCachePolicy(maxSnapshots = 513) }
        rejected { HandoffCachePolicy(maxBytes = 0) }
        rejected { HandoffCachePolicy(clock = { -1 }).cleanup(base()) }
    }
}

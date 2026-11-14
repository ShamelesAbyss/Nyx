package com.nyx.ai.workbench

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkbenchStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = File(temporary.root, "workbench")
    private fun store(limits: WorkbenchLimits = WorkbenchLimits()) = WorkbenchStore(root(), "objective-1", limits)
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: Exception) { assertTrue(e is IllegalArgumentException || e is java.io.IOException) }
    }

    @Test fun objectiveTraversalAndReservedNamesRejected() {
        listOf("../outside", "/tmp/outside", ".hidden", "a..b", "a/b", "a\\b", "CON", "nul.txt", "COM1", "a.", "", "x".repeat(81)).forEach {
            reject { WorkbenchStore(root(), it) }
        }
    }
    @Test fun artifactTraversalAndExtensionMismatchRejected() {
        val s = store()
        listOf("../x.txt", "/x.txt", "a/b.txt", ".x.txt", "a..txt", "CON.txt", "NUL.txt", "a\\b.txt").forEach {
            reject { s.writeArtifact(it, ArtifactFormat.TEXT, "hello".toByteArray()) }
        }
        reject { s.writeArtifact("fake.pdf", ArtifactFormat.TEXT, "hello".toByteArray()) }
        assertTrue(s.listArtifacts().isEmpty())
    }
    @Test fun validWritesSurviveRestartAndAreIdempotent() {
        val s = store()
        val a = s.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "# Findings\nEvidence.".toByteArray())
        assertEquals(a, s.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "# Findings\nEvidence.".toByteArray()))
        val reopen = store()
        assertEquals(listOf(a), reopen.listArtifacts())
        assertArrayEquals("# Findings\nEvidence.".toByteArray(), reopen.readArtifact(a))
        assertTrue(reopen.verifyArtifact(a.id))
        assertTrue(reopen.verifiedArtifactFile(a).isFile)
        assertEquals("objective-1", reopen.readManifest().objectiveId)
        reject { reopen.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "changed".toByteArray()) }
        reject { reopen.writeArtifact("REPORT.md", ArtifactFormat.MARKDOWN, "# Findings\nEvidence.".toByteArray()) }
    }
    @Test fun invalidFilesNeverRegisteredOrLeftBehind() {
        val s = store()
        reject { s.writeArtifact("bad.json", ArtifactFormat.JSON, "{oops}".toByteArray()) }
        reject { s.writeArtifact("bad.csv", ArtifactFormat.CSV, "a,b\n1".toByteArray()) }
        reject { s.writeArtifact("bad.pdf", ArtifactFormat.PDF, "%PDF-1.4\n/Type /Page\n%%EOF".toByteArray()) }
        assertTrue(s.listArtifacts().isEmpty())
        assertTrue(File(root(), "objective-1/artifacts").listFiles()!!.isEmpty())
    }
    @Test fun forgedRecordsAndPrivatePathsCannotExport() {
        val s = store(); val a = s.writeArtifact("a.txt", ArtifactFormat.TEXT, "hi".toByteArray())
        listOf(a.copy(relativePath = "../private.txt"), a.copy(fileName = "secret.txt"), a.copy(sha256 = "0".repeat(64)), a.copy(status = ArtifactStatus.FAILED)).forEach {
            assertFalse(s.verifyArtifact(it)); reject { s.readArtifact(it) }; reject { s.verifiedArtifactFile(it) }
        }
        reject { s.readArtifact("../meta/manifest.json") }
    }
    @Test fun corruptionAndMissingFilesBecomeFailedAndNeverExported() {
        val s = store(); val a = s.writeArtifact("a.txt", ArtifactFormat.TEXT, "hello".toByteArray())
        File(root(), "objective-1/artifacts/a.txt").writeText("other")
        assertFalse(s.verifyArtifact(a)); assertTrue(store().listArtifacts().isEmpty())
        assertEquals(ArtifactStatus.FAILED, store().readManifest().artifacts.single().status)
        assertTrue(s.deleteArtifact(a.id)); assertFalse(s.deleteArtifact(a.id))
        val b = s.writeArtifact("b.txt", ArtifactFormat.TEXT, "hello".toByteArray())
        File(root(), "objective-1/artifacts/b.txt").delete()
        assertFalse(s.verifyArtifact(b.id)); assertTrue(store().listArtifacts().isEmpty())
    }
    @Test fun rootAncestorObjectiveAndDirectorySymlinksRejected() {
        val external = temporary.newFolder("external").toPath()
        val link = File(temporary.root, "linked").toPath()
        Files.createSymbolicLink(link, external)
        reject { WorkbenchStore(link.toFile(), "o") }
        root().mkdirs(); Files.createSymbolicLink(File(root(), "o").toPath(), external)
        reject { WorkbenchStore(root(), "o") }
        val s = store()
        val artifacts = File(root(), "objective-1/artifacts").toPath()
        Files.delete(artifacts); Files.createSymbolicLink(artifacts, external)
        reject { s.writeArtifact("out.txt", ArtifactFormat.TEXT, "no".toByteArray()) }
        assertFalse(external.resolve("out.txt").toFile().exists())
    }
    @Test fun artifactAndManifestSymlinksRejectedWithoutTouchingTarget() {
        val target = temporary.newFile("target").apply { writeText("private") }
        val s = store(); val a = s.writeArtifact("a.txt", ArtifactFormat.TEXT, "hello".toByteArray())
        val path = File(root(), "objective-1/artifacts/a.txt").toPath()
        Files.delete(path); Files.createSymbolicLink(path, target.toPath())
        assertFalse(s.verifyArtifact(a)); reject { store() }
        assertEquals("private", target.readText())
        Files.delete(path)
        val meta = File(root(), "objective-1/meta/manifest.json").toPath()
        Files.delete(meta); Files.createSymbolicLink(meta, target.toPath())
        reject { store() }; assertEquals("private", target.readText())
    }
    @Test fun quotaEnforcedAcrossConcurrentInstances() {
        val limits = WorkbenchLimits(maxArtifacts = 2, maxArtifactBytes = 8, maxTotalBytes = 8)
        val stores = (1..8).map { store(limits) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(stores.mapIndexed { i, s -> Callable {
                try { s.writeArtifact("$i.txt", ArtifactFormat.TEXT, "four".toByteArray()); true }
                catch (_: IllegalArgumentException) { false }
            } }).map { it.get() }
            assertEquals(2, results.count { it })
            assertEquals(8L, store(limits).listArtifacts().sumOf { it.bytes })
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun idempotencyConcurrentAndByteQuotaIndependentOfCount() {
        val limits = WorkbenchLimits(maxArtifactBytes = 4, maxTotalBytes = 5)
        val s = store(limits)
        reject { s.writeArtifact("big.txt", ArtifactFormat.TEXT, "12345".toByteArray()) }
        s.writeArtifact("ok.txt", ArtifactFormat.TEXT, "1234".toByteArray())
        reject { store(limits).writeArtifact("two.txt", ArtifactFormat.TEXT, "12".toByteArray()) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..4).map { Callable { store(limits).writeArtifact("ok.txt", ArtifactFormat.TEXT, "1234".toByteArray()) } }).map { it.get() }
            assertEquals(1, results.map { it.id }.distinct().size)
        } finally { pool.shutdown() }
    }
    @Test fun metadataBoundsAndQuotaFailureAreAtomic() {
        val s = store(WorkbenchLimits(maxMetadataBytes = 500))
        reject { s.recordSource(ResearchSource("https://example.com", title = "x".repeat(513))) }
        reject { s.recordSource(ResearchSource("https://example.com", note = "x".repeat(1024))) }
        assertTrue(s.listSources().isEmpty())
        assertTrue(File(root(), "objective-1/sources").listFiles()!!.isEmpty())
    }
    @Test fun sourcesAndImportsPersistButAreNotArtifacts() {
        val s = store()
        val source = s.recordSource(ResearchSource("https://example.com", bytes = 1, sha256 = "a".repeat(64)), "untrusted page".toByteArray())
        assertEquals(14L, source.bytes); assertNotEquals("a".repeat(64), source.sha256)
        val duplicate = s.recordSource(ResearchSource("https://example.com"), "untrusted page".toByteArray())
        assertEquals(source, duplicate)
        val failure = s.recordSource(ResearchSource("https://bad.example", status = "FAILED", note = "denied"))
        assertNull(s.readSource(failure.id))
        val imp = s.importFile("input.bin", byteArrayOf(0, 1, 2))
        assertEquals(imp, s.importFile("input.bin", byteArrayOf(0, 1, 2)))
        val reopen = store()
        assertArrayEquals("untrusted page".toByteArray(), reopen.readSource(source.id))
        assertArrayEquals(byteArrayOf(0, 1, 2), reopen.readImport(imp.id))
        assertTrue(reopen.listArtifacts().isEmpty())
        reject { reopen.readArtifact(imp.id) }; reject { reopen.readArtifact(source.id) }
    }
    @Test fun sourcesImportsAndArtifactsShareTotalQuota() {
        val s = store(WorkbenchLimits(maxTotalBytes = 6, maxSourceBytes = 4, maxImportBytes = 4))
        s.recordSource(ResearchSource("https://example.com"), "123".toByteArray())
        s.importFile("in.bin", "12".toByteArray())
        reject { s.writeArtifact("a.txt", ArtifactFormat.TEXT, "12".toByteArray()) }
        reject { s.recordSource(ResearchSource("https://other.example"), "12345".toByteArray()) }
        s.writeArtifact("b.txt", ArtifactFormat.TEXT, "1".toByteArray())
        assertEquals(1, s.listArtifacts().size)
    }
    @Test fun interruptedPayloadAndTempsDiscardedExistingCommitPreserved() {
        val s = store(); val a = s.writeArtifact("kept.txt", ArtifactFormat.TEXT, "durable".toByteArray())
        File(root(), "objective-1/artifacts/uncommitted.txt").writeText("not registered")
        File(root(), "objective-1/artifacts/.tmp-interrupted").writeText("partial")
        File(root(), "objective-1/meta/.tmp-interrupted").writeText("partial manifest")
        val reopen = store()
        assertEquals(listOf(a), reopen.listArtifacts()); assertArrayEquals("durable".toByteArray(), reopen.readArtifact(a))
        assertFalse(File(root(), "objective-1/artifacts/uncommitted.txt").exists())
        assertFalse(File(root(), "objective-1/meta/.tmp-interrupted").exists())
    }
    @Test fun corruptedManifestFailsClosedPreservingEvidence() {
        val s = store(); s.writeArtifact("kept.txt", ArtifactFormat.TEXT, "durable".toByteArray())
        File(root(), "objective-1/meta/manifest.json").writeText("{broken")
        reject { store() }
        assertEquals("durable", File(root(), "objective-1/artifacts/kept.txt").readText())
        assertEquals("{broken", File(root(), "objective-1/meta/manifest.json").readText())
    }
    @Test fun missingManifestDoesNotDestroyExistingArtifacts() {
        val s = store(); s.writeArtifact("kept.txt", ArtifactFormat.TEXT, "durable".toByteArray())
        File(root(), "objective-1/meta/manifest.json").delete()
        reject { store() }
        assertEquals("durable", File(root(), "objective-1/artifacts/kept.txt").readText())
    }
    @Test fun sourceAndImportCountQuotasPersistAcrossInstances() {
        val limits = WorkbenchLimits(maxSources = 1, maxImports = 1)
        val s = store(limits)
        s.recordSource(ResearchSource("https://example.com", status = "FAILED", note = "denied"))
        s.importFile("one.bin", byteArrayOf(1))
        reject { store(limits).recordSource(ResearchSource("https://other.example", status = "FAILED")) }
        reject { store(limits).importFile("two.bin", byteArrayOf(2)) }
        assertEquals(1, s.listSources().size); assertEquals(1, s.listImports().size)
    }
    @Test fun failedSourceContentStaysFailedAndMetadataOnlySourceCanGainContent() {
        val s = store()
        val bytes = "text".toByteArray()
        val source = s.recordSource(ResearchSource("https://example.com", bytes = bytes.size.toLong(), sha256 = WorkbenchStore.sha256(bytes)))
        assertNull(s.readSource(source.id))
        val updated = s.recordSource(ResearchSource("https://example.com"), bytes)
        assertEquals(source.id, updated.id); assertArrayEquals(bytes, s.readSource(updated.id))
        val failed = s.recordSource(ResearchSource("https://failed.example", status = "FAILED"), bytes)
        assertEquals("FAILED", store().listSources().single { it.id == failed.id }.status)
        assertTrue(s.listArtifacts().isEmpty())
    }
    @Test fun everySupportedFormatIsPersistedAndValidatedOnReopen() {
        val s = store()
        val payloads = listOf(
            Triple("report.md", ArtifactFormat.MARKDOWN, "# Report".toByteArray()),
            Triple("report.txt", ArtifactFormat.TEXT, "Report".toByteArray()),
            Triple("report.csv", ArtifactFormat.CSV, "name,value\nA,1".toByteArray()),
            Triple("report.json", ArtifactFormat.JSON, "{\"value\":1}".toByteArray()),
            Triple("report.html", ArtifactFormat.HTML, "<!doctype html><html><head><title>Report</title></head><body><h1>Report</h1></body></html>".toByteArray())
        )
        val records = payloads.map { (name, format, bytes) -> s.writeArtifact(name, format, bytes) }
        val bundleBytes = ZipBundleWriter.write(records.associate { it.fileName to s.readArtifact(it) })
        val bundle = s.writeArtifact("bundle.zip", ArtifactFormat.ZIP, bundleBytes)
        val reopened = store()
        assertEquals(records + bundle, reopened.listArtifacts())
        records.forEachIndexed { index, record -> assertArrayEquals(payloads[index].third, reopened.readArtifact(record)) }
        assertArrayEquals(bundleBytes, reopened.readArtifact(bundle))
    }
    @Test fun manifestPathForgeryRejected() {
        val s = store(); s.writeArtifact("kept.txt", ArtifactFormat.TEXT, "durable".toByteArray())
        val manifest = File(root(), "objective-1/meta/manifest.json")
        manifest.writeText(manifest.readText().replace("artifacts/kept.txt", "../secret.txt"))
        reject { store() }
    }
    @Test fun sourceAndImportChecksumsRecheckedAfterMutation() {
        val s = store(); val r = s.recordSource(ResearchSource("https://example.com"), "good".toByteArray())
        val i = s.importFile("in.bin", "good".toByteArray())
        File(root(), "objective-1/sources/${r.id}.txt").writeText("evil")
        File(root(), "objective-1/${i.relativePath}").writeText("evil")
        reject { s.readSource(r.id) }; reject { s.readImport(i.id) }
    }

    private fun process(mode: String, index: Int = 0): Process {
        val cp = listOf(WorkbenchProcessWriter::class.java, WorkbenchStore::class.java,
            kotlin.Unit::class.java, org.json.JSONObject::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", cp, "com.nyx.ai.workbench.WorkbenchProcessWriter", root().absolutePath, mode, index.toString())
            .redirectErrorStream(true).start()
    }
    @Test fun quotaEnforcedAcrossRealProcesses() {
        store(WorkbenchLimits(maxArtifacts = 2, maxArtifactBytes = 4, maxTotalBytes = 8))
        val processes = (0..3).map { process("quota", it) }
        try {
            processes.forEach { assertTrue("Process timeout", it.waitFor(15, TimeUnit.SECONDS)) }
            val outcomes = processes.map { it.exitValue() }
            assertTrue(processes.joinToString { it.inputStream.bufferedReader().readText() }, outcomes.all { it == 0 || it == 2 })
            assertEquals(2, outcomes.count { it == 0 })
            assertEquals(8L, store().listArtifacts().sumOf { it.bytes })
        } finally { processes.forEach { if (it.isAlive) it.destroyForcibly() } }
    }
    @Test fun abruptProcessStopKeepsCommittedArtifactsAndDiscardsUncommittedData() {
        val s = store(); val baseline = s.writeArtifact("baseline.txt", ArtifactFormat.TEXT, "baseline".toByteArray())
        val child = process("crash")
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS))
            assertEquals(child.inputStream.bufferedReader().readText(), 0, child.exitValue())
            val reopened = store()
            assertTrue(reopened.verifyArtifact(baseline))
            val committed = reopened.listArtifacts().single { it.fileName == "committed.txt" }
            assertArrayEquals("persistent".toByteArray(), reopened.readArtifact(committed))
            assertFalse(File(root(), "objective-1/artifacts/orphan.txt").exists())
            assertFalse(File(root(), "objective-1/meta/.tmp-crash").exists())
        } finally { if (child.isAlive) child.destroyForcibly() }
    }
}

/** Standalone JVM fixture: no Gradle/test-worker coordination or Android runtime needed. */
object WorkbenchProcessWriter {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args[1]
        val limits = if (mode == "quota") WorkbenchLimits(maxArtifacts = 2, maxArtifactBytes = 4, maxTotalBytes = 8) else WorkbenchLimits()
        try {
            val s = WorkbenchStore(File(args[0]), "objective-1", limits)
            if (mode == "quota") s.writeArtifact("process-${args[2]}.txt", ArtifactFormat.TEXT, "four".toByteArray())
            else {
                s.writeArtifact("committed.txt", ArtifactFormat.TEXT, "persistent".toByteArray())
                File(args[0], "objective-1/artifacts/orphan.txt").writeText("uncommitted payload")
                File(args[0], "objective-1/meta/.tmp-crash").writeText("partial manifest")
                Runtime.getRuntime().halt(0) // No finally blocks, shutdown hooks, or graceful cleanup.
            }
        } catch (e: IllegalArgumentException) {
            println(e.message)
            kotlin.system.exitProcess(2)
        }
    }
}

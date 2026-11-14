package com.nyx.ai.workbench

import com.nyx.ai.autonomy.policy.AutonomyMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** Opt-in host acceptance, never mixed with deterministic MockWebServer fixture claims. */
class ResearchRealNetworkTest {
    @Test fun `three public HTTPS URLs production guarded host acceptance`() = runBlocking {
        assumeTrue("Set NYX_REAL_NETWORK_TEST=1 to run public-network acceptance", System.getenv("NYX_REAL_NETWORK_TEST") == "1")
        val urls = listOf("https://example.com/", "https://www.iana.org/help/example-domains", "https://www.rfc-editor.org/rfc/rfc8259.txt")
        val root = Files.createTempDirectory("nyx-research-real").toFile()
        try {
            val store = WorkbenchStore(root, "real-acceptance")
            val result = ResearchEngine(store, urls.toSet(), { AutonomyMode.SMART }).research(urls,
                ResearchBudget(maxSources = 3, maxBytesPerSource = 131_072, maxTotalBytes = 393_216, concurrency = 2, perHostDelayMs = 750))
            println("REAL NETWORK RESULTS (host JVM, production guarded constructor; no model/device claim)")
            result.sources.forEach { println("${it.status}\t${it.url}\tfinal=${it.finalUrl}\tbytes=${it.bytes}\tsha256=${it.sha256}\tfetchedAt=${it.fetchedAt}\tnote=${it.note}") }
            println("Extracted titles: ${result.pages.map { it.document.title }}")
            assertEquals(3, result.sources.size)
            assertEquals("All three real URLs must be fetched for acceptance", 3, result.pages.size)
            assertTrue(result.sources.all { it.status == "FETCHED" && it.bytes > 0 && it.sha256.length == 64 })
            val reopened = WorkbenchStore(root, "real-acceptance")
            assertEquals(result.sources.toSet(), reopened.listSources().toSet())
            result.sources.forEach { assertEquals(it.sha256, ResearchEngine.sha256(reopened.readSource(it.id)!!)) }
            assertFalse(result.asUntrustedBlock().contains("<html"))
            println("REAL NETWORK ACCEPTANCE PASSED: three fetched/extracted/durable sources, bounded untrusted evidence, reopened checksums")
        } finally { root.deleteRecursively() }
    }
}

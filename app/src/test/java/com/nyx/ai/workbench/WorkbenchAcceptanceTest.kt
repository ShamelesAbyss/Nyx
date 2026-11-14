package com.nyx.ai.workbench

import com.nyx.ai.autonomy.policy.AutonomyMode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Host acceptance with real network/files, but deterministic text—not real-model synthesis. */
class WorkbenchAcceptanceTest {
    @Test fun publicSourcesToCitedReportAndValidatedFormats(): Unit = runBlocking {
        assumeTrue("Opt-in real network trial", System.getenv("NYX_REAL_NETWORK_TEST") == "1")
        val root = Files.createTempDirectory("nyx-acceptance").toFile()
        try {
            val store = WorkbenchStore(root, "source-report")
            val urls = listOf("https://example.com/", "https://www.iana.org/help/example-domains", "https://www.rfc-editor.org/rfc/rfc8259.txt")
            val result = ResearchEngine(store, urls.toSet(), { AutonomyMode.SMART }).research(urls,
                ResearchBudget(maxSources = 3, maxBytesPerSource = 131_072, maxTotalBytes = 393_216, concurrency = 2))
            assertEquals(3, result.pages.size)
            val report = buildString {
                appendLine("# Source-backed research acceptance")
                appendLine("\nThis is a deterministic extraction comparison on the Linux build host, not model-driven synthesis or Android hardware verification.")
                appendLine("\nThe first two sources document reserved example domains; the third describes the JSON interchange format. Excerpts and provenance below allow independent review; byte counts measure retained text, not importance.")
                result.pages.forEachIndexed { index, page ->
                    appendLine("\n## Source ${index + 1}: ${page.document.title.replace('\n', ' ')}")
                    appendLine("\n[S${index + 1}](${page.source.finalUrl}) · retrieved ${page.source.fetchedAt} · ${page.source.bytes} retained bytes")
                    appendLine("\nExtracted excerpt (untrusted source data):\n")
                    page.document.text.take(900).lineSequence().forEach { appendLine("> $it") }
                    appendLine("\nSHA-256: `${page.source.sha256}`")
                }
                appendLine("\n## Limits\nNo generic search, JavaScript browsing or factual-correctness claim. The source texts are retained privately and their checksums survive reopening.")
            }
            val markdown = store.writeArtifact("report.md", ArtifactFormat.MARKDOWN, report.toByteArray())
            val json = JSONArray().apply { result.pages.forEachIndexed { i, p -> put(JSONObject().put("source", i + 1).put("url", p.source.finalUrl).put("title", p.document.title).put("bytes", p.source.bytes).put("sha256", p.source.sha256)) } }.toString(2)
            val jsonRecord = store.writeArtifact("sources.json", ArtifactFormat.JSON, json.toByteArray())
            fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
            val csv = "source,url,title,bytes,sha256\r\n" + result.pages.mapIndexed { i, p -> listOf((i + 1).toString(), p.source.finalUrl, p.document.title, p.source.bytes.toString(), p.source.sha256).joinToString(",", transform = ::quoted) }.joinToString("\r\n") + "\r\n"
            val csvRecord = store.writeArtifact("sources.csv", ArtifactFormat.CSV, csv.toByteArray())
            val html = "<!DOCTYPE html><html><head><title>Source report</title></head><body><h1>Source report</h1><pre>${ArtifactFormats.escapeHtml(report)}</pre></body></html>"
            val htmlRecord = store.writeArtifact("report.html", ArtifactFormat.HTML, html.toByteArray())
            val reopened = WorkbenchStore(root, "source-report")
            assertEquals(4, reopened.listArtifacts().size)
            listOf(markdown, jsonRecord, csvRecord, htmlRecord).forEach { assertTrue(reopened.verifyArtifact(it)); assertArrayEquals(store.readArtifact(it), AndroidArtifactHandoff.checkedBytes(reopened, it)) }
            assertEquals(3, JSONArray(reopened.readArtifact(jsonRecord).toString(Charsets.UTF_8)).length())
            urls.forEach { assertTrue(report.contains(it)) }
            result.sources.forEach { assertEquals(it.sha256, ResearchEngine.sha256(reopened.readSource(it.id)!!)) }
            // Optional parent-controlled host delivery path, never an agent/model path or Android export API.
            System.getenv("NYX_ACCEPTANCE_EXPORT")?.let { path ->
                val directory = File(path).apply { require(mkdirs() || isDirectory) }
                reopened.listArtifacts().forEach { File(directory, it.fileName).writeBytes(reopened.readArtifact(it)) }
                File(directory, "SHA256SUMS.txt").writeText(reopened.listArtifacts().joinToString("\n", postfix = "\n") { "${it.sha256}  ${it.fileName}" })
            }
        } finally { root.deleteRecursively() }
        Unit
    }

    @Test fun selectedLocalDocumentsProduceRetainedSummary() = runBlocking {
        val root = Files.createTempDirectory("nyx-import-acceptance").toFile()
        try {
            val store = WorkbenchStore(root, "local-summary")
            val text = "Selected local reference: launch review on Tuesday; two open issues."
            val imported = DocumentImporter(store).importSelectedStream("reference.txt", "text/plain", text.toByteArray().size.toLong()) { text.byteInputStream() }
            assertEquals(text, imported.text)
            val summary = "# Local document summary\n\nSelected reference reports a Tuesday launch review and two open issues.\n\nSource: ${imported.record.fileName}; SHA-256 `${imported.record.sha256}`. Deterministic fixture, not model synthesis.\n"
            val record = store.writeArtifact("summary.md", ArtifactFormat.MARKDOWN, summary.toByteArray())
            val reopened = WorkbenchStore(root, "local-summary")
            assertEquals(imported.record, reopened.listImports().single())
            assertTrue(reopened.verifyArtifact(record))
            assertEquals(summary, reopened.readArtifact(record).toString(Charsets.UTF_8))
        } finally { root.deleteRecursively() }
    }
}

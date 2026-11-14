package com.nyx.ai.workbench

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArtifactFormatsTest {
    private fun valid(f: ArtifactFormat, text: String) { val r = ArtifactFormats.validate(f, text.toByteArray()); assertTrue(r.note, r.valid) }
    private fun invalid(f: ArtifactFormat, text: String) { assertFalse(text.take(120), ArtifactFormats.validate(f, text.toByteArray()).valid) }
    private fun html(body: String) = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Report</title></head><body>$body</body></html>"

    @Test fun markdownAndTextAreStrictNonblankUtf8() {
        valid(ArtifactFormat.MARKDOWN, "# Report\nUnicode: é 中文")
        valid(ArtifactFormat.TEXT, "A real text file\n")
        invalid(ArtifactFormat.TEXT, " \n\t")
        invalid(ArtifactFormat.MARKDOWN, "\u0000binary")
        assertFalse(ArtifactFormats.validate(ArtifactFormat.TEXT, byteArrayOf(0xc3.toByte(), 0x28)).valid)
        assertFalse(ArtifactFormats.validate(ArtifactFormat.TEXT, ByteArray(0)).valid)
    }
    @Test fun csvSupportsQuotedMultilineEscapedQuotesAndTrailingEmptyFields() {
        valid(ArtifactFormat.CSV, "name,notes,last\r\nAlice,\"a,b\r\n\"\"quoted\"\"\",\r\n")
        valid(ArtifactFormat.CSV, "one\nvalue\n")
        valid(ArtifactFormat.CSV, "a,b\n1,\n")
        valid(ArtifactFormat.CSV, "a,b")
    }
    @Test fun csvMalformedAndRaggedRejected() {
        listOf("a,b\n1", "a,b\n1,2,3", ",b\n1,2", "a,\"bad", "a,b\n\"x\"no,2", "a,b\nno\"quote,2", "a,b\r1,2", "a,b\n\n").forEach { invalid(ArtifactFormat.CSV, it) }
    }
    @Test fun jsonUsesStrictGrammarNotLenientOrgJsonSyntax() {
        valid(ArtifactFormat.JSON, "{\"x\":[1,true,null,-2.5e+3],\"s\":\"\\uD83D\\uDE00\"}")
        valid(ArtifactFormat.JSON, "[]")
        listOf("{'x':1}", "{\"x\":1,}", "[1,]", "[01]", "[NaN]", "[Infinity]", "[undefined]", "{x:1}", "{} trailing", "true", "[\"bad\\x\"]", "[\"\\uD800\"]", "{\"a\":1,\"\\u0061\":2}", "[/* comment */1]").forEach { invalid(ArtifactFormat.JSON, it) }
        invalid(ArtifactFormat.JSON, "[".repeat(66) + "0" + "]".repeat(66))
    }
    @Test fun htmlIsStandaloneEscapedStaticDocument() {
        valid(ArtifactFormat.HTML, html("<h1>Findings</h1><p>A &amp; B</p><a href=\"https://example.com/source\">Citation</a>"))
        val hostile = "<script>alert('x')</script>"
        valid(ArtifactFormat.HTML, html("<p>${ArtifactFormats.escapeHtml(hostile)}</p>"))
        assertEquals("&lt;&amp;&gt;&quot;&#39;", ArtifactFormats.escapeHtml("<&>\"'"))
    }
    @Test fun htmlActiveExternalAndUnbalancedContentRejected() {
        listOf("<script>alert(1)</script>", "<img src=\"https://host/image\">", "<iframe></iframe>", "<p onclick=\"go()\">bad</p>", "<a href=\"javascript:alert(1)\">bad</a>", "<a href=\"jav&#97;script:alert(1)\">bad</a>", "<a href=\"file:///private\">bad</a>", "<p style=\"background:url(x)\">bad</p>", "<p>unclosed", "<p>&raw</p>", "<p id=x>bad</p>", "<p id=\"a\" id=\"b\">bad</p>", "<svg><script>x</script></svg>").forEach { invalid(ArtifactFormat.HTML, html(it)) }
        invalid(ArtifactFormat.HTML, "<html><head></head><body>no doctype</body></html>")
        invalid(ArtifactFormat.HTML, "<!doctype html><html><body>missing head</body></html>")
        invalid(ArtifactFormat.HTML, html("ok") + "<body>second document</body>")
        invalid(ArtifactFormat.HTML, "<!doctype html><html><head><meta http-equiv=\"refresh\" content=\"0;url=https://bad\"></head><body>x</body></html>")
    }
    @Test fun pdfSignaturesDoNotImplyValidityAndPdfIsDeferred() {
        val r = ArtifactFormats.validate(ArtifactFormat.PDF, "%PDF-1.4\n/Type /Page\n%%EOF".toByteArray())
        assertFalse(r.valid); assertTrue(r.note.contains("deferred", ignoreCase = true))
    }
    @Test fun deterministicZipContainsRealReadableFiles() {
        val entries = mapOf("report.md" to "# Evidence".toByteArray(), "manifest.json" to "{}".toByteArray(), "README.txt" to "Bundle".toByteArray())
        val zip = ZipBundleWriter.write(entries)
        assertArrayEquals(zip, ZipBundleWriter.write(entries))
        assertTrue(ArtifactFormats.validate(ArtifactFormat.ZIP, zip).valid)
        val found = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { stream ->
            while (true) { val entry = stream.nextEntry ?: break; found[entry.name] = stream.readBytes() }
        }
        assertEquals(entries.keys, found.keys)
        entries.forEach { (name, bytes) -> assertArrayEquals(bytes, found[name]) }
    }
    private fun standardZip(name: String, bytes: ByteArray = "hello".toByteArray()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(name)); it.write(bytes); it.closeEntry() }
        return out.toByteArray()
    }
    @Test fun zipSupportsNormalDeflateAndDescriptors() {
        val r = ArtifactFormats.validate(ArtifactFormat.ZIP, standardZip("folder/a.txt"))
        assertTrue(r.note, r.valid)
    }
    @Test fun zipTraversalNamesRejectedAndNeverExtracted() {
        listOf("../out.txt", "/out.txt", "a\\out.txt", "C:out.txt", "a/../../b", "a//b", ".hidden", "CON.txt").forEach {
            assertFalse(it, ArtifactFormats.validate(ArtifactFormat.ZIP, standardZip(it)).valid)
        }
    }
    @Test fun zipRequiresCompleteDirectoryAndCorrectCrc() {
        val zip = ZipBundleWriter.write(mapOf("a.txt" to "hello".toByteArray()))
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, zip.copyOf(zip.size - 5)).valid)
        val corrupted = zip.copyOf(); corrupted[35] = (corrupted[35].toInt() xor 1).toByte()
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, corrupted).valid)
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, "PK fake".toByteArray()).valid)
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, zip + byteArrayOf(1)).valid)
        val prefix = byteArrayOf(1, 2, 3) + zip
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, prefix).valid)
    }
    @Test fun zipBombDeclaredSizeAndDuplicateCaseRejected() {
        val zipped = standardZip("bomb.txt", ByteArray(8 * 1024 * 1024 + 1))
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, zipped).valid)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            listOf("a.txt", "A.txt").forEach { name -> z.putNextEntry(ZipEntry(name)); z.write(1); z.closeEntry() }
        }
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, out.toByteArray()).valid)
    }
    @Test fun zipSymlinkAndEncryptionRejected() {
        val zipped = ZipBundleWriter.write(mapOf("a.txt" to "hello".toByteArray()))
        fun central(b: ByteArray): Int = (0 until b.size - 3).first { b[it] == 0x50.toByte() && b[it + 1] == 0x4b.toByte() && b[it + 2] == 1.toByte() && b[it + 3] == 2.toByte() }
        val p = central(zipped)
        val link = zipped.copyOf(); link[p + 41] = 0xa0.toByte()
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, link).valid)
        val encrypted = zipped.copyOf(); encrypted[p + 8] = (encrypted[p + 8].toInt() or 1).toByte()
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, encrypted).valid)
    }
}

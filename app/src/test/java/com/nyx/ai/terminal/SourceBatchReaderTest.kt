package com.nyx.ai.terminal

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBatchReaderTest {
    private val path = "app/src/main/java/com/nyx/Greeting.kt"
    private val content = "fun greet() = \"hello\"\n"
    private val request = BatchReadRequest(listOf(path))

    @Test
    fun `valid request has no issues`() {
        assertTrue(SourceBatchReader.validate(request).isEmpty())
    }

    @Test
    fun `validate rejects unsafe paths`() {
        val issues = SourceBatchReader.validate(
            BatchReadRequest(
                listOf(
                    "/absolute/A.kt",
                    "../A.kt",
                    "app/../../A.kt",
                    "A.exe",
                    "bad name.kt",
                    "src/A.kt",
                    "src/A.kt",
                ),
            ),
        )
        assertTrue(issues.any { it.contains("absolute") })
        assertTrue(issues.any { it.contains("unsafe") })
        assertTrue(issues.any { it.contains("extension") })
        assertTrue(issues.any { it.contains("characters") })
        assertTrue(issues.any { it.contains("duplicate") })
    }

    @Test
    fun `validate rejects oversized and malformed requests`() {
        assertTrue(
            SourceBatchReader.validate(BatchReadRequest((1..33).map { "src/File$it.kt" }))
                .any { it.contains("too many") },
        )
        assertTrue(
            SourceBatchReader.validate(BatchReadRequest(listOf("a".repeat(600) + ".kt")))
                .any { it.contains("too long") },
        )
        assertTrue(
            SourceBatchReader.validate(BatchReadRequest(listOf("src/A\u0000.kt")))
                .any { it.contains("NUL") },
        )
        assertTrue(
            SourceBatchReader.validate(BatchReadRequest(emptyList())).any { it.contains("no paths") },
        )
        assertTrue(
            SourceBatchReader.validate(BatchReadRequest(listOf(path), maxFileBytes = 0))
                .any { it.contains("maxFileBytes") },
        )
    }

    @Test
    fun `script emits the marker protocol with safe primitives`() {
        val script = SourceBatchReader.script(BatchReadRequest(listOf(path), maxFileBytes = 100, maxTotalBytes = 300))
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("NYX_MAX_FILE=100"))
        assertTrue(script.contains("NYX_MAX_TOTAL=300"))
        assertTrue(script.contains("NYX_FILE_BEGIN %s %s %s"))
        assertTrue(script.contains("NYX_FILE_END %s"))
        assertTrue(script.contains("sha256sum"))
        assertTrue(script.contains("base64"))
        assertTrue(script.contains("base64 -w0"))
        assertTrue(script.contains("truncated=1"))
        assertTrue(script.contains("nyx_emit '$path'"))
        assertTrue(script.contains("if [ \"\$take\" -le 0 ] && [ \"\$size\" -gt 0 ]; then return 1; fi"))
    }

    @Test
    fun `script rejects an invalid request without running`() {
        val script = SourceBatchReader.script(BatchReadRequest(emptyList()))
        assertTrue(script.contains("NYX_REQUEST_INVALID"))
        assertTrue(script.contains("exit 2"))
        assertFalse(script.contains("nyx_emit"))
    }

    @Test
    fun `parse accepts a well formed block`() {
        val result = success(block(path, content), request)
        assertEquals(1, result.files.size)
        assertEquals(1, result.ipcCalls)
        assertEquals(path, result.files[0].path)
        assertEquals(content, result.files[0].content)
        assertEquals(sha256(content.toByteArray(Charsets.UTF_8)), result.files[0].sha256)
        assertFalse(result.files[0].truncated)
    }

    @Test
    fun `parse returns requested order and handles empty files`() {
        val other = "app/src/main/java/com/nyx/Empty.kt"
        val batch = BatchReadRequest(listOf(path, other))
        val stdout = block(other, "") + block(path, content)
        val result = success(stdout, batch)
        assertEquals(listOf(path, other), result.files.map { it.path })
        assertEquals("", result.files[1].content)
        assertEquals(0, result.files[1].content.length)
    }

    @Test
    fun `parse keeps the truncation flag`() {
        val batch = BatchReadRequest(listOf(path), maxFileBytes = 8)
        val result = success(block(path, "abc", truncated = true), batch)
        assertTrue(result.files[0].truncated)
    }

    @Test
    fun `parse rejects a hash mismatch`() {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val stdout = "NYX_FILE_BEGIN $path ${"0".repeat(64)} ${bytes.size}\n" +
            Base64.getEncoder().encodeToString(bytes) + "\nNYX_FILE_END $path\n"
        assertEquals("hash_mismatch", failure(stdout, request).code)
    }

    @Test
    fun `parse rejects malformed markers and base64`() {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val noEnd = "NYX_FILE_BEGIN $path ${sha256(bytes)} ${bytes.size}\n$encoded\n"
        assertEquals("malformed_output", failure(noEnd, request).code)
        val badBase64 = "NYX_FILE_BEGIN $path ${sha256(bytes)} ${bytes.size}\n!!!!\nNYX_FILE_END $path\n"
        assertEquals("malformed_output", failure(badBase64, request).code)
        val unknown = "NYX_OTHER $path\n"
        assertEquals("malformed_output", failure(unknown, request).code)
        val split = "NYX_FILE_BEGIN $path ${sha256(bytes)} ${bytes.size}\n$encoded\n$encoded\nNYX_FILE_END $path\n"
        assertEquals("malformed_output", failure(split, request).code)
        assertEquals("malformed_output", failure("", request).code)
    }

    @Test
    fun `parse rejects duplicates and unrequested files`() {
        val duplicate = block(path, content) + block(path, content)
        assertEquals("malformed_output", failure(duplicate, request).code)
        val unexpected = block("app/src/main/java/com/nyx/Other.kt", "x")
        assertEquals("unexpected_file", failure(unexpected, request).code)
    }

    @Test
    fun `parse rejects a size mismatch`() {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val stdout = "NYX_FILE_BEGIN $path ${sha256(bytes)} ${bytes.size + 1}\n$encoded\nNYX_FILE_END $path\n"
        assertEquals("malformed_output", failure(stdout, request).code)
    }

    @Test
    fun `parse rejects oversized payloads`() {
        val big = "0123456789"
        val small = BatchReadRequest(listOf(path), maxFileBytes = 4)
        assertEquals("too_large", failure(block(path, big), small).code)
        val total = BatchReadRequest(listOf(path), maxFileBytes = 64, maxTotalBytes = 4)
        assertEquals("too_large", failure(block(path, "0123456789"), total).code)
    }

    @Test
    fun `parse rejects an invalid request`() {
        val invalid = BatchReadRequest(listOf("/absolute/A.kt"))
        assertEquals("invalid_request", failure(block("/absolute/A.kt", "x"), invalid).code)
    }

    private fun block(path: String, content: String, truncated: Boolean = false): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val suffix = if (truncated) " truncated=1" else ""
        return "NYX_FILE_BEGIN $path ${sha256(bytes)} ${bytes.size}$suffix\n$encoded\nNYX_FILE_END $path\n"
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(64)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            hex.append("0123456789abcdef"[value ushr 4]).append("0123456789abcdef"[value and 0x0f])
        }
        return hex.toString()
    }

    private fun success(stdout: String, request: BatchReadRequest): BatchReadResult.Success {
        val result = SourceBatchReader.parse(stdout, request)
        assertTrue("expected success but was $result", result is BatchReadResult.Success)
        return result as BatchReadResult.Success
    }

    private fun failure(stdout: String, request: BatchReadRequest): BatchReadResult.Failure {
        val result = SourceBatchReader.parse(stdout, request)
        assertTrue("expected failure but was $result", result is BatchReadResult.Failure)
        return result as BatchReadResult.Failure
    }
}

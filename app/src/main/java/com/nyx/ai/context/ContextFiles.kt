package com.nyx.ai.context

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

/** Output allocation and decoded reads scale with the caller's character budget, not file size. */
internal object ContextFiles {
    fun readText(file: File, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val output = StringBuilder(minOf(maxChars, 4_096))
        InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(minOf(maxChars, 4_096))
            while (output.length < maxChars) {
                val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - output.length))
                if (count < 0) break
                if (count > 0) output.append(buffer, 0, count)
            }
        }
        return output.toString()
    }

    /** Never hashes a prefix as if it were the complete file. */
    fun sha256(file: File, maxBytes: Long = Long.MAX_VALUE): String? {
        if (maxBytes < 0 || file.length() > maxBytes) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var consumed = 0L
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count.toLong() > maxBytes - consumed) return null
                consumed += count
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    fun sha256(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 255
                append(digits[value ushr 4]).append(digits[value and 15])
            }
        }
    }
}

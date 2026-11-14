package com.nyx.ai.terminal

import com.nyx.ai.development.DevelopmentPaths
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** A bounded request to read several project files in one terminal round trip. */
data class BatchReadRequest(
    val paths: List<String>,
    val maxFileBytes: Int = 48_000,
    val maxTotalBytes: Int = 192_000,
)

/** One decoded file; [sha256] is the digest of the decoded bytes. */
data class BatchReadFile(
    val path: String,
    val sha256: String,
    val content: String,
    val truncated: Boolean = false,
)

/** Outcome of a batch read. [BatchReadResult.Success.ipcCalls] is the terminal calls the batch used. */
sealed interface BatchReadResult {
    data class Success(val files: List<BatchReadFile>, val ipcCalls: Int = 1) : BatchReadResult
    data class Failure(val code: String, val message: String) : BatchReadResult
}

/**
 * Builds and parses a single POSIX `sh` script that reads many files at once.
 *
 * The protocol is line oriented: `NYX_FILE_BEGIN <path> <sha256> <size> [truncated=1]`, one base64
 * line, then `NYX_FILE_END <path>`. Paths are relative, normalized, restricted to a conservative
 * character set and an extension allowlist, so they are safe to embed in a shell script. Parsing
 * rejects anything that was not requested, duplicated, malformed, oversized or hash-mismatched;
 * decoded bytes are always re-hashed locally instead of trusting the script.
 */
object SourceBatchReader {
    /** Maximum number of files in one batch. */
    const val MAX_FILES = 32

    /** Begin marker; the full line prefix includes a trailing space. */
    const val MARKER_BEGIN = "NYX_FILE_BEGIN"

    /** End marker; the full line prefix includes a trailing space. */
    const val MARKER_END = "NYX_FILE_END"

    private const val MAX_PATH_CHARS = 512
    private const val MAX_FILE_BYTES_LIMIT = 2_000_000
    private const val MAX_TOTAL_BYTES_LIMIT = 8_000_000
    private const val BEGIN_PREFIX = MARKER_BEGIN + " "
    private const val END_PREFIX = MARKER_END + " "
    private const val TRUNCATED = "truncated=1"
    private const val HEX_DIGITS = "0123456789abcdefABCDEF"
    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    private val SEGMENT = Regex("[A-Za-z0-9._+@-]+")
    private val ALLOWED_EXTENSIONS = setOf(
        "kt", "kts", "java", "xml", "gradle", "md", "txt", "json", "c", "h", "cpp", "hpp",
        "py", "js", "ts", "rs", "go", "sh", "toml", "yml", "yaml",
    )

    /** Returns human-readable problems with [request]; an empty list means the request is valid. */
    fun validate(request: BatchReadRequest): List<String> {
        val issues = mutableListOf<String>()
        if (request.paths.isEmpty()) issues += "no paths requested"
        if (request.paths.size > MAX_FILES) issues += "too many paths: ${request.paths.size} (max $MAX_FILES)"
        if (request.maxFileBytes !in 1..MAX_FILE_BYTES_LIMIT) {
            issues += "maxFileBytes out of range: ${request.maxFileBytes}"
        }
        if (request.maxTotalBytes !in 1..MAX_TOTAL_BYTES_LIMIT) {
            issues += "maxTotalBytes out of range: ${request.maxTotalBytes}"
        }
        val seen = mutableSetOf<String>()
        for (path in request.paths) {
            if (path.isEmpty()) {
                issues += "empty path"
                continue
            }
            if (path.length > MAX_PATH_CHARS) {
                issues += "path too long: ${path.take(32)}"
                continue
            }
            if ('\u0000' in path) {
                issues += "NUL in path"
                continue
            }
            if (path.startsWith('/') || path.startsWith('~')) {
                issues += "absolute path: $path"
                continue
            }
            val segments = path.split('/')
            if (segments.any { it.isEmpty() }) {
                issues += "path is not normalized: $path"
                continue
            }
            if (segments.any { it == "." || it == ".." }) {
                issues += "unsafe path: $path"
                continue
            }
            if (segments.any { !SEGMENT.matches(it) }) {
                issues += "invalid characters: $path"
                continue
            }
            if (extensionOf(path) !in ALLOWED_EXTENSIONS) {
                issues += "extension not allowed: $path"
                continue
            }
            if (!WorkspaceSourcePaths.isSafeRelative(path)) {
                issues += "private or generated path: $path"
                continue
            }
            if (!seen.add(path)) issues += "duplicate path: $path"
        }
        return issues
    }

    /**
     * Renders one POSIX `sh` script for [request].
     *
     * An invalid request renders a script that writes the first problem to stderr and exits 2.
     * Valid requests print markers, one base64 line per file, truncate a file at `maxFileBytes`,
     * mark it `truncated=1` and stop once `maxTotalBytes` payload has been emitted.
     */
    fun script(request: BatchReadRequest): String {
        val issues = validate(request)
        if (issues.isNotEmpty()) {
            return buildString {
                appendLine("#!/bin/sh")
                appendLine("# invalid NYX batch read request")
                append("printf '%s\\n' ").append(quote("NYX_REQUEST_INVALID " + issues.first()))
                appendLine(" >&2")
                appendLine("exit 2")
            }
        }
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# NYX SourceBatchReader: one deterministic batch read with sha256 and base64.")
            appendLine("set -u")
            append("NYX_MAX_FILE=").appendLine(request.maxFileBytes)
            append("NYX_MAX_TOTAL=").appendLine(request.maxTotalBytes)
            appendLine("NYX_ROOT=\$(pwd -P) || exit 71")
            appendLine("NYX_TMP=\$(umask 077; mktemp \"\$NYX_ROOT/.nyx-batch-XXXXXXXX\") || exit 73")
            appendLine("trap 'rm -f \"\$NYX_TMP\"' 0 1 2 15")
            appendLine("if printf 'eA==' | base64 -d >/dev/null 2>&1; then NYX_B64='base64';")
            appendLine("else NYX_B64='base64 -w0'; fi")
            appendLine("NYX_TOTAL=0")
            appendLine("nyx_emit() {")
            appendLine("  path=\$1")
            appendLine("  budget=\$2")
            appendLine("  [ -f \"\$path\" ] || { printf '# NYX_SKIP %s\\n' \"\$path\" >&2; return 0; }")
            appendLine("  [ ! -L \"\$path\" ] && [ \"\$(realpath -e -- \"\$path\")\" = \"\$NYX_ROOT/\$path\" ] && [ \"\$(stat -c %h -- \"\$path\")\" = 1 ] || return 2")
            appendLine("  [ ! -L \"\$NYX_TMP\" ] && [ \"\$(realpath -e -- \"\$NYX_TMP\")\" = \"\$NYX_TMP\" ] && [ \"\$(stat -c %h -- \"\$NYX_TMP\")\" = 1 ] || return 2")
            appendLine("  before=\$(sha256sum -- \"\$path\" | cut -d' ' -f1) || return 2")
            appendLine("  size=\$(wc -c < \"\$path\" | tr -d ' ')")
            appendLine("  take=\$size")
            appendLine("  [ \"\$take\" -gt \"\$NYX_MAX_FILE\" ] && take=\$NYX_MAX_FILE")
            appendLine("  [ \"\$take\" -gt \"\$budget\" ] && take=\$budget")
            appendLine("  if [ \"\$take\" -le 0 ] && [ \"\$size\" -gt 0 ]; then return 1; fi")
            appendLine("  head -c \"\$take\" \"\$path\" > \"\$NYX_TMP\" || return 1")
            appendLine("  hash=\$(sha256sum \"\$NYX_TMP\" | cut -d' ' -f1)")
            appendLine("  [ ! -L \"\$path\" ] && [ \"\$(realpath -e -- \"\$path\")\" = \"\$NYX_ROOT/\$path\" ] && [ \"\$(sha256sum -- \"\$path\" | cut -d' ' -f1)\" = \"\$before\" ] || return 2")
            appendLine("  [ \"\$take\" -lt \"\$size\" ] || [ \"\$hash\" = \"\$before\" ] || return 2")
            appendLine("  if [ \"\$take\" -lt \"\$size\" ]; then")
            appendLine("    printf 'NYX_FILE_BEGIN %s %s %s truncated=1\\n' \"\$path\" \"\$hash\" \"\$take\"")
            appendLine("  else")
            appendLine("    printf 'NYX_FILE_BEGIN %s %s %s\\n' \"\$path\" \"\$hash\" \"\$take\"")
            appendLine("  fi")
            appendLine("  \$NYX_B64 \"\$NYX_TMP\" | tr -d '\\n'")
            appendLine("  printf '\\n'")
            appendLine("  printf 'NYX_FILE_END %s\\n' \"\$path\"")
            appendLine("  NYX_TOTAL=\$((NYX_TOTAL + take))")
            appendLine("  return 0")
            appendLine("}")
            for (path in request.paths) {
                append("nyx_emit ").append(quote(path))
                appendLine(" \$((NYX_MAX_TOTAL - NYX_TOTAL)) || exit 2")
            }
            appendLine("exit 0")
        }
    }

    /**
     * Parses [stdout] produced by [script] for [request].
     *
     * Files absent from the output are reported by omission (the script skips unreadable paths).
     * Requested files are returned in request order.
     */
    fun parse(stdout: String, request: BatchReadRequest): BatchReadResult {
        val issues = validate(request)
        if (issues.isNotEmpty()) return BatchReadResult.Failure("invalid_request", issues.joinToString("; "))
        if (stdout.isEmpty()) return BatchReadResult.Failure("malformed_output", "no output")
        if (stdout.length > request.maxTotalBytes * 2 + 65_536) {
            return BatchReadResult.Failure("too_large", "output exceeds the batch budget")
        }
        val requested = request.paths.toSet()
        val files = LinkedHashMap<String, BatchReadFile>()
        val lines = stdout.split('\n')
        var index = 0
        var total = 0
        while (index < lines.size) {
            val line = lines[index].removeSuffix("\r")
            index++
            when {
                line.isBlank() || line.startsWith('#') -> Unit
                line.startsWith(BEGIN_PREFIX) -> {
                    val header = parseBegin(line)
                        ?: return BatchReadResult.Failure("malformed_output", "bad begin marker: " + line.take(80))
                    if (header.path !in requested) {
                        return BatchReadResult.Failure("unexpected_file", "file not requested: " + header.path)
                    }
                    if (files.containsKey(header.path)) {
                        return BatchReadResult.Failure("malformed_output", "duplicate path: " + header.path)
                    }
                    if (header.size > request.maxFileBytes) {
                        return BatchReadResult.Failure("too_large", "file exceeds maxFileBytes: " + header.path)
                    }
                    val content = StringBuilder()
                    var contentLines = 0
                    var closed = false
                    while (index < lines.size) {
                        val body = lines[index].removeSuffix("\r")
                        index++
                        if (body.startsWith(END_PREFIX)) {
                            val endPath = body.removePrefix(END_PREFIX).trim()
                            if (endPath != header.path) {
                                return BatchReadResult.Failure("malformed_output", "end marker mismatch: " + endPath)
                            }
                            closed = true
                            break
                        }
                        if (body.startsWith(BEGIN_PREFIX)) {
                            return BatchReadResult.Failure("malformed_output", "nested begin marker")
                        }
                        contentLines++
                        if (contentLines > 1) {
                            return BatchReadResult.Failure("malformed_output", "content must be a single line")
                        }
                        content.append(body.trim())
                    }
                    if (!closed) {
                        return BatchReadResult.Failure("malformed_output", "missing end marker for " + header.path)
                    }
                    val encoded = content.toString()
                    val maxEncoded = ((request.maxFileBytes + 2) / 3) * 4 + 8
                    if (encoded.length > maxEncoded) {
                        return BatchReadResult.Failure("too_large", "encoded content exceeds maxFileBytes")
                    }
                    val bytes = decodeBase64(encoded)
                        ?: return BatchReadResult.Failure("malformed_output", "invalid base64 for " + header.path)
                    if (bytes.size != header.size) {
                        return BatchReadResult.Failure("malformed_output", "size mismatch for " + header.path)
                    }
                    if (bytes.size > request.maxFileBytes) {
                        return BatchReadResult.Failure("too_large", "file exceeds maxFileBytes: " + header.path)
                    }
                    total += bytes.size
                    if (total > request.maxTotalBytes) {
                        return BatchReadResult.Failure("too_large", "payload exceeds maxTotalBytes")
                    }
                    val actual = sha256(bytes)
                    if (!actual.equals(header.sha256, ignoreCase = true)) {
                        return BatchReadResult.Failure("hash_mismatch", "sha256 mismatch for " + header.path)
                    }
                    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                    catch (_: java.nio.charset.CharacterCodingException) { return BatchReadResult.Failure("invalid_text", "invalid UTF-8") }
                    if ('\u0000' in text) return BatchReadResult.Failure("invalid_text", "NUL in source")
                    files[header.path] = BatchReadFile(
                        header.path,
                        actual,
                        text,
                        header.truncated,
                    )
                }
                line.startsWith(END_PREFIX) ->
                    return BatchReadResult.Failure("malformed_output", "end marker without begin")
                else -> return BatchReadResult.Failure("malformed_output", "unexpected output: " + line.take(80))
            }
        }
        return BatchReadResult.Success(request.paths.mapNotNull { files[it] })
    }

    private fun parseBegin(line: String): Header? {
        val parts = line.removePrefix(BEGIN_PREFIX).trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size !in 3..4) return null
        val path = parts[0]
        val hash = parts[1]
        val size = parts[2].toIntOrNull() ?: return null
        if (size < 0 || size > MAX_FILE_BYTES_LIMIT) return null
        if (hash.length != 64 || hash.any { it !in HEX_DIGITS }) return null
        val truncated = when {
            parts.size == 3 -> false
            parts[3] == TRUNCATED -> true
            else -> return null
        }
        return Header(path, hash, size, truncated)
    }

    private fun decodeBase64(value: String): ByteArray? {
        if (value.isEmpty()) return ByteArray(0)
        if (value.any { it !in BASE64_ALPHABET }) return null
        return try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(64)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append("0123456789abcdef"[value ushr 4]).append("0123456789abcdef"[value and 0x0f])
        }
        return out.toString()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class Header(val path: String, val sha256: String, val size: Int, val truncated: Boolean)
}

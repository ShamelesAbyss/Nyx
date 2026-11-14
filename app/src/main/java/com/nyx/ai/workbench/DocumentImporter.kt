package com.nyx.ai.workbench

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

data class ImportedDocument(val record: ImportRecord, val text: String) {
    fun asUntrustedBlock(maxChars: Int = 12_000): String = UntrustedEvidence.wrap(
        "Picker-selected import: ${record.fileName}\nImported at: ${record.importedAt}\nType: ${record.contentType}\nBytes: ${record.bytes}\nSHA-256: ${record.sha256}",
        text, maxChars,
    )
}

/** Only selected streams are accepted; no filesystem, URL download, or directory traversal API. */
class DocumentImporter(private val store: WorkbenchStore, private val maxBytes: Long = 2 * 1024 * 1024) {
    init { require(maxBytes in 1..8_388_608) { "Import byte limit must be between 1 and 8 MiB." } }

    /** Caller must pass the content URI returned by the user's Android OpenDocument picker. */
    suspend fun importSelected(resolver: ContentResolver, uri: Uri): ImportedDocument = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Only a picker-selected content URI may be imported." }
        coroutineContext.ensureActive()
        var name: String? = null
        var declared: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declared = cursor.getLong(sizeIndex)
            }
        }
        val displayName = name ?: throw IOException("Selected provider did not supply a display name; choose a supported text document.")
        coroutineContext.ensureActive()
        importSelectedStream(displayName, resolver.getType(uri), declared) {
            resolver.openInputStream(uri) ?: throw IOException("Selected provider did not supply a readable stream.")
        }
    }

    /** Narrow adapter for an already user-selected stream; never expose this to model tools. */
    suspend fun importSelectedStream(
        name: String,
        contentType: String?,
        declaredBytes: Long? = null,
        openStream: () -> InputStream,
    ): ImportedDocument = withContext(Dispatchers.IO) {
        val format = validateSelection(name, contentType)
        require(declaredBytes == null || declaredBytes in 0..maxBytes) { "Selected file exceeds the import byte limit or has invalid size metadata." }
        coroutineContext.ensureActive()
        val output = ByteArrayOutputStream(minOf(declaredBytes ?: 8_192, 65_536).toInt())
        openStream().use { input ->
            val chunk = ByteArray(8_192)
            while (true) {
                coroutineContext.ensureActive()
                // Read only cap+1 bytes, enough to reject oversized input without unbounded reads.
                val allowed = minOf(chunk.size.toLong(), maxBytes + 1 - output.size()).toInt()
                if (allowed <= 0) throw IOException("Selected file exceeds the import byte limit.")
                val read = input.read(chunk, 0, allowed)
                if (read < 0) break
                if (read == 0) throw IOException("Selected provider returned an invalid zero-length stream read.")
                if (output.size().toLong() + read > maxBytes) throw IOException("Selected file exceeds the import byte limit.")
                output.write(chunk, 0, read)
            }
        }
        coroutineContext.ensureActive()
        val bytes = output.toByteArray()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) { throw IOException("Selected file is not valid UTF-8.", error) }
        require(text.none { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) { "Binary/control content is not a supported text import." }
        val normalized = text.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8)
        ArtifactFormats.requireValid(format, normalized)
        coroutineContext.ensureActive()
        // Preserve the exact selected UTF-8 bytes (including BOM) for checksum/provenance.
        val record = store.importFile(name, bytes, format.mimeType)
        ImportedDocument(record, text.removePrefix("\uFEFF"))
    }

    private fun validateSelection(name: String, contentType: String?): ArtifactFormat {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) && !name.contains("..")) { "Selected filename is not a safe bounded basename." }
        val stem = name.substringBefore('.').uppercase()
        require(stem !in setOf("CON", "PRN", "AUX", "NUL") && !stem.matches(Regex("(?:COM|LPT)[1-9]"))) { "Selected filename is reserved." }
        val extension = name.substringAfterLast('.', "").lowercase()
        val format = when (extension) {
            "txt" -> ArtifactFormat.TEXT
            "md", "markdown" -> ArtifactFormat.MARKDOWN
            "csv" -> ArtifactFormat.CSV
            "json" -> ArtifactFormat.JSON
            "pdf" -> throw IllegalArgumentException("PDF import is unsupported: no verified PDF text extractor is installed. Select text, Markdown, CSV or JSON.")
            else -> throw IllegalArgumentException("Unsupported import; select .txt, .md, .csv or .json UTF-8 text.")
        }
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val matching = when (format) {
            ArtifactFormat.TEXT -> setOf("text/plain")
            ArtifactFormat.MARKDOWN -> setOf("text/markdown", "text/x-markdown", "text/plain")
            ArtifactFormat.CSV -> setOf("text/csv", "application/csv", "text/comma-separated-values", "text/plain")
            ArtifactFormat.JSON -> setOf("application/json", "text/json", "text/plain")
            else -> emptySet()
        }
        require(mime.isEmpty() || mime == "application/octet-stream" || mime in matching) { "Selected file MIME type does not match its supported extension." }
        return format
    }
}

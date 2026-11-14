package com.nyx.ai.workbench

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** The picker/chooser receives only bytes from a full matching registered VERIFIED artifact. */
object AndroidArtifactHandoff {
    const val CACHE_DIRECTORY = "workbench-artifact-handoff"
    data class Selection(val objectiveId: String, val artifact: ArtifactRecord)
    private val cachePolicy = HandoffCachePolicy()

    fun checkedBytes(store: WorkbenchStore, record: ArtifactRecord): ByteArray {
        val bytes = store.readArtifact(record)
        require(bytes.size.toLong() == record.bytes && digest(bytes) == record.sha256) { "Artifact changed before handoff; refresh and select it again." }
        return bytes
    }

    /** URI comes solely from the user's CreateDocument callback, never from a tool or source. */
    fun save(context: Context, uri: Uri, store: WorkbenchStore, record: ArtifactRecord) {
        require(uri.scheme == "content") { "Choose a document destination using Android's save picker." }
        val bytes = checkedBytes(store, record)
        context.contentResolver.openOutputStream(uri, "wt").use { stream ->
            requireNotNull(stream) { "The selected document provider did not open an output stream." }
            // Re-check the immutable byte snapshot immediately before crossing the app boundary.
            require(digest(bytes) == record.sha256) { "Export digest mismatch." }
            stream.write(bytes)
            stream.flush()
        }
    }

    fun shareIntent(context: Context, store: WorkbenchStore, record: ArtifactRecord): Intent {
        val bytes = checkedBytes(store, record)
        val base = File(context.cacheDir, CACHE_DIRECTORY)
        return cachePolicy.snapshot(base, record.fileName, bytes) { file ->
            require(store.verifyArtifact(record)) { "Registered artifact changed during handoff." }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.workbench.artifacts", file)
            Intent(Intent.ACTION_SEND).setType(record.format.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .apply { clipData = ClipData.newRawUri("Verified NYX artifact", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package com.nyx.ai.workbench

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** The picker/chooser receives only bytes from a full matching registered VERIFIED artifact. */
object AndroidArtifactHandoff {
    const val CACHE_DIRECTORY = "workbench-artifact-handoff"
    data class Selection(val objectiveId: String, val artifact: ArtifactRecord)
    private val cachePolicy = HandoffCachePolicy()

    fun checkedBytes(store: WorkbenchStore, record: ArtifactRecord): ByteArray = VerifiedArtifactExport.checkedBytes(store, record)

    /** URI comes solely from the user's CreateDocument callback, never from a tool or source. */
    fun save(context: Context, uri: Uri, store: WorkbenchStore, record: ArtifactRecord) {
        validateDestination(uri)
        VerifiedArtifactExport.write(store, record) {
            context.contentResolver.openOutputStream(uri, "wt")
        }
    }

    internal fun validateDestination(uri: Uri) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Choose a document destination using Android's save picker." }
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
}

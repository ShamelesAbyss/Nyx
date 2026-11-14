package com.nyx.ai.workbench

import java.io.IOException
import java.io.OutputStream

/** Stream completion is not destination readback, atomic replacement or provider durability. */
object VerifiedArtifactExport {
    fun checkedBytes(store: WorkbenchStore, record: ArtifactRecord): ByteArray {
        val bytes = store.readArtifact(record)
        require(bytes.size.toLong() == record.bytes && WorkbenchStore.sha256(bytes) == record.sha256) {
            "Artifact changed before handoff; refresh and select it again."
        }
        return bytes
    }

    /** The trusted caller supplies only the user-selected destination opener, never a model tool. */
    fun write(store: WorkbenchStore, record: ArtifactRecord, openStream: () -> OutputStream?) {
        // Resolve and verify the complete local snapshot BEFORE opening/truncating a destination.
        val bytes = checkedBytes(store, record)
        try {
            openStream().use { stream ->
                if (stream == null) throw IOException("The selected document provider did not open an output stream.")
                stream.write(bytes)
                stream.flush()
            }
        } catch (error: IOException) {
            // Do not delete or restore the selected document: it may hold unrelated user data.
            throw IOException("Export did not complete; the destination may contain partial data. " +
                "The retained Workbench artifact is unchanged. Retry using the save picker.", error)
        }
    }
}

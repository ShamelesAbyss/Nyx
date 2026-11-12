package com.nyx.ai.nativeinference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import com.nyx.ai.network.InferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** id and path are identical canonical absolute paths. Name is for display only. */
data class NativeModel(
    val id: String,
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val architecture: String,
    val trainingContext: Int,
    val importedAt: Long,
    val metadata: GgufMetadata,
    val estimateInfo: NativeMemoryEstimate,
) {
    fun estimate(contextLength: Int): NativeMemoryEstimate = metadata.estimate(sizeBytes, contextLength)
}

class NativeModelRepository(context: Context) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "models").apply {
        if (!isDirectory && !mkdirs()) throw IOException("Cannot create private models directory")
    }.canonicalFile
    init { NativeRuntime.initialize(this.context) }

    /** Call listing on IO; metadata sidecars are bounded, and missing sidecars are recovered by streaming GGUF parsing. */
    fun models(): List<NativeModel> = synchronized(repositoryLock) {
        directory.listFiles().orEmpty().asSequence().filter { it.name.matches(MODEL_FILE) && it.isFile }
            .take(MAX_MODELS).mapNotNull { file ->
                try {
                    val sidecar = File(directory, "${file.name}.json")
                    if (sidecar.isFile && sidecar.length() in 1..MAX_SIDECAR_BYTES) {
                        val saved = JSONObject(sidecar.readText(Charsets.UTF_8))
                        if (saved.getLong("sizeBytes") != file.length() || saved.getInt("schema") != 1) throw IOException("Stale metadata")
                        nativeModel(file, metadataFromJson(saved.getJSONObject("gguf")), saved.getLong("importedAt"))
                    } else {
                        val model = nativeModel(file, GgufMetadataParser.read(file), file.lastModified())
                        saveSidecar(model)
                        model
                    }
                } catch (_: Exception) {
                    // Do not list invalid or tampered files; native load always reparses before mmap.
                    null
                }
            }.sortedByDescending { it.importedAt }.toList()
    }
    fun list(): List<NativeModel> = models()
    fun find(id: String): NativeModel? = models().firstOrNull { it.id == id }

    /** Copies through a 64 KiB buffer. No URI/path tricks, no persistable URI permission needed after import. */
    suspend fun import(uri: Uri, maxBytes: Long = DEFAULT_MAX_IMPORT_BYTES): NativeModel = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Select a content URI through Android's Storage Access Framework" }
        require(maxBytes in 24..GgufMetadataParser.MAX_FILE_BYTES)
        val caller = currentCoroutineContext()
        synchronized(repositoryLock) {
            if (directory.listFiles().orEmpty().count { it.name.matches(MODEL_FILE) } >= MAX_MODELS) throw IOException("At most $MAX_MODELS imported models are supported")
            val declaredSize = declaredSize(uri)
            if (declaredSize != null && declaredSize > maxBytes) throw IOException("Selected GGUF exceeds the import size limit")
            val storageBudget = directory.usableSpace - STORAGE_MARGIN_BYTES
            if (storageBudget < 24 || (declaredSize != null && declaredSize > storageBudget)) throw IOException("Insufficient private storage for model import")
            val budget = minOf(maxBytes, storageBudget)
            val key = UUID.randomUUID().toString()
            val temp = File(directory, ".$key.part")
            val target = File(directory, "$key.gguf")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            caller.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) throw IOException("Document provider returned a zero-length read")
                            if (n.toLong() > budget - copied) throw IOException("GGUF exceeds import or available storage limit")
                            output.write(buffer, 0, n)
                            copied += n
                        }
                        output.fd.sync()
                    }
                } ?: throw IOException("Document provider could not open selected model")
                caller.ensureActive()
                val metadata = GgufMetadataParser.read(temp)
                caller.ensureActive()
                // Same-directory POSIX rename is atomic on app-private storage.
                Os.rename(temp.absolutePath, target.absolutePath)
                val model = nativeModel(target, metadata, System.currentTimeMillis())
                saveSidecar(model)
                syncDirectory()
                model
            } catch (error: Throwable) {
                target.delete()
                File(directory, "${target.name}.json").delete()
                throw error
            } finally { temp.delete() }
        }
    }

    /** Cancels native work, joins the session mutex and unloads this path before unlinking it. */
    suspend fun delete(id: String): Boolean {
        val file = File(id)
        if (!file.isAbsolute) throw IllegalArgumentException("Model ID must be an absolute path")
        val canonical = file.canonicalFile
        if (canonical.parentFile != directory || !canonical.name.matches(MODEL_FILE)) throw IllegalArgumentException("Model is not owned by this repository")
        return NativeRuntime.withUnloadedModel(canonical.path) {
            synchronized(repositoryLock) {
                val removed = !canonical.exists() || canonical.delete()
                if (removed) File(directory, "${canonical.name}.json").delete()
                syncDirectory()
                removed
            }
        }
    }
    fun admission(model: NativeModel, options: InferenceOptions = InferenceOptions()): NativeAdmission =
        NativeRuntime.admission(model.metadata, model.sizeBytes, options.contextLength)

    private fun declaredSize(uri: Uri): Long? = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column).takeIf { it >= 0 } else null
    }
    private fun nativeModel(file: File, metadata: GgufMetadata, importedAt: Long): NativeModel {
        val canonical = file.canonicalFile
        if (canonical.parentFile != directory || canonical.name != file.name) throw IOException("Model path escapes private repository")
        val path = canonical.path
        val context = minOf(4096, metadata.trainingContext).coerceAtLeast(512)
        return NativeModel(path, path, metadata.name?.takeIf { it.isNotBlank() } ?: file.name, file.length(),
            metadata.architecture, metadata.trainingContext, importedAt, metadata, metadata.estimate(file.length(), context))
    }
    private fun saveSidecar(model: NativeModel) {
        val target = File(directory, "${File(model.path).name}.json")
        val temp = File(directory, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            val json = JSONObject().put("schema", 1).put("sizeBytes", model.sizeBytes).put("importedAt", model.importedAt)
                .put("gguf", metadataJson(model.metadata)).toString().toByteArray(Charsets.UTF_8)
            if (json.size > MAX_SIDECAR_BYTES) throw IOException("Persisted metadata exceeds 2 MiB")
            FileOutputStream(temp).use { it.write(json); it.fd.sync() }
            Os.rename(temp.absolutePath, target.absolutePath)
        } finally { temp.delete() }
    }
    private fun syncDirectory() {
        // Durability best effort; atomic visibility is provided by rename even if directory fsync is unavailable.
        runCatching {
            val fd = Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
    private fun metadataJson(m: GgufMetadata): JSONObject = JSONObject().apply {
        put("version", m.version); put("name", m.name); put("architecture", m.architecture)
        put("trainingContext", m.trainingContext); put("tokenizer", m.tokenizer); put("vocabularySize", m.vocabularySize)
        put("chatTemplate", m.chatTemplate); put("tensorCount", m.tensorCount); put("tensorBytes", m.tensorBytes); put("dataOffset", m.dataOffset)
        put("blockCount", m.blockCount); put("embeddingLength", m.embeddingLength); put("attentionHeads", m.attentionHeads)
        put("kvHeads", m.kvHeads); put("keyLength", m.keyLength); put("valueLength", m.valueLength)
    }
    private fun metadataFromJson(j: JSONObject): GgufMetadata {
        fun int(key: String): Int? = if (j.has(key) && !j.isNull(key)) j.getInt(key).also { require(it in 1..1_048_576) } else null
        val context = j.getInt("trainingContext").also { require(it in 1..1_048_576) }
        val architecture = j.getString("architecture").also { require(it.matches(Regex("[a-z0-9_]{1,64}"))) }
        return GgufMetadata(j.getInt("version").also { require(it == 2 || it == 3) }, j.optString("name").takeIf { it.isNotBlank() },
            architecture, context, j.getString("tokenizer"), j.getInt("vocabularySize"),
            j.optString("chatTemplate").takeIf { it.isNotBlank() }, j.getInt("tensorCount"), j.getLong("tensorBytes"), j.getLong("dataOffset"),
            int("blockCount"), int("embeddingLength"), int("attentionHeads"), int("kvHeads"), int("keyLength"), int("valueLength"))
    }
    companion object {
        const val DEFAULT_MAX_IMPORT_BYTES = 8L * 1024 * 1024 * 1024
        private const val STORAGE_MARGIN_BYTES = 64L * 1024 * 1024
        private const val MAX_SIDECAR_BYTES = 2L * 1024 * 1024
        private const val MAX_MODELS = 128
        private val MODEL_FILE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.gguf")
        private val repositoryLock = Any()
    }
}

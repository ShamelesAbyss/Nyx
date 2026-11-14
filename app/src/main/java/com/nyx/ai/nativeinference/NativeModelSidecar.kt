package com.nyx.ai.nativeinference

import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class NativeCachedMetadata(val metadata: GgufMetadata, val importedAt: Long)

/** Optional cache only: malformed/stale sidecars never prevent a strict reparse of owned weights. */
internal object NativeModelSidecar {
    const val MAX_BYTES = 2L * 1024 * 1024

    fun readOrRecover(file: File, sidecar: File, persist: (NativeCachedMetadata) -> Unit): NativeCachedMetadata {
        val cached = try {
            if (!sidecar.isFile || sidecar.length() !in 1..MAX_BYTES) null
            else {
                // Bound actual bytes, not just the pre-read length, if another app-owned writer replaces it.
                val bytes = sidecar.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0 || output.size().toLong() + n > MAX_BYTES) throw IOException("Sidecar exceeds bounded read")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                val saved = JSONObject(String(bytes, Charsets.UTF_8))
                if (saved.getLong("sizeBytes") != file.length() || saved.getInt("schema") != 1) throw IOException("Stale metadata")
                NativeCachedMetadata(decode(saved.getJSONObject("gguf"), file.length()), saved.getLong("importedAt").also { require(it >= 0) })
            }
        } catch (_: Exception) { null }
        if (cached != null) return cached
        // Weight validation failures propagate; never manufacture metadata for invalid/truncated GGUFs.
        val recovered = NativeCachedMetadata(GgufMetadataParser.read(file), file.lastModified())
        persist(recovered)
        return recovered
    }

    fun encode(m: GgufMetadata): JSONObject = JSONObject().apply {
        put("version", m.version); put("name", m.name); put("architecture", m.architecture)
        put("trainingContext", m.trainingContext); put("tokenizer", m.tokenizer); put("vocabularySize", m.vocabularySize)
        put("chatTemplate", m.chatTemplate); put("tensorCount", m.tensorCount); put("tensorBytes", m.tensorBytes); put("dataOffset", m.dataOffset)
        put("blockCount", m.blockCount); put("embeddingLength", m.embeddingLength); put("attentionHeads", m.attentionHeads)
        put("kvHeads", m.kvHeads); put("keyLength", m.keyLength); put("valueLength", m.valueLength)
    }
    private fun decode(j: JSONObject, sizeBytes: Long): GgufMetadata {
        fun int(key: String, max: Int = 1_048_576): Int? = if (j.has(key) && !j.isNull(key)) j.getInt(key).also { require(it in 1..max) } else null
        val context = j.getInt("trainingContext").also { require(it in 1..1_048_576) }
        val architecture = j.getString("architecture").also { require(it.matches(Regex("[a-z0-9_]{1,64}"))) }
        val heads = int("attentionHeads", 4096)
        val kvHeads = int("kvHeads", 4096)
        if (heads != null && kvHeads != null) require(kvHeads <= heads && heads % kvHeads == 0)
        val offset = j.getLong("dataOffset").also { require(it in 24 until sizeBytes) }
        val tensorBytes = j.getLong("tensorBytes").also { require(it in 1..(sizeBytes - offset)) }
        return GgufMetadata(j.getInt("version").also { require(it == 2 || it == 3) }, j.optString("name").takeIf { it.isNotBlank() },
            architecture, context, j.getString("tokenizer").also { require(it.isNotBlank() && it.length <= 4096) },
            j.getInt("vocabularySize").also { require(it in 1..1_000_000) },
            j.optString("chatTemplate").takeIf { it.isNotBlank() }, j.getInt("tensorCount").also { require(it in 1..100_000) }, tensorBytes, offset,
            int("blockCount", 4096), int("embeddingLength"), heads, kvHeads, int("keyLength"), int("valueLength"))
    }
}

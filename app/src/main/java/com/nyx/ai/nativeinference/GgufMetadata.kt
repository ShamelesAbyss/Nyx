package com.nyx.ai.nativeinference

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class InvalidGgufException(message: String) : IOException(message)

data class GgufMetadata(
    val version: Int,
    val name: String?,
    val architecture: String,
    val trainingContext: Int,
    val tokenizer: String,
    val vocabularySize: Int,
    val chatTemplate: String?,
    val tensorCount: Int,
    val tensorBytes: Long,
    val dataOffset: Long,
    val blockCount: Int?,
    val embeddingLength: Int?,
    val attentionHeads: Int?,
    val kvHeads: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
) {
    fun estimate(sizeBytes: Long, contextLength: Int): NativeMemoryEstimate {
        val allocatedContext = nativeAllocationContext(contextLength)
        val conventional = architecture in setOf("llama", "qwen2", "qwen3", "smollm3", "phi3", "stablelm")
        val layers = blockCount
        val heads = attentionHeads
        val kv = kvHeads ?: heads
        val embedding = embeddingLength
        val headDim = if (embedding != null && heads != null && embedding % heads == 0) embedding / heads else null
        val k = keyLength ?: headDim
        val v = valueLength ?: headDim
        val kvBytes = if (conventional && layers != null && kv != null && k != null && v != null) {
            checkedMultiply(checkedMultiply(checkedMultiply(layers.toLong(), allocatedContext.toLong()), kv.toLong()), (k.toLong() + v) * 2)
        } else null
        return NativeMemoryEstimate(
            weightFileBytes = sizeBytes,
            kvCacheBytes = kvBytes,
            computeReserveBytes = 256L * 1024 * 1024,
            contextLength = contextLength,
            allocatedContextLength = allocatedContext,
            explanation = if (kvBytes != null) "Conservative full weight-file residency plus conventional transformer FP16 K/V estimate. KV = layers × allocated context (256-position padding) × KV heads × (key + value head dimensions) × 2 bytes. Usable context remains requested. Includes 256 MiB graph reserve, not a measured peak or a guarantee. mmap page residency varies."
                else "KV layout cannot be qualified from this architecture/metadata; load admission is refused. File size is not Android Java heap usage.",
        )
    }
}

data class NativeMemoryEstimate(
    val weightFileBytes: Long,
    val kvCacheBytes: Long?,
    val computeReserveBytes: Long,
    val contextLength: Int,
    val explanation: String,
    val allocatedContextLength: Int = contextLength,
) {
    val estimatedWorkingSetBytes: Long? get() = kvCacheBytes?.let { checkedAdd(checkedAdd(weightFileBytes, it), computeReserveBytes) }
}

/** Must match app-owned nyx_context.h and the pinned llama.cpp single-sequence allocation. */
internal fun nativeAllocationContext(requested: Int): Int {
    require(requested in 512..32768) { "Native context is bounded to 512..32768" }
    return ((requested + 255) / 256) * 256
}

internal fun checkedMultiply(a: Long, b: Long): Long {
    if (a < 0 || b < 0 || (b != 0L && a > Long.MAX_VALUE / b)) throw InvalidGgufException("GGUF size arithmetic overflow")
    return a * b
}
internal fun checkedAdd(a: Long, b: Long): Long {
    if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) throw InvalidGgufException("GGUF size arithmetic overflow")
    return a + b
}

/** Reads only bounded metadata/directory data; never reads or allocates model weights. Little-endian GGUF v2/v3 only. */
object GgufMetadataParser {
    const val MAX_FILE_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_DIRECTORY_BYTES = 128L * 1024 * 1024
    private const val MAX_ARRAY_ITEMS = 1_000_000L
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_METADATA = 16384L
    private const val MAX_TENSORS = 100000L
    private data class Range(val offset: Long, val size: Long)

    fun read(file: File): GgufMetadata = RandomAccessFile(file, "r").use { input ->
        val r = Reader(input)
        if (r.length < 24 || r.length > MAX_FILE_BYTES) fail("GGUF file size is outside supported bounds")
        if (r.u32() != 0x46554747L) fail("Not a little-endian GGUF file")
        val version = r.u32().toInt()
        if (version != 2 && version != 3) fail("Only GGUF versions 2 and 3 are supported")
        val tensors = r.u64(MAX_TENSORS)
        val count = r.u64(MAX_METADATA)
        if (tensors == 0L) fail("GGUF has no weight tensors")
        val strings = mutableMapOf<String, String>()
        val numbers = mutableMapOf<String, Long>()
        val seen = HashSet<String>()
        var vocabulary = 0
        repeat(count.toInt()) {
            val key = r.string(1024)
            if (key.isBlank() || !seen.add(key)) fail("Blank or duplicate metadata key")
            val type = r.u32().toInt()
            val relevantString = key in setOf("general.name", "general.architecture", "tokenizer.ggml.model", "tokenizer.chat_template")
            val relevantNumber = key == "general.alignment" || key == "split.count" || key.endsWith(".context_length") ||
                key.endsWith(".block_count") || key.endsWith(".embedding_length") || key.endsWith(".attention.head_count") ||
                key.endsWith(".attention.head_count_kv") || key.endsWith(".attention.key_length") || key.endsWith(".attention.value_length")
            if (relevantString && type != 8) fail("Expected a string for $key")
            if (relevantNumber && type !in setOf(0, 1, 2, 3, 4, 5, 10, 11)) fail("Expected an integer for $key")
            when (type) {
                8 -> if (relevantString) strings[key] = r.string(if (key == "tokenizer.chat_template") MAX_STRING_BYTES else 4096) else r.skipString()
                9 -> {
                    val elementType = r.u32().toInt()
                    if (elementType == 9 || elementType !in 0..12) fail("Nested/unknown GGUF array type")
                    val size = r.u64(MAX_ARRAY_ITEMS)
                    if (key == "tokenizer.ggml.tokens") {
                        if (elementType != 8 || size == 0L) fail("Invalid tokenizer vocabulary array")
                        vocabulary = size.toInt()
                    }
                    if (elementType == 8) repeat(size.toInt()) { r.skipString(65536) }
                    else r.skip(checkedMultiply(size, scalarBytes(elementType).toLong()))
                }
                else -> {
                    if (relevantNumber) numbers[key] = r.integer(type)
                    else r.skip(scalarBytes(type).toLong())
                }
            }
            r.checkDirectory()
        }
        val architecture = strings["general.architecture"] ?: fail("Missing model architecture")
        if (!architecture.matches(Regex("[a-z0-9_]{1,64}"))) fail("Invalid architecture")
        val context = positiveInt(numbers["$architecture.context_length"], 1_048_576) ?: fail("Missing training context")
        val tokenizer = strings["tokenizer.ggml.model"]?.takeIf { it.isNotBlank() } ?: fail("Missing tokenizer model")
        if (vocabulary == 0) fail("Missing tokenizer.ggml.tokens")
        if ((numbers["split.count"] ?: 1L) != 1L) fail("Split GGUFs are not supported; import a single-file model")
        val alignment = numbers["general.alignment"] ?: 32L
        if (alignment !in 32L..4096L || alignment and (alignment - 1) != 0L) fail("Invalid GGUF mmap alignment")
        val ranges = ArrayList<Range>(tensors.toInt())
        val names = HashSet<String>()
        var tensorBytes = 0L
        repeat(tensors.toInt()) {
            val name = r.string(256)
            if (name.isBlank() || !names.add(name)) fail("Duplicate or blank tensor name")
            val dimensions = r.u32()
            if (dimensions !in 1..4) fail("Invalid tensor dimension count")
            val dims = LongArray(dimensions.toInt()) { r.u64(1L shl 30).also { if (it == 0L) fail("Zero tensor dimension") } }
            val type = r.u32().toInt()
            val format = formats[type] ?: fail("Unsupported/removed tensor format $type")
            if (dims[0] % format.first != 0L) fail("Quantized row is not block-aligned")
            var bytes = checkedMultiply(dims[0] / format.first, format.second.toLong())
            for (i in 1 until dims.size) bytes = checkedMultiply(bytes, dims[i])
            if (bytes > r.length) fail("Tensor size exceeds file")
            val offset = r.u64(r.length)
            if (offset % alignment != 0L) fail("Tensor offset is not mmap-aligned")
            ranges += Range(offset, bytes)
            tensorBytes = checkedAdd(tensorBytes, bytes)
            r.checkDirectory()
        }
        val dataOffset = checkedAdd(r.position, alignment - 1) / alignment * alignment
        if (dataOffset >= r.length) fail("Missing tensor data section")
        var previousEnd = 0L
        for (range in ranges.sortedBy { it.offset }) {
            if (range.offset < previousEnd) fail("Overlapping tensor ranges")
            previousEnd = checkedAdd(range.offset, range.size)
            if (previousEnd > r.length - dataOffset) fail("Truncated tensor data")
        }
        fun dimension(suffix: String, max: Int = 1_048_576) = positiveInt(numbers["$architecture.$suffix"], max)
        val heads = dimension("attention.head_count", 4096)
        val kvHeads = dimension("attention.head_count_kv", 4096)
        if (heads != null && kvHeads != null && (kvHeads > heads || heads % kvHeads != 0)) fail("Invalid attention head ratio")
        GgufMetadata(version, strings["general.name"], architecture, context, tokenizer, vocabulary,
            strings["tokenizer.chat_template"], tensors.toInt(), tensorBytes, dataOffset,
            dimension("block_count", 4096), dimension("embedding_length"), heads, kvHeads,
            dimension("attention.key_length"), dimension("attention.value_length"))
    }

    private fun positiveInt(value: Long?, max: Int): Int? {
        if (value == null) return null
        if (value !in 1..max.toLong()) fail("Model dimension is outside safe bounds")
        return value.toInt()
    }
    private fun scalarBytes(type: Int): Int = when (type) {
        0, 1, 7 -> 1
        2, 3 -> 2
        4, 5, 6 -> 4
        10, 11, 12 -> 8
        else -> fail("Unknown GGUF value type $type")
    }
    private fun fail(message: String): Nothing = throw InvalidGgufException(message)
    private class Reader(val file: RandomAccessFile) {
        val length = file.length()
        val position get() = file.filePointer
        fun u32(): Long { requireBytes(4); return java.lang.Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL }
        fun u64(max: Long): Long {
            requireBytes(8)
            val value = java.lang.Long.reverseBytes(file.readLong())
            if (value < 0 || value > max) fail("Unsigned GGUF length/count exceeds safe bounds")
            return value
        }
        fun integer(type: Int): Long = when (type) {
            0 -> { requireBytes(1); file.readUnsignedByte().toLong() }
            1 -> { requireBytes(1); file.readByte().toLong() }
            2 -> { requireBytes(2); java.lang.Short.reverseBytes(file.readShort()).toLong() and 0xffffL }
            3 -> { requireBytes(2); java.lang.Short.reverseBytes(file.readShort()).toLong() }
            4 -> u32()
            5 -> u32().toInt().toLong()
            10 -> u64(Long.MAX_VALUE)
            11 -> { requireBytes(8); java.lang.Long.reverseBytes(file.readLong()) }
            else -> fail("Not an integer metadata type")
        }
        fun string(max: Int): String {
            val n = u64(max.toLong()).toInt()
            requireBytes(n.toLong())
            val data = ByteArray(n)
            file.readFully(data)
            val result = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { fail("Invalid UTF-8 in GGUF metadata") }
            if ('\u0000' in result) fail("NUL in GGUF metadata string")
            return result
        }
        fun skipString(max: Int = MAX_STRING_BYTES) = skip(u64(max.toLong()))
        fun skip(n: Long) { requireBytes(n); file.seek(position + n); checkDirectory() }
        fun checkDirectory() { if (position > MAX_DIRECTORY_BYTES) fail("Metadata/directory exceeds 128 MiB") }
        private fun requireBytes(n: Long) {
            if (n < 0 || n > length - position) fail("Truncated GGUF metadata/directory")
            if (n > MAX_DIRECTORY_BYTES - position) fail("Metadata/directory exceeds 128 MiB")
        }
    }
    // GGML block sizes from the pinned upstream gguf-py/gguf/constants.py; removed types fail closed.
    private val formats = mapOf(
        0 to (1 to 4), 1 to (1 to 2), 2 to (32 to 18), 3 to (32 to 20), 6 to (32 to 22), 7 to (32 to 24),
        8 to (32 to 34), 9 to (32 to 36), 10 to (256 to 84), 11 to (256 to 110), 12 to (256 to 144),
        13 to (256 to 176), 14 to (256 to 210), 15 to (256 to 292), 16 to (256 to 66), 17 to (256 to 74),
        18 to (256 to 98), 19 to (256 to 50), 20 to (32 to 18), 21 to (256 to 110), 22 to (256 to 82),
        23 to (256 to 136), 24 to (1 to 1), 25 to (1 to 2), 26 to (1 to 4), 27 to (1 to 8), 28 to (1 to 8),
        29 to (256 to 56), 30 to (1 to 2), 34 to (256 to 54), 35 to (256 to 66), 39 to (32 to 17),
        40 to (64 to 36), 41 to (128 to 18), 42 to (64 to 18), 43 to (1 to 1),
    )
}

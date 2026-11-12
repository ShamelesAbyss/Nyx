package com.nyx.ai.nativeinference

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class GgufMetadataParserTest {
    @Test fun syntheticV2AndV3MetadataAndEstimate() {
        for (version in listOf(2, 3)) withFile(valid(version = version)) { file ->
            val m = GgufMetadataParser.read(file)
            assertEquals(version, m.version)
            assertEquals("Synthetic test", m.name)
            assertEquals("llama", m.architecture)
            assertEquals("gpt2", m.tokenizer)
            assertEquals(2, m.vocabularySize)
            assertEquals(8192, m.trainingContext)
            assertEquals(128L, m.tensorBytes)
            assertEquals("chatml", m.chatTemplate)
            val estimate = m.estimate(file.length(), 512)
            assertEquals(2L * 512 * 1 * (4 + 4) * 2, estimate.kvCacheBytes)
            assertEquals(file.length(), estimate.weightFileBytes)
            assertTrue(estimate.explanation.contains("not a measured peak"))
        }
    }
    @Test fun wrongMagic() { bad(valid().also { it[0] = 0 }) }
    @Test fun unsupportedVersion() { bad(valid(version = 1)) }
    @Test fun truncatedHeader() { bad(ByteArray(23)) }
    @Test fun negativeUnsignedTensorCount() { bad(header(tensors = -1L)) }
    @Test fun hugeMetadataCount() { bad(header(metadata = Long.MAX_VALUE)) }
    @Test fun hugeStringAllocationRefused() {
        bad(header(metadata = 1) + le64(Long.MAX_VALUE))
    }
    @Test fun nestedArrayRefused() {
        bad(header(metadata = 1) + str("bad") + le32(9) + le32(9) + le64(1))
    }
    @Test fun hugeArrayCountRefused() {
        bad(header(metadata = 1) + str("bad") + le32(9) + le32(4) + le64(Long.MAX_VALUE))
    }
    @Test fun duplicateMetadataKeyRefused() { bad(valid(extra = listOf(stringValue("general.architecture", "llama")))) }
    @Test fun missingArchitectureRefused() { bad(valid(architecture = "")) }
    @Test fun invalidArchitectureRefused() { bad(valid(architecture = "../llama")) }
    @Test fun invalidMetadataUtf8Refused() {
        bad(header(metadata = 1) + le64(1) + byteArrayOf(0xff.toByte()))
    }
    @Test fun nulInArchitectureRefused() { bad(valid(architecture = "llama\u0000")) }
    @Test fun fractionalContextRefused() { bad(valid(contextEntry = str("llama.context_length") + le32(6) + le32(8192))) }
    @Test fun negativeContextRefused() { bad(valid(contextEntry = integerValue("llama.context_length", -1))) }
    @Test fun splitModelRefused() { bad(valid(extra = listOf(integerValue("split.count", 2)))) }
    @Test fun invalidMmapAlignmentRefused() { bad(valid(alignment = 33)) }
    @Test fun tensorOverflowRefused() { bad(valid(dims = longArrayOf(1L shl 30, 1L shl 30, 1L shl 30))) }
    @Test fun invalidDimensionCountRefused() { bad(valid(dims = longArrayOf())) }
    @Test fun zeroDimensionRefused() { bad(valid(dims = longArrayOf(0))) }
    @Test fun removedTensorTypeRefused() { bad(valid(type = 4)) }
    @Test fun quantizedRowMisalignmentRefused() { bad(valid(type = 2, dims = longArrayOf(31))) }
    @Test fun tensorOffsetMisalignmentRefused() { bad(valid(offset = 1)) }
    @Test fun tensorBeyondFileRefused() { bad(valid(offset = 4096)) }
    @Test fun truncatedWeightsRefused() { bad(valid().dropLast(1).toByteArray()) }
    @Test fun overlappingTensorsRefused() { bad(valid(secondTensor = true)) }
    @Test fun unknownArchitectureHasUnqualifiedKv() {
        withFile(valid(architecture = "unknown")) {
            assertNull(GgufMetadataParser.read(it).estimate(it.length(), 512).kvCacheBytes)
        }
    }
    @Test fun noChatTemplateImportsButExplicitlyReportsMissing() {
        withFile(valid(includeTemplate = false)) { assertNull(GgufMetadataParser.read(it).chatTemplate) }
    }
    @Test fun rejectsSparseHugeFileWithoutReadingWeights() {
        val file = File.createTempFile("nyx-gguf", ".gguf")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(GgufMetadataParser.MAX_FILE_BYTES + 1) }
            assertThrows(InvalidGgufException::class.java) { GgufMetadataParser.read(file) }
        } finally { file.delete() }
    }
    @Test fun arithmeticOverflowRefused() {
        assertThrows(InvalidGgufException::class.java) { checkedMultiply(Long.MAX_VALUE, 2) }
        assertThrows(InvalidGgufException::class.java) { checkedAdd(Long.MAX_VALUE, 1) }
    }

    private fun bad(bytes: ByteArray) = withFile(bytes) { assertThrows(InvalidGgufException::class.java) { GgufMetadataParser.read(it) } }
    private fun withFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("nyx-gguf", ".gguf")
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
    private fun valid(version: Int = 3, architecture: String = "llama", alignment: Int = 32,
                      type: Int = 0, dims: LongArray = longArrayOf(32), offset: Long = 0,
                      extra: List<ByteArray> = emptyList(), contextEntry: ByteArray? = null,
                      secondTensor: Boolean = false, includeTemplate: Boolean = true): ByteArray {
        val entries = mutableListOf(stringValue("general.name", "Synthetic test"), stringValue("general.architecture", architecture),
            contextEntry ?: integerValue("$architecture.context_length", 8192), stringValue("tokenizer.ggml.model", "gpt2"),
            str("tokenizer.ggml.tokens") + le32(9) + le32(8) + le64(2) + str("a") + str("b"),
            integerValue("general.alignment", alignment), integerValue("$architecture.block_count", 2),
            integerValue("$architecture.embedding_length", 8), integerValue("$architecture.attention.head_count", 2),
            integerValue("$architecture.attention.head_count_kv", 1))
        if (includeTemplate) entries += stringValue("tokenizer.chat_template", "chatml")
        entries += extra
        val out = ByteArrayOutputStream()
        out.write(header(version, if (secondTensor) 2 else 1, entries.size.toLong()))
        entries.forEach(out::write)
        fun tensor(name: String) {
            out.write(str(name)); out.write(le32(dims.size)); dims.forEach { out.write(le64(it)) }
            out.write(le32(type)); out.write(le64(offset))
        }
        tensor("token_embd.weight")
        if (secondTensor) tensor("output.weight")
        while (out.size() % 32 != 0) out.write(0)
        out.write(ByteArray(128))
        return out.toByteArray()
    }
    private fun header(version: Int = 3, tensors: Long = 1, metadata: Long = 0) = le32(0x46554747) + le32(version) + le64(tensors) + le64(metadata)
    private fun stringValue(key: String, value: String) = str(key) + le32(8) + str(value)
    private fun integerValue(key: String, value: Int) = str(key) + le32(5) + le32(value)
    private fun str(value: String): ByteArray = value.toByteArray(Charsets.UTF_8).let { le64(it.size.toLong()) + it }
    private fun le32(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun le64(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
}

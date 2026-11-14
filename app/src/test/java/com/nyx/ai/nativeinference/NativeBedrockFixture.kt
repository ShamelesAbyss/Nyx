package com.nyx.ai.nativeinference

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** Metadata-only synthetic format fixture. It cannot execute real llama.cpp inference. */
internal object NativeBedrockFixture {
    fun bytes(trainingContext: Int = 8192): ByteArray {
        fun u32(n: Int) = ByteArray(4) { (n ushr (it * 8)).toByte() }
        fun u64(n: Long) = ByteArray(8) { (n ushr (it * 8)).toByte() }
        fun string(s: String): ByteArray = s.toByteArray(Charsets.UTF_8).let { u64(it.size.toLong()) + it }
        fun text(key: String, value: String) = string(key) + u32(8) + string(value)
        fun number(key: String, value: Int) = string(key) + u32(4) + u32(value)
        val entries = listOf(text("general.name", "Bedrock synthetic fixture"), text("general.architecture", "llama"),
            number("llama.context_length", trainingContext), text("tokenizer.ggml.model", "gpt2"), text("tokenizer.chat_template", "chatml"),
            string("tokenizer.ggml.tokens") + u32(9) + u32(8) + u64(2) + string("a") + string("b"),
            number("llama.block_count", 2), number("llama.embedding_length", 8),
            number("llama.attention.head_count", 2), number("llama.attention.head_count_kv", 1))
        val out = ByteArrayOutputStream()
        out.write(u32(0x46554747) + u32(3) + u64(1) + u64(entries.size.toLong()))
        entries.forEach(out::write)
        out.write(string("token_embd.weight") + u32(1) + u64(32) + u32(0) + u64(0))
        while (out.size() % 32 != 0) out.write(0)
        out.write(ByteArray(128))
        return out.toByteArray()
    }
    fun <T> directory(block: (File) -> T): T {
        val directory = Files.createTempDirectory("nyx-bedrock-native-test-").toFile()
        return try { block(directory) } finally { directory.deleteRecursively() }
    }
    fun model(directory: File, name: String = "fixture.gguf", trainingContext: Int = 8192): File =
        File(directory, name).apply { writeBytes(bytes(trainingContext)) }.canonicalFile
}

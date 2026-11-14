package com.nyx.ai.nativeinference

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Android repository integration with synthetic metadata only; never a real inference test. */
@RunWith(AndroidJUnit4::class)
class NativeBedrockRepositoryRecoveryTest {
    @Test fun corruptSidecarsRecoverWithoutChangingValidWeights() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "bedrock-native-recovery-${UUID.randomUUID()}").apply { check(mkdir()) }
        val fixtureContext = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        val directory = File(root, "models").apply { check(mkdir()) }
        val file = File(directory, "${UUID.randomUUID()}.gguf").apply { writeBytes(synthetic()) }
        val sidecar = File(directory, "${file.name}.json")
        fun digest() = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        val originalDigest = digest()
        try {
            val first = NativeModelRepository(fixtureContext).find(file.canonicalPath)!!
            val valid = sidecar.readText()
            val corruption = listOf("{broken", JSONObject(valid).put("schema", 99).toString(),
                JSONObject(valid).put("sizeBytes", file.length() + 1).toString(),
                JSONObject(valid).apply { getJSONObject("gguf").put("blockCount", 0) }.toString())
            for (bad in corruption) {
                sidecar.writeText(bad)
                val recovered = NativeModelRepository(fixtureContext).find(file.canonicalPath)
                assertNotNull(recovered)
                assertEquals(first.metadata, recovered!!.metadata)
                assertEquals(originalDigest, digest())
                assertEquals(1, JSONObject(sidecar.readText()).getInt("schema"))
                assertNotNull(NativeModelRepository(fixtureContext).find(file.canonicalPath))
            }
            // Corrupt weights themselves remain invalid; sidecar recovery must not invent a model.
            file.writeBytes(synthetic().dropLast(1).toByteArray())
            sidecar.writeText("{broken")
            assertNull(NativeModelRepository(fixtureContext).find(file.canonicalPath))
            assertTrue(NativeModelRepository(fixtureContext).delete(file.canonicalPath))
            assertFalse(file.exists())
            assertFalse(sidecar.exists())
        } finally {
            NativeRuntime.initialize(base)
            root.deleteRecursively() // Only this fixture-owned cache directory.
        }
    }
    private fun synthetic(): ByteArray {
        fun u32(n: Int) = ByteArray(4) { (n ushr (it * 8)).toByte() }
        fun u64(n: Long) = ByteArray(8) { (n ushr (it * 8)).toByte() }
        fun text(s: String): ByteArray = s.toByteArray().let { u64(it.size.toLong()) + it }
        fun string(key: String, value: String) = text(key) + u32(8) + text(value)
        fun number(key: String, value: Int) = text(key) + u32(4) + u32(value)
        val entries = listOf(string("general.name", "Bedrock Android metadata fixture"), string("general.architecture", "llama"),
            number("llama.context_length", 8192), string("tokenizer.ggml.model", "gpt2"), string("tokenizer.chat_template", "chatml"),
            text("tokenizer.ggml.tokens") + u32(9) + u32(8) + u64(2) + text("a") + text("b"),
            number("llama.block_count", 2), number("llama.embedding_length", 8),
            number("llama.attention.head_count", 2), number("llama.attention.head_count_kv", 1))
        val out = ByteArrayOutputStream()
        out.write(u32(0x46554747) + u32(3) + u64(1) + u64(entries.size.toLong()))
        entries.forEach(out::write)
        out.write(text("token_embd.weight") + u32(1) + u64(32) + u32(0) + u64(0))
        while (out.size() % 32 != 0) out.write(0)
        out.write(ByteArray(128))
        return out.toByteArray()
    }
}

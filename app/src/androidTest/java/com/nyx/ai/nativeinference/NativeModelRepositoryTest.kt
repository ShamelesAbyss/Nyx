package com.nyx.ai.nativeinference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Repository-only synthetic format fixture. This test does NOT claim real model inference. */
@RunWith(AndroidJUnit4::class)
class NativeModelRepositoryTest {
    @Test fun diskMetadataSurvivesRepositoryRecreationAndDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "models").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.gguf")
        file.writeBytes(synthetic())
        val id = file.canonicalPath
        try {
            val first = NativeModelRepository(context).find(id)
            assertNotNull(first)
            assertEquals(id, first!!.id)
            assertEquals(id, first.path)
            assertEquals("Repository fixture", first.name)
            assertEquals("llama", first.architecture)
            assertEquals(8192, first.trainingContext)
            val sidecar = File(directory, "${file.name}.json")
            assertTrue("Recovery must atomically persist metadata", sidecar.isFile)
            val recreated = NativeModelRepository(context).find(id)
            assertEquals(first, recreated)
            assertTrue(NativeModelRepository(context).delete(id))
            assertFalse(file.exists())
            assertFalse(sidecar.exists())
            assertNull(NativeModelRepository(context).find(id))
        } finally {
            file.delete()
            File(directory, "${file.name}.json").delete()
        }
    }
    @Test fun deletionRefusesOutsidePrivateRepository() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outside = File(context.filesDir, "outside-native-test.gguf")
        outside.writeText("not a model")
        try {
            var refused = false
            try { NativeModelRepository(context).delete(outside.path) } catch (_: IllegalArgumentException) { refused = true }
            assertTrue(refused)
            assertTrue(outside.exists())
        } finally { outside.delete() }
    }
    private fun synthetic(): ByteArray {
        fun u32(n: Int) = ByteArray(4) { (n ushr (it * 8)).toByte() }
        fun u64(n: Long) = ByteArray(8) { (n ushr (it * 8)).toByte() }
        fun string(s: String): ByteArray = s.toByteArray(Charsets.UTF_8).let { u64(it.size.toLong()) + it }
        fun text(key: String, value: String) = string(key) + u32(8) + string(value)
        fun number(key: String, value: Int) = string(key) + u32(4) + u32(value)
        val entries = listOf(text("general.name", "Repository fixture"), text("general.architecture", "llama"),
            number("llama.context_length", 8192), text("tokenizer.ggml.model", "gpt2"), text("tokenizer.chat_template", "chatml"),
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
}

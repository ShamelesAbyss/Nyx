package com.nyx.ai.nativeinference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.InferenceOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No bundled weights. No modelPath argument => JUnit assumption skip, never a fake inference pass. */
@RunWith(AndroidJUnit4::class)
class NativeRealModelTest {
    @Test fun realModelLoadStreamCancelReuseAndUnload() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("modelPath")
        assumeTrue("SKIPPED real native inference: supply instrumentation -e modelPath /absolute/readable/instruct.gguf", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        assertTrue("Explicit modelPath must exist and be readable by target app", file.isAbsolute && file.isFile && file.canRead())
        NativeRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val options = InferenceOptions(temperature = 0.0, contextLength = 512, cpuThreads = 2)
        val messages = listOf(ApiMessage("system", "You are a helpful assistant."), ApiMessage("user", "What is the capital of France?"))
        try {
            withTimeout(180_000) {
                val loaded = NativeRuntime.load(file.path, options)
                assertEquals(NativePhase.READY, loaded.phase)
                val stream = StringBuilder()
                val output = NativeRuntime.generate(file.path, messages, options, NativeGenerationOptions(32, 42)) { stream.append(it) }
                assertTrue("A supplied real model must generate nonempty tokens", output.isNotBlank())
                assertEquals(output, stream.toString())
                val first = CompletableDeferred<Unit>()
                val request = async {
                    NativeRuntime.generate(file.path, messages, options, NativeGenerationOptions(512, 42)) {
                        first.complete(Unit)
                        NativeRuntime.cancel()
                    }
                }
                first.await()
                var cancelled = false
                try { request.await() } catch (_: CancellationException) { cancelled = true }
                assertTrue("Atomic native cancellation must propagate", cancelled)
                assertEquals(NativePhase.READY, NativeRuntime.status.value.phase)
                val replay = NativeRuntime.generate(file.path, messages, options, NativeGenerationOptions(32, 42))
                assertEquals("Cancellation must not pollute reused KV memory", output, replay)
                val coroutineFirst = CompletableDeferred<Unit>()
                val coroutineRequest = async {
                    NativeRuntime.generate(file.path, messages, options, NativeGenerationOptions(4096, 42)) { coroutineFirst.complete(Unit) }
                }
                coroutineFirst.await()
                coroutineRequest.cancelAndJoin()
                assertTrue(coroutineRequest.isCancelled)
                assertEquals(NativePhase.READY, NativeRuntime.status.value.phase)
                assertEquals(output, NativeRuntime.generate(file.path, messages, options, NativeGenerationOptions(32, 42)))
            }
        } finally {
            NativeRuntime.unload()
            NativeRuntime.unload()
            assertEquals(NativePhase.UNLOADED, NativeRuntime.status.value.phase)
        }
    }
}

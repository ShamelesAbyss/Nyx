package com.nyx.ai.nativeinference

import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.InferenceOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Fake backend + synthetic metadata only. These tests do not execute llama.cpp or Ollama. */
class NativeSessionRuntimeTest {
    @Test(timeout = 15000) fun deletingUnusedModelDoesNotCancelOrUnloadActiveModel() = NativeBedrockFixture.directory { directory -> runBlocking {
        val a = NativeBedrockFixture.model(directory, "a.gguf")
        val b = NativeBedrockFixture.model(directory, "b.gguf")
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val gate = Gate()
        backend.generationAction = { gate.enterAndWait(); "fake A" }
        val generation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { generate(runtime, a) }
        gate.awaitEntered()
        try {
            // UNDISPATCHED + matching IO context executes the delete entry through mutex suspension now.
            val deletion = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                runtime.withUnloadedModel(b.path) { backend.events += "unlink:b"; b.delete() }
            }
            assertFalse(deletion.isCompleted)
            assertTrue(b.exists())
            assertEquals(0, backend.cancelCalls.get())
            assertEquals(0, backend.unloadCalls.get())
            gate.release.countDown()
            assertEquals("fake A", generation.await())
            assertTrue(deletion.await())
            assertFalse(b.exists())
            assertEquals(a.path, runtime.status.value.modelPath)
            assertEquals(0, backend.cancelCalls.get())
        } finally { gate.release.countDown(); runtime.unload() }
    } }

    @Test(timeout = 15000) fun deletingActiveModelWaitsThenUnloadsBeforeUnlink() = NativeBedrockFixture.directory { directory -> runBlocking {
        val a = NativeBedrockFixture.model(directory, "a.gguf")
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val gate = Gate()
        backend.generationAction = { gate.enterAndWait(); "fake A" }
        val generation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { generate(runtime, a) }
        gate.awaitEntered()
        try {
            val deletion = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                runtime.withUnloadedModel(a.path) {
                    assertTrue(backend.unloadCalls.get() > 0)
                    backend.events += "unlink:a"
                    a.delete()
                }
            }
            assertFalse(deletion.isCompleted)
            assertEquals(0, backend.cancelCalls.get())
            assertTrue(a.exists())
            gate.release.countDown()
            assertEquals("fake A", generation.await())
            assertTrue(deletion.await())
            assertEquals(NativePhase.UNLOADED, runtime.status.value.phase)
            assertTrue(backend.events.indexOf("unload") < backend.events.indexOf("unlink:a"))
            assertEquals(0, backend.cancelCalls.get())
        } finally { gate.release.countDown(); runtime.unload() }
    } }

    @Test(timeout = 15000) fun queuedModelSwitchCannotBeCancelledByDeletionOfUnusedModel() = NativeBedrockFixture.directory { directory -> runBlocking {
        val a = NativeBedrockFixture.model(directory, "a.gguf")
        val b = NativeBedrockFixture.model(directory, "b.gguf")
        val c = NativeBedrockFixture.model(directory, "c.gguf")
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val gate = Gate()
        backend.generationAction = { gate.enterAndWait(); "fake" }
        val first = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { generate(runtime, a) }
        gate.awaitEntered()
        try {
            val deletion = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runtime.withUnloadedModel(b.path) { b.delete() } }
            val switch = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { generate(runtime, c) }
            assertFalse(deletion.isCompleted)
            assertFalse(switch.isCompleted)
            assertEquals(0, backend.cancelCalls.get())
            gate.release.countDown()
            assertEquals("fake", first.await())
            assertTrue(deletion.await())
            assertEquals("fake", switch.await())
            assertEquals(c.path, runtime.status.value.modelPath)
            assertEquals(0, backend.cancelCalls.get())
        } finally { gate.release.countDown(); runtime.unload() }
    } }

    @Test(timeout = 15000) fun stopAtFinalLoadReturnCannotBecomeANewGenerationEpoch() = NativeBedrockFixture.directory { directory -> runBlocking {
        val file = NativeBedrockFixture.model(directory)
        val backend = FakeBackend()
        val runtime = runtime(backend)
        // Native load's final cancellation check has passed. Cancel immediately before return/resume.
        backend.afterLoad = { runtime.cancel() }
        expectCancellation { generate(runtime, file) }
        assertEquals(0, backend.generateCalls.get())
        assertTrue(backend.unloadCalls.get() > 0)
        assertEquals(NativePhase.UNLOADED, runtime.status.value.phase)
        assertNull(runtime.currentOptions)
        backend.afterLoad = null
        assertEquals("fake", generate(runtime, file))
        runtime.unload()
    } }

    @Test(timeout = 15000) fun cachedModelSetupStopIsNotErasedByBeginOperation() = NativeBedrockFixture.directory { directory -> runBlocking {
        val file = NativeBedrockFixture.model(directory)
        val backend = FakeBackend()
        val runtime = runtime(backend)
        runtime.load(file.path, options)
        backend.afterBegin = { runtime.cancel() }
        expectCancellation { generate(runtime, file) }
        assertEquals(0, backend.generateCalls.get())
        assertEquals(NativePhase.READY, runtime.status.value.phase)
        backend.afterBegin = null
        assertEquals("fake", generate(runtime, file))
        runtime.unload()
    } }

    @Test(timeout = 15000) fun coroutineCancellationJoinsUncooperativeNativeWorkerBeforeDelete() = NativeBedrockFixture.directory { directory -> runBlocking {
        val file = NativeBedrockFixture.model(directory)
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val gate = Gate()
        backend.generationAction = { gate.enterAndWait(); "fake" }
        val generation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { generate(runtime, file) }
        gate.awaitEntered()
        try {
            generation.cancel()
            val deletion = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                runtime.withUnloadedModel(file.path) { backend.events += "unlink"; file.delete() }
            }
            assertFalse(generation.isCompleted)
            assertFalse(deletion.isCompleted)
            assertTrue(file.exists())
            assertEquals(0, backend.unloadCalls.get())
            gate.release.countDown()
            generation.join()
            deletion.join()
            assertTrue(backend.events.indexOf("generate:end") < backend.events.indexOf("unload"))
            assertFalse(file.exists())
            // Fresh independent work must not inherit the previous coroutine cancellation.
            val next = NativeBedrockFixture.model(directory, "next.gguf")
            backend.generationAction = null
            assertEquals("fake", generate(runtime, next))
        } finally { gate.release.countDown(); runtime.unload() }
    } }

    @Test(timeout = 15000) fun nativeAllocationMismatchFreesResidentModelAndFailsClosed() = NativeBedrockFixture.directory { directory -> runBlocking {
        val file = NativeBedrockFixture.model(directory)
        val backend = FakeBackend().apply { wrongAllocation = true }
        val runtime = runtime(backend)
        var rejected = false
        try { runtime.load(file.path, options) } catch (e: NativeAdmissionException) { rejected = e.message!!.contains("expected=768 actual=513") }
        assertTrue(rejected)
        assertTrue(backend.unloadCalls.get() > 0)
        assertFalse(runtime.status.value.loaded)
        assertNull(runtime.currentOptions)
        runtime.unload()
    } }

    @Test(timeout = 15000) fun usableAndAllocatedStatusRemainDistinctAcrossCacheReuse() = NativeBedrockFixture.directory { directory -> runBlocking {
        val file = NativeBedrockFixture.model(directory, trainingContext = 513)
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val status = runtime.load(file.path, options)
        assertEquals(513, status.contextLength)
        assertEquals(768, status.allocatedContextLength)
        assertEquals(513, status.estimate!!.contextLength)
        assertEquals(768, status.estimate.allocatedContextLength)
        assertEquals("fake", generate(runtime, file))
        assertEquals(1, backend.loadCalls.get())
        var rejected = false
        try { runtime.load(file.path, options.copy(contextLength = 514)) } catch (_: NativeAdmissionException) { rejected = true }
        assertTrue(rejected)
        assertEquals(1, backend.loadCalls.get())
        runtime.unload()
    } }

    @Test(timeout = 15000) fun explicitStopFromTokenCallbackPropagatesAndNextRequestWorks() = NativeBedrockFixture.directory { directory -> runBlocking {
        val file = NativeBedrockFixture.model(directory)
        val backend = FakeBackend()
        val runtime = runtime(backend)
        expectCancellation { generate(runtime, file) { runtime.cancel() } }
        assertNull(runtime.status.value.generatedTokens)
        assertEquals("fake", generate(runtime, file))
        runtime.unload()
    } }

    private val options = InferenceOptions(contextLength = 513)
    private fun runtime(backend: FakeBackend) = NativeSessionRuntime({ metadata, size, context ->
        nativeAdmission(metadata, size, context, 8L * 1024 * 1024 * 1024, 0, false, 512)
    }, backend)
    private suspend fun generate(runtime: NativeSessionRuntime, file: File, token: (String) -> Unit = {}): String =
        runtime.generate(file.path, listOf(ApiMessage("user", "fixture")), options, NativeGenerationOptions(), token)
    private suspend fun expectCancellation(block: suspend () -> Unit) {
        var cancelled = false
        try { block() } catch (_: CancellationException) { cancelled = true }
        assertTrue("Expected explicit request cancellation", cancelled)
    }
    private class Gate {
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun enterAndWait() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) { "test gate timed out" } }
        fun awaitEntered() { check(entered.await(5, TimeUnit.SECONDS)) { "native worker did not enter test gate" } }
    }
    private class FakeBackend : NativeBackend {
        val cancelCalls = AtomicInteger()
        val unloadCalls = AtomicInteger()
        val loadCalls = AtomicInteger()
        val generateCalls = AtomicInteger()
        private val cancelled = AtomicBoolean()
        val events = CopyOnWriteArrayList<String>()
        @Volatile var afterLoad: (() -> Unit)? = null
        @Volatile var afterBegin: (() -> Unit)? = null
        @Volatile var generationAction: (() -> String)? = null
        var wrongAllocation = false
        override fun ensureLibrary() { }
        override fun beginOperation() { cancelled.set(false); afterBegin?.invoke() }
        override fun cancel() { cancelCalls.incrementAndGet(); cancelled.set(true) }
        override fun load(path: ByteArray, context: Int, threads: Int): Int {
            loadCalls.incrementAndGet()
            events += "load:${String(path)}"
            afterLoad?.invoke()
            return if (wrongAllocation) context else nativeAllocationContext(context)
        }
        override fun unload() { unloadCalls.incrementAndGet(); events += "unload" }
        override fun generate(roles: Array<ByteArray>, contents: Array<ByteArray>, temperature: Float, maxTokens: Int, seed: Int, sink: NativeTokenSink): Int {
            generateCalls.incrementAndGet()
            events += "generate:start"
            try {
                val text = generationAction?.invoke() ?: "fake"
                if (cancelled.get()) throw CancellationException("fake cooperative cancellation")
                sink.onBytes(text.toByteArray())
                return 1
            } finally { events += "generate:end" }
        }
    }
}

package com.nyx.ai.service

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceOperationSlotBedrockTest {
    @Test fun cancelledRunKeepsAdmissionThroughGatedNonCancellableCleanup() = runBlocking {
        val slot = ServiceOperationSlot<Any>()
        val a = Any(); val b = Any()
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stoppedId = -1
        slot.attachStart(1); assertTrue(slot.admit(a))
        val job = launch(start = CoroutineStart.ATOMIC) {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await(); slot.finish(a) { stoppedId = it } } }
        }
        entered.await(); job.cancel(); cleanup.await()
        assertFalse(job.isActive)
        slot.attachStart(2)
        assertFalse("isActive false must not admit a replacement", slot.admit(b))
        assertSame(a, slot.current())
        release.complete(Unit); job.join()
        assertEquals(2, stoppedId)
        assertTrue(slot.admit(b))
        assertFalse(slot.finish(a) { fail("Stale finalizer") })
        assertSame(b, slot.current())
    }

    @Test fun duplicateStartsAttachLatestIdAndStaleNotifiersCannotTouchReplacement() {
        val slot = ServiceOperationSlot<Any>(); val a = Any(); val b = Any()
        slot.attachStart(10); assertTrue(slot.admit(a)); slot.attachStart(11)
        var stopped = -1
        assertTrue(slot.finish(a) { stopped = it }); assertEquals(11, stopped)
        assertTrue(slot.admit(a)); assertSame(a, slot.replace(b))
        assertFalse(slot.withOwner(a) { fail("Stale notification") })
        assertFalse(slot.finish(a) { fail("Stale foreground removal") })
        assertSame(b, slot.current())
    }

    @Test fun failedCleanupStillClearsOnlyItsOwnGeneration() {
        val slot = ServiceOperationSlot<Any>(); val a = Any()
        assertTrue(slot.admit(a))
        try { slot.finish(a) { error("Injected notification failure") }; fail("Failure expected") }
        catch (_: IllegalStateException) { }
        assertNull(slot.current())
    }

    @Test fun commandAdmissionAndStartIdAttachmentAreAtomicWithFinalizer() {
        val slot = ServiceOperationSlot<Any>(); val a = Any(); val b = Any()
        val attempted = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        slot.attachStart(1); assertTrue(slot.admit(a))
        var stoppedId = -1
        lateinit var finalizer: Thread
        synchronized(slot) {
            slot.attachStart(2)
            finalizer = Thread {
                attempted.countDown()
                slot.finish(a) { stoppedId = it }
                finished.countDown()
            }.also { it.start() }
            assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1L, finished.count)
            assertSame(a, slot.current()); assertFalse(slot.admit(b))
        }
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)); finalizer.join()
        assertEquals(2, stoppedId); assertNull(slot.current())
        slot.attachStart(3); assertTrue(slot.admit(b))
    }

    @Test fun cancellationBeforeFirstDispatchStillRunsAtomicFinalizer() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch(start = CoroutineStart.ATOMIC) {
            try { gate.await() } finally { cleaned = true }
        }
        job.cancel(); job.join(); assertTrue(cleaned)
    }

    @Test fun cancelledMiddleGenerationDrainsPredecessorBeforeNextSideEffects() = runBlocking {
        val cleanup = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var sideEffects = 0
        val a = launch(start = CoroutineStart.ATOMIC) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
        }
        a.cancel(); cleanup.await()
        val b = launch(start = CoroutineStart.ATOMIC) {
            try { a.join(); sideEffects++ }
            finally { withContext(NonCancellable) { a.join() } }
        }
        b.cancel()
        val c = launch(start = CoroutineStart.ATOMIC) { b.join(); sideEffects++ }
        yield(); assertEquals(0, sideEffects); assertFalse(c.isCompleted)
        release.complete(Unit); joinAll(a, b, c)
        assertEquals(1, sideEffects)
    }
}

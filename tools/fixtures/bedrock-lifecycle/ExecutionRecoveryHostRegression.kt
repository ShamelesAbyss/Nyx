package com.nyx.ai.autonomy

import com.nyx.ai.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionRecoveryHostRegression {
    private fun fixture(expires: Long? = 100): NyxDatabase = NyxDatabase().also { db ->
        db.dao.rows["a"] = ObjectiveEntity("a", "running", "killed-owner", expires)
        db.dao.tasks["t"] = ObjectiveTaskEntity("t", "a", "running")
    }

    @Test fun promptRestartRecoversInitiallyLiveLeaseAtExpiryWithoutDispatchOrReplay() = runBlocking {
        val db = fixture(); var now = 99L; var waited = 0
        assertEquals(0, ExecutionRecovery.recover(db, now)); assertEquals(0, db.dao.writes)
        val recovered = ExecutionRecovery.recoverWatching(db, { now }, { now = 101; waited++ }, 2000)
        assertEquals(1, recovered); assertEquals(1, waited)
        assertEquals("needs_review", db.dao.tasks.getValue("t").status)
        assertEquals("needs_attention", db.dao.rows.getValue("a").status)
        assertNull(db.dao.rows.getValue("a").leaseOwner)
        assertEquals("recovered_interruption", db.dao.events.single().kind)
    }

    @Test fun transactionRereadProtectsOwnerThatRenewedAfterTheScan() = runBlocking {
        val db = fixture(90)
        db.dao.beforeRead = {
            db.dao.rows["a"] = db.dao.rows.getValue("a").copy(leaseOwner = "replacement", leaseExpiresAt = 10_000)
            db.dao.beforeRead = null
        }
        assertEquals(0, ExecutionRecovery.recover(db, 100))
        assertEquals("replacement", db.dao.rows.getValue("a").leaseOwner)
        assertEquals("running", db.dao.tasks.getValue("t").status); assertEquals(0, db.dao.writes)
    }

    @Test fun liveRenewalsHaveBoundedWatchingAndNoMutations() = runBlocking {
        val db = fixture(10_000); var waits = 0
        assertEquals(0, ExecutionRecovery.recoverWatching(db, { 100 }, { waits++ }, 2000))
        assertEquals(2, waits); assertEquals(0, db.dao.writes)
        assertEquals("killed-owner", db.dao.rows.getValue("a").leaseOwner)
    }

    @Test fun checkpointFaultIsObservableAndFakeTransactionRollsBackNoReplay() = runBlocking {
        val db = fixture(90); db.dao.failEvents = true
        try { ExecutionRecovery.recover(db, 100); fail("Injected failure expected") } catch (_: IllegalStateException) { }
        assertNotNull(ExecutionRecovery.failure.value)
        assertEquals("running", db.dao.tasks.getValue("t").status)
        assertEquals("killed-owner", db.dao.rows.getValue("a").leaseOwner)
        db.dao.failEvents = false; assertEquals(1, ExecutionRecovery.recover(db, 100))
        assertNull(ExecutionRecovery.failure.value)
    }

    @Test fun leaseWatcherIsCancellationAware() = runBlocking {
        val db = fixture(10_000); val entered = CompletableDeferred<Unit>()
        val job = launch {
            ExecutionRecovery.recoverWatching(db, { 100 }, { entered.complete(Unit); awaitCancellation() }, 2000)
        }
        entered.await(); job.cancelAndJoin()
        assertEquals(0, db.dao.writes); assertEquals("running", db.dao.rows.getValue("a").status)
    }
}

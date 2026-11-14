package com.nyx.ai.autonomy

import androidx.room.withTransaction
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.data.ObjectiveEventEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Conservative reconciliation only. No inference/tool dispatch and no automatic side-effect replay. */
object ExecutionRecovery {
    private val mutableFailure = MutableStateFlow<String?>(null)
    /** Observable even when a legacy caller wraps recovery in runCatching. */
    val failure: StateFlow<String?> = mutableFailure

    /** The existing startup caller now rechecks initially live leases after prompt process restart. */
    suspend fun recover(database: NyxDatabase): Int = recoverWatching(database)

    /** Explicit-time one-shot entry point for deterministic fixtures. */
    suspend fun recover(database: NyxDatabase, now: Long): Int = guarded { reconcile(database, now).first }

    suspend fun recoverWatching(
        database: NyxDatabase,
        clock: () -> Long = System::currentTimeMillis,
        wait: suspend (Long) -> Unit = { delay(it) },
        windowMillis: Long = 120_000L,
    ): Int = guarded {
        require(windowMillis in 0..300_000L)
        var remainingBudget = windowMillis
        var (recovered, liveIds) = reconcile(database, clock())
        // Bound elapsed waiting independently of wall-clock jumps and repeated heartbeat renewals.
        while (liveIds.isNotEmpty() && remainingBudget > 0L) {
            val slice = minOf(1_000L, remainingBudget)
            wait(slice)
            remainingBudget -= slice
            val next = reconcile(database, clock(), liveIds)
            recovered += next.first
            liveIds = next.second
        }
        recovered
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        try {
            val result = block()
            mutableFailure.value = null
            return result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            mutableFailure.value = "Interruption reconciliation failed (${failure.javaClass.simpleName}); no automatic replay."
            throw failure
        }
    }

    private suspend fun reconcile(database: NyxDatabase, now: Long, onlyIds: Set<String>? = null): Pair<Int, Set<String>> {
        val dao = database.objectiveDao()
        var recovered = 0
        val liveIds = linkedSetOf<String>()
        for (candidate in dao.observeAll().first()) {
            if (onlyIds != null && candidate.id !in onlyIds) continue
            database.withTransaction {
                // The scan may be stale: heartbeat/acquisition can occur before this transaction.
                val objective = dao.getObjective(candidate.id) ?: return@withTransaction
                if (objective.status !in setOf("planning", "running")) return@withTransaction
                if (!ExecutionLeasePolicy.needsRecovery(objective.status, objective.leaseExpiresAt, now)) {
                    liveIds += objective.id
                    return@withTransaction
                }
                val uncertain = dao.getTasks(objective.id).filter { it.status in setOf("running", "verifying") }
                uncertain.forEach { task ->
                    dao.upsertTask(task.copy(status = "needs_review", finishedAt = now, updatedAt = now,
                        error = "Interrupted while work may have had side effects. Review before retrying."))
                    dao.appendEvent(ObjectiveEventEntity(UUID.randomUUID().toString(), objective.id, task.id,
                        "recovered_interruption", "Execution stopped with the process; this task was not automatically replayed.", now))
                }
                dao.upsertObjective(objective.copy(
                    status = if (uncertain.isEmpty()) "paused" else "needs_attention",
                    leaseOwner = null, leaseExpiresAt = null, updatedAt = now,
                    error = if (uncertain.isEmpty()) "Execution was interrupted before tool work; resume when ready."
                        else "A task was interrupted; review it before resuming.",
                ))
                recovered++
            }
        }
        // Do not issue a broad clearExpiredLeases scan outside the ownership transaction.
        return recovered to liveIds
    }
}

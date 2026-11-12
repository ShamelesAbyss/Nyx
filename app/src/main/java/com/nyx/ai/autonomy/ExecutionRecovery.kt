package com.nyx.ai.autonomy

import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.data.ObjectiveEventEntity
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Conservative startup reconciliation. An objective whose lease expired is not resumed
 * automatically: in-flight tasks are marked `needs_review` because a tool may have had side effects.
 */
object ExecutionRecovery {
    suspend fun recover(database: NyxDatabase, now: Long = System.currentTimeMillis()): Int {
        val dao = database.objectiveDao()
        var recovered = 0
        for (objective in dao.observeAll().first()) {
            if (objective.status != "planning" && objective.status != "running") continue
            val leaseAlive = objective.leaseExpiresAt != null && objective.leaseExpiresAt > now
            if (leaseAlive) continue
            val tasks = dao.getTasks(objective.id)
            val uncertain = tasks.filter { it.status == "running" || it.status == "verifying" }
            uncertain.forEach { task ->
                dao.upsertTask(
                    task.copy(
                        status = "needs_review",
                        error = "Interrupted while work may have had side effects. Review before retrying.",
                        finishedAt = now,
                        updatedAt = now,
                    )
                )
                dao.appendEvent(
                    ObjectiveEventEntity(
                        id = UUID.randomUUID().toString(),
                        objectiveId = objective.id,
                        taskId = task.id,
                        kind = "recovered_interruption",
                        detail = "Execution stopped with the process; this task was not automatically replayed.",
                        createdAt = now,
                    )
                )
            }
            dao.upsertObjective(
                objective.copy(
                    status = if (uncertain.isEmpty()) "paused" else "needs_attention",
                    leaseOwner = null,
                    leaseExpiresAt = null,
                    updatedAt = now,
                    error = if (uncertain.isEmpty()) {
                        "Execution was interrupted before tool work; resume when ready."
                    } else {
                        "A task was interrupted; review it before resuming."
                    },
                )
            )
            recovered++
        }
        dao.clearExpiredLeases(now)
        return recovered
    }
}

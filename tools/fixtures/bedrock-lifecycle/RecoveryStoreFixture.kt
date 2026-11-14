package com.nyx.ai.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ObjectiveEntity(val id: String, val status: String, val leaseOwner: String? = null,
    val leaseExpiresAt: Long? = null, val updatedAt: Long = 0, val error: String? = null)
data class ObjectiveTaskEntity(val id: String, val objectiveId: String, val status: String,
    val updatedAt: Long = 0, val finishedAt: Long? = null, val error: String? = null)
data class ObjectiveEventEntity(val id: String, val objectiveId: String, val taskId: String?,
    val kind: String, val detail: String?, val createdAt: Long)

/** A fake DAO only, never Android Room. Production recovery source is compiled against this seam. */
class ObjectiveDao {
    val rows = linkedMapOf<String, ObjectiveEntity>()
    val tasks = linkedMapOf<String, ObjectiveTaskEntity>()
    val events = mutableListOf<ObjectiveEventEntity>()
    var beforeRead: (() -> Unit)? = null
    var failEvents = false
    var writes = 0
    fun observeAll(): Flow<List<ObjectiveEntity>> = flowOf(rows.values.toList())
    suspend fun getObjective(id: String): ObjectiveEntity? { beforeRead?.invoke(); return rows[id] }
    suspend fun getTasks(id: String) = tasks.values.filter { it.objectiveId == id }
    suspend fun upsertObjective(row: ObjectiveEntity) { writes++; rows[row.id] = row }
    suspend fun upsertTask(row: ObjectiveTaskEntity) { writes++; tasks[row.id] = row }
    suspend fun appendEvent(event: ObjectiveEventEntity) { if (failEvents) error("Injected checkpoint fault"); events += event }
}
class NyxDatabase(val dao: ObjectiveDao = ObjectiveDao()) {
    private val mutex = Mutex()
    fun objectiveDao() = dao
    suspend fun <T> transaction(block: suspend () -> T): T = mutex.withLock {
        val rows = dao.rows.toMap(); val tasks = dao.tasks.toMap(); val events = dao.events.toList(); val writes = dao.writes
        try { block() } catch (failure: Throwable) {
            dao.rows.clear(); dao.rows.putAll(rows); dao.tasks.clear(); dao.tasks.putAll(tasks)
            dao.events.clear(); dao.events.addAll(events); dao.writes = writes
            throw failure
        }
    }
}

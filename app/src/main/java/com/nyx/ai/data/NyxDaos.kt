package com.nyx.ai.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ConversationDao {
    @Upsert
    abstract suspend fun upsertConversation(conversation: ConversationEntity)

    @Upsert
    abstract suspend fun upsertConversations(conversations: List<ConversationEntity>)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    abstract fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    abstract suspend fun getConversation(conversationId: String): ConversationEntity?

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    abstract fun observeConversationWithMessages(
        conversationId: String
    ): Flow<ConversationWithMessages?>

    @Transaction
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    abstract fun observeConversationsWithMessages(): Flow<List<ConversationWithMessages>>

    @Upsert
    abstract suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    abstract suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    abstract fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    abstract suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteMessagesForConversation(conversationId: String)

    @Delete
    abstract suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    abstract suspend fun deleteConversationById(conversationId: String)

    /** Atomically stores the conversation and replaces its complete message list. */
    @Transaction
    open suspend fun saveConversationWithMessages(
        conversation: ConversationEntity,
        messages: List<MessageEntity>
    ) {
        upsertConversation(conversation)
        deleteMessagesForConversation(conversation.id)
        if (messages.isNotEmpty()) {
            upsertMessages(messages)
        }
    }
}

@Dao
interface ModelEndpointDao {
    @Upsert
    suspend fun upsert(endpoint: ModelEndpointEntity)

    @Upsert
    suspend fun upsertAll(endpoints: List<ModelEndpointEntity>)

    @Query("SELECT * FROM model_endpoints ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ModelEndpointEntity>>

    @Query("SELECT * FROM model_endpoints WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ModelEndpointEntity?

    @Query("DELETE FROM model_endpoints WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AgentDao {
    @Upsert
    suspend fun upsert(agent: AgentEntity)

    @Upsert
    suspend fun upsertAll(agents: List<AgentEntity>)

    @Query("SELECT * FROM agents ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE endpointId = :endpointId ORDER BY name COLLATE NOCASE ASC")
    fun observeForEndpoint(endpointId: String): Flow<List<AgentEntity>>

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
abstract class WorkflowDao {
    @Upsert
    abstract suspend fun upsert(workflow: WorkflowDefinitionEntity)

    @Upsert
    abstract suspend fun upsertAll(workflows: List<WorkflowDefinitionEntity>)

    @Query("SELECT * FROM workflow_definitions ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<WorkflowDefinitionEntity>>

    @Transaction
    @Query("SELECT * FROM workflow_definitions ORDER BY updatedAt DESC")
    abstract fun observeAllWithExecutionLogs(): Flow<List<WorkflowWithExecutionLogs>>

    @Transaction
    @Query("SELECT * FROM workflow_definitions WHERE id = :id LIMIT 1")
    abstract suspend fun getWithExecutionLogs(id: String): WorkflowWithExecutionLogs?

    @Query("SELECT * FROM workflow_definitions WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): WorkflowDefinitionEntity?

    @Query("DELETE FROM workflow_definitions WHERE id = :id")
    abstract suspend fun deleteById(id: String)
}

@Dao
interface ExecutionLogDao {
    @Upsert
    suspend fun upsert(log: ExecutionLogEntity)

    @Upsert
    suspend fun upsertAll(logs: List<ExecutionLogEntity>)

    @Query("SELECT * FROM execution_logs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE workflowId = :workflowId ORDER BY createdAt DESC")
    fun observeForWorkflow(workflowId: String): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE taskId = :taskId ORDER BY createdAt DESC")
    fun observeForTask(taskId: String): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExecutionLogEntity?

    @Query("DELETE FROM execution_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM execution_logs WHERE workflowId = :workflowId")
    suspend fun deleteForWorkflow(workflowId: String)

    @Query("DELETE FROM execution_logs WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface ObjectiveDao {
    @Upsert
    suspend fun upsertObjective(objective: ObjectiveEntity)

    @Query("SELECT * FROM objectives ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ObjectiveEntity>>

    @Query("SELECT * FROM objectives WHERE id = :id LIMIT 1")
    suspend fun getObjective(id: String): ObjectiveEntity?

    @Query("SELECT * FROM objective_tasks WHERE id = :id LIMIT 1")
    suspend fun getTask(id: String): ObjectiveTaskEntity?

    @Query("SELECT * FROM objectives WHERE id = :id LIMIT 1")
    fun observeObjective(id: String): Flow<ObjectiveEntity?>

    @Query("SELECT * FROM objectives WHERE status = 'running' ORDER BY updatedAt DESC")
    suspend fun getRunningObjectives(): List<ObjectiveEntity>

    @Query(
        "UPDATE objectives SET leaseOwner = :owner, leaseExpiresAt = :expiresAt " +
            "WHERE id = :id AND (leaseOwner IS NULL OR leaseExpiresAt < :now OR leaseOwner = :owner)"
    )
    suspend fun tryAcquireLease(id: String, owner: String, now: Long, expiresAt: Long): Int

    @Query(
        "UPDATE objectives SET leaseOwner = NULL, leaseExpiresAt = NULL " +
            "WHERE id = :id AND leaseOwner = :owner"
    )
    suspend fun releaseLease(id: String, owner: String): Int

    @Query(
        "UPDATE objectives SET leaseOwner = NULL, leaseExpiresAt = NULL " +
            "WHERE leaseExpiresAt IS NOT NULL AND leaseExpiresAt < :now"
    )
    suspend fun clearExpiredLeases(now: Long): Int

    @Upsert
    suspend fun upsertTasks(tasks: List<ObjectiveTaskEntity>)

    @Upsert
    suspend fun upsertTask(task: ObjectiveTaskEntity)

    @Query(
        "SELECT * FROM objective_tasks WHERE objectiveId = :objectiveId " +
            "ORDER BY ordinal ASC, id ASC"
    )
    fun observeTasks(objectiveId: String): Flow<List<ObjectiveTaskEntity>>

    @Query(
        "SELECT * FROM objective_tasks WHERE objectiveId = :objectiveId " +
            "ORDER BY ordinal ASC, id ASC"
    )
    suspend fun getTasks(objectiveId: String): List<ObjectiveTaskEntity>

    @Insert
    suspend fun appendEvent(event: ObjectiveEventEntity)

    @Query(
        "SELECT * FROM objective_events WHERE objectiveId = :objectiveId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeEvents(objectiveId: String): Flow<List<ObjectiveEventEntity>>

    @Upsert
    suspend fun upsertArtifact(artifact: ObjectiveArtifactEntity)

    @Query(
        "SELECT * FROM objective_artifacts WHERE objectiveId = :objectiveId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeArtifacts(objectiveId: String): Flow<List<ObjectiveArtifactEntity>>

    @Query(
        "SELECT * FROM objective_artifacts WHERE objectiveId = :objectiveId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun getArtifacts(objectiveId: String): List<ObjectiveArtifactEntity>
}

@Dao
interface TerminalCommandDao {
    @Upsert
    suspend fun upsert(command: TerminalCommandEntity)

    @Query("SELECT * FROM terminal_commands ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TerminalCommandEntity>>

    @Query(
        "SELECT * FROM terminal_commands WHERE objectiveId = :objectiveId " +
            "ORDER BY startedAt DESC"
    )
    fun observeForObjective(objectiveId: String): Flow<List<TerminalCommandEntity>>

    @Query("SELECT * FROM terminal_commands WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TerminalCommandEntity?

    @Query("DELETE FROM terminal_commands")
    suspend fun deleteAll()

    @Query("DELETE FROM terminal_commands WHERE startedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface TaskEvidenceDao {
    @Insert
    suspend fun insert(evidence: TaskEvidenceEntity)

    @Insert
    suspend fun insertAll(evidence: List<TaskEvidenceEntity>)

    @Query(
        "SELECT * FROM task_evidence WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeForTask(taskId: String): Flow<List<TaskEvidenceEntity>>

    @Query(
        "SELECT * FROM task_evidence WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun getForTask(taskId: String): List<TaskEvidenceEntity>
}

@Dao
interface ContextRecordDao {
    @Insert
    suspend fun insert(record: ContextRecordEntity)

    @Query(
        "SELECT * FROM context_records WHERE objectiveId = :objectiveId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun listForObjective(objectiveId: String): List<ContextRecordEntity>

    @Query(
        "SELECT * FROM context_records WHERE objectiveId = :objectiveId AND taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun listForTask(objectiveId: String, taskId: String): List<ContextRecordEntity>

    @Query("DELETE FROM context_records WHERE objectiveId = :objectiveId")
    suspend fun deleteForObjective(objectiveId: String)
}

@Dao
interface ExecutionLeaseDao {
    @Upsert
    suspend fun upsert(lease: ExecutionLeaseEntity)

    @Query("SELECT * FROM execution_leases WHERE objectiveId = :objectiveId LIMIT 1")
    suspend fun get(objectiveId: String): ExecutionLeaseEntity?

    @Query("DELETE FROM execution_leases WHERE objectiveId = :objectiveId")
    suspend fun delete(objectiveId: String)

    @Query("DELETE FROM execution_leases WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long)
}

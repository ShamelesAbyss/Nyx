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

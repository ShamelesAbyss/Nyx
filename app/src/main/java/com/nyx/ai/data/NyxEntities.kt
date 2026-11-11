package com.nyx.ai.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Locally persisted conversation metadata. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A message belongs to exactly one conversation; deleting that conversation deletes its messages. */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId", "createdAt"])]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    /** Role is stored as a string (for example, system, user, assistant, or tool). */
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Optional provider/model metadata for rendering or debugging; no telemetry is implied. */
    val modelId: String? = null
)

/** A configurable model connection. Store secrets outside this record and reference them by ID. */
@Entity(tableName = "model_endpoints")
data class ModelEndpointEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val provider: String,
    val baseUrl: String,
    val modelId: String,
    val credentialReference: String? = null,
    val configurationJson: String? = null,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A locally stored agent definition. endpointId is intentionally nullable. */
@Entity(
    tableName = "agents",
    indices = [Index(value = ["endpointId"])]
)
data class AgentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val instructions: String = "",
    val endpointId: String? = null,
    val configurationJson: String? = null,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A workflow definition serialized by the workflow layer. */
@Entity(tableName = "workflow_definitions")
data class WorkflowDefinitionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val definitionJson: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A local execution record, optionally associated with a workflow and/or task. */
@Entity(
    tableName = "execution_logs",
    indices = [
        Index(value = ["workflowId", "createdAt"]),
        Index(value = ["taskId", "createdAt"])
    ]
)
data class ExecutionLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val workflowId: String? = null,
    val taskId: String? = null,
    val status: String,
    val inputJson: String? = null,
    val outputText: String? = null,
    val errorMessage: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

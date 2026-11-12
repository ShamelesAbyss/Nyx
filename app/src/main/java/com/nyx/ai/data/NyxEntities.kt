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

/** A durable autonomous objective, independent of any in-memory coordinator session. */
@Entity(
    tableName = "objectives",
    indices = [Index(value = ["status"]), Index(value = ["updatedAt"])]
)
data class ObjectiveEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val goal: String,
    val status: String = "pending",
    val agentId: String? = null,
    val modelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val error: String? = null
)

/** One planned DAG node; dependsOnJson stores the stable IDs of predecessor tasks. */
@Entity(
    tableName = "objective_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ObjectiveEntity::class,
            parentColumns = ["id"],
            childColumns = ["objectiveId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["objectiveId", "ordinal"]),
        Index(value = ["objectiveId", "status"])
    ]
)
data class ObjectiveTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val objectiveId: String,
    val ordinal: Int,
    val title: String,
    /** Human-readable plan/instructions for this DAG node. */
    val instructions: String,
    /** Verification criteria or verifier prompt. */
    val verification: String? = null,
    /** JSON array of predecessor task IDs. */
    val dependsOnJson: String = "[]",
    val status: String = "pending",
    val attemptCount: Int = 0,
    val maxAttempts: Int = 1,
    val outcome: String? = null,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Durable resumable state for a task interrupted between attempts. */
    val checkpointJson: String? = null,
    /** For example pending, passed, failed, or skipped. */
    val verificationStatus: String = "pending"
)

/** Append-only lifecycle, tool invocation/observation, and verification records. */
@Entity(
    tableName = "objective_events",
    foreignKeys = [
        ForeignKey(
            entity = ObjectiveEntity::class,
            parentColumns = ["id"],
            childColumns = ["objectiveId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ObjectiveTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["objectiveId", "createdAt"]),
        Index(value = ["taskId", "createdAt"])
    ]
)
data class ObjectiveEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val objectiveId: String,
    val taskId: String? = null,
    /** Event category, such as status_changed, tool_invocation, tool_observation, or verified. */
    val kind: String,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val verificationStatus: String? = null
)

/** Text artifact produced during an objective, optionally attributed to one DAG task. */
@Entity(
    tableName = "objective_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = ObjectiveEntity::class,
            parentColumns = ["id"],
            childColumns = ["objectiveId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ObjectiveTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["objectiveId", "createdAt"]),
        Index(value = ["taskId"])
    ]
)
data class ObjectiveArtifactEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val objectiveId: String,
    val taskId: String? = null,
    val name: String,
    val contentType: String = "text/plain",
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

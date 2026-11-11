package com.nyx.ai.data

import androidx.room.Embedded
import androidx.room.Relation

/** Conversation and its ordered messages, loaded together inside a Room transaction. */
data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "conversationId"
    )
    val messages: List<MessageEntity>
)

/** Workflow definition and its associated execution history. */
data class WorkflowWithExecutionLogs(
    @Embedded val workflow: WorkflowDefinitionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "workflowId"
    )
    val executionLogs: List<ExecutionLogEntity>
)

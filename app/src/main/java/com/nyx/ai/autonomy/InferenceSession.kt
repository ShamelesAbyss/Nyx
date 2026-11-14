package com.nyx.ai.autonomy

import com.nyx.ai.data.AgentEntity
import com.nyx.ai.data.ObjectiveEntity
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider

/** The provider-neutral inference context for one objective run. */
data class InferenceSession(
    val provider: InferenceProvider,
    val modelId: String,
    val options: InferenceOptions,
    val timeouts: ExecutionTimeoutPolicy = options.executionTimeouts ?: ExecutionTimeoutPolicy.forProfile(),
)

fun interface InferenceSessionFactory {
    suspend fun create(objective: ObjectiveEntity, agent: AgentEntity): InferenceSession
}

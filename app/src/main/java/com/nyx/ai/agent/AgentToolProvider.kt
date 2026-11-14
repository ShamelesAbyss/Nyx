package com.nyx.ai.agent

import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.WeakHashMap

/** Structured, locally observed evidence from one tool invocation. */
data class ToolExecutionEvidence(
    val toolName: String,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val files: List<String> = emptyList(),
    /** True when the invocation may have changed files, packages, or external state. */
    val mutating: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = System.currentTimeMillis(),
    val objectiveId: String? = null,
    val taskId: String? = null,
)

/** Read-only file inspection used by the verifier; implemented over the authorized workspace. */
interface FileProbe {
    suspend fun exists(path: String): Boolean
    suspend fun read(path: String, maxChars: Int = 64_000): String?
    suspend fun sha256(path: String): String?
}

data class AgentToolTaskContext(val objectiveId: String, val taskId: String, val timeouts: ExecutionTimeoutPolicy)

/**
 * Extra tools attached to an agent beyond the built-in local allowlist. Implementations must bound
 * their own output and must never widen their own permissions at runtime.
 */
interface AgentToolProvider {
    val names: Set<String>
    fun schemas(): JSONArray
    suspend fun execute(call: ToolCall): String
    fun drainEvidence(): List<ToolExecutionEvidence> = emptyList()

    /** Stateful implementations should clone; legacy providers serialize execute+drain, not tasks. */
    fun forTask(context: AgentToolTaskContext): AgentToolProvider = TaskLocalToolProvider(this, context)
}

/** Safe fallback for providers that have not implemented a task-local factory. */
private class TaskLocalToolProvider(
    private val source: AgentToolProvider,
    private val context: AgentToolTaskContext,
) : AgentToolProvider {
    private val invocationLock = synchronized(locks) { locks.getOrPut(source) { Mutex() } }
    private val evidence = mutableListOf<ToolExecutionEvidence>()
    override val names: Set<String> get() = source.names
    override fun schemas(): JSONArray = source.schemas()
    override suspend fun execute(call: ToolCall): String = invocationLock.withLock {
        // A previous direct caller's undrained evidence cannot establish this task's success.
        source.drainEvidence()
        try { source.execute(call) } finally {
            val observed = source.drainEvidence().map { item ->
                check(item.objectiveId == null || item.objectiveId == context.objectiveId) { "Foreign objective evidence" }
                check(item.taskId == null || item.taskId == context.taskId) { "Foreign task evidence" }
                item.copy(objectiveId = context.objectiveId, taskId = context.taskId)
            }
            synchronized(evidence) { evidence.addAll(observed) }
        }
    }
    override fun drainEvidence(): List<ToolExecutionEvidence> = synchronized(evidence) {
        evidence.toList().also { evidence.clear() }
    }
    override fun forTask(context: AgentToolTaskContext): AgentToolProvider = source.forTask(context)

    companion object {
        private val locks = WeakHashMap<AgentToolProvider, Mutex>()
    }
}

package com.nyx.ai.agent

import com.nyx.ai.network.ToolCall
import org.json.JSONArray

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
)

/** Read-only file inspection used by the verifier; implemented over the authorized workspace. */
interface FileProbe {
    suspend fun exists(path: String): Boolean
    suspend fun read(path: String, maxChars: Int = 64_000): String?
    suspend fun sha256(path: String): String?
}

/**
 * Extra tools attached to an agent beyond the built-in local allowlist. Implementations must bound
 * their own output and must never widen their own permissions at runtime.
 */
interface AgentToolProvider {
    val names: Set<String>
    fun schemas(): JSONArray
    suspend fun execute(call: ToolCall): String
    fun drainEvidence(): List<ToolExecutionEvidence> = emptyList()
}

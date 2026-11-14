package com.nyx.ai.agent

import com.nyx.ai.network.ToolCall
import org.json.JSONArray

/**
 * Exposes several providers as one tool surface. Duplicate names are rejected rather than silently
 * shadowed, so a later provider cannot replace a terminal tool with a broader implementation.
 */
class CompositeToolProvider(private val providers: List<AgentToolProvider>) : AgentToolProvider {
    private val byName = LinkedHashMap<String, AgentToolProvider>()

    init {
        for (provider in providers) {
            for (name in provider.names) {
                require(byName.put(name, provider) == null) { "Duplicate tool name: $name" }
            }
        }
    }

    override val names: Set<String> get() = byName.keys

    override fun schemas(): JSONArray {
        val merged = JSONArray()
        for (provider in providers) {
            val schemas = provider.schemas()
            for (index in 0 until schemas.length()) merged.put(schemas.optJSONObject(index))
        }
        return merged
    }

    override suspend fun execute(call: ToolCall): String {
        val provider = byName[call.name] ?: return "Tool error: unknown tool ${call.name}."
        return provider.execute(call)
    }

    override fun drainEvidence(): List<ToolExecutionEvidence> = providers.flatMap { it.drainEvidence() }

    override fun forTask(context: AgentToolTaskContext): AgentToolProvider =
        CompositeToolProvider(providers.map { it.forTask(context) })
}

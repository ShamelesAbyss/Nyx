package com.nyx.ai.agent

import com.nyx.ai.autonomy.policy.ActionKind
import com.nyx.ai.autonomy.policy.ActionRequest
import com.nyx.ai.autonomy.policy.AutonomyRuntime
import com.nyx.ai.gateway.GatewayRepository
import com.nyx.ai.gateway.GatewayServerConfig
import com.nyx.ai.gateway.GatewayTool
import com.nyx.ai.network.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Bridges one snapshot of allowlisted gateway tools into the agent tool registry. The snapshot is
 * taken by the execution service before the objective starts, so a model cannot enable a new server
 * or widen an allowlist at runtime. Every call still passes [GatewayRepository] policy and the
 * shared autonomy policy.
 */
class GatewayAgentTools(
    private val context: android.content.Context,
    private val repository: GatewayRepository,
    private val servers: List<Pair<GatewayServerConfig, List<GatewayTool>>>,
    private val environmentId: String,
    private val workspace: String,
) : AgentToolProvider {
    private data class Entry(val server: GatewayServerConfig, val tool: GatewayTool, val exposedName: String)

    private val entries: List<Entry> = servers.flatMap { (server, tools) ->
        tools.map { tool ->
            val prefix = server.id.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]+"), "_").take(40)
            Entry(server, tool, "gw_${prefix}_${tool.name}".take(64))
        }
    }.distinctBy { it.exposedName }

    private val evidence = mutableListOf<ToolExecutionEvidence>()

    override val names: Set<String> = entries.map { it.exposedName }.toSet()

    override fun schemas(): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            val schema = runCatching { JSONObject(entry.tool.inputSchemaJson.ifBlank { "{}" }) }.getOrDefault(JSONObject().put("type", "object"))
            put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", entry.exposedName)
                        .put("description", "${entry.server.name}: ${entry.tool.description}".take(500))
                        .put("parameters", schema),
                ),
            )
        }
    }

    override suspend fun execute(call: ToolCall): String {
        val entry = entries.firstOrNull { it.exposedName == call.name }
            ?: return "Tool error: unknown gateway tool ${call.name}."
        AutonomyRuntime.initialize(context)
        if (AutonomyRuntime.policy().stopped) return "Tool denied: emergency stop is active."
        val writeTool = entry.tool.name.contains("create") || entry.tool.name.contains("comment") || entry.tool.name.contains("publish")
        val target = if (workspace.startsWith("/")) {
            "$workspace/.nyx/gateway/${entry.server.id}/${entry.tool.name}"
        } else {
            "/nyx/gateway/${entry.server.id}/${entry.tool.name}"
        }
        val decision = AutonomyRuntime.authorize(
            context,
            ActionRequest(
                kind = if (writeTool) ActionKind.GIT_COMMIT else ActionKind.NETWORK_READ,
                target = target,
                environmentId = environmentId,
                insideAuthorizedWorkspace = workspace.startsWith("/"),
            ),
        )
        if (!decision.allowed && !decision.requiresApproval) {
            return "Tool denied: ${decision.reason}"
        }
        val started = System.currentTimeMillis()
        val result = repository.callTool(
            entry.server.id,
            entry.tool.name,
            call.arguments.toString(),
            runId = "objective",
        )
        evidence += ToolExecutionEvidence(
            toolName = call.name,
            stdout = result.content.take(2_000),
            mutating = writeTool,
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
        )
        return if (result.success) result.content else "Tool error: ${result.content.take(1_000)}"
    }

    override fun drainEvidence(): List<ToolExecutionEvidence> = synchronized(evidence) {
        val copy = evidence.toList()
        evidence.clear()
        copy
    }

}

package com.nyx.ai.agent

import android.content.Context
import com.nyx.ai.autonomy.policy.AutonomyRuntime
import com.nyx.ai.network.NetworkDecision
import com.nyx.ai.network.NetworkPolicy
import com.nyx.ai.network.NetworkTools
import com.nyx.ai.network.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only web access for objectives. Fetched text is always returned inside an explicit untrusted
 * block, and Manual mode refuses rather than silently treating a model request as user consent.
 */
class NetworkAgentTools(
    context: Context,
    private val tools: NetworkTools = NetworkTools(),
) : AgentToolProvider {
    private val appContext = context.applicationContext
    private val evidence = mutableListOf<ToolExecutionEvidence>()

    override val names: Set<String> = setOf(TOOL_FETCH)

    override fun schemas(): JSONArray = JSONArray().apply {
        put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", TOOL_FETCH)
                        .put("description", "Fetch one public HTTPS page as untrusted text. Private addresses and credentials-in-URL are refused.")
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject().put("url", JSONObject().put("type", "string").put("description", "Absolute HTTPS URL")))
                                .put("required", JSONArray().put("url"))
                        )
                )
        )
    }

    override suspend fun execute(call: ToolCall): String {
        if (call.name != TOOL_FETCH) return "Tool error: unknown tool ${call.name}."
        AutonomyRuntime.initialize(appContext)
        val url = call.arguments.optString("url").trim()
        val started = System.currentTimeMillis()
        val assessment = NetworkPolicy.assess(url, upload = false, mode = AutonomyRuntime.mode())
        val result = when (assessment.decision) {
            NetworkDecision.DENY -> "Tool denied: ${assessment.reason}"
            NetworkDecision.ASK -> "Tool paused: ${assessment.reason} Switch to Smart or Full autonomy to allow read-only fetches."
            NetworkDecision.ALLOW -> runCatching { tools.fetchText(url, 256 * 1024) }.fold(
                onSuccess = { page -> page.asUntrustedBlock(12_000) },
                onFailure = { error -> "Tool error: ${error.message?.take(240) ?: "fetch failed"}" },
            )
        }
        evidence += ToolExecutionEvidence(
            toolName = TOOL_FETCH,
            stdout = result.take(2_000),
            mutating = false,
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
        )
        return result
    }

    override fun drainEvidence(): List<ToolExecutionEvidence> {
        val drained = evidence.toList()
        evidence.clear()
        return drained
    }

    companion object {
        const val TOOL_FETCH = "fetch_url"
    }
}

package com.nyx.ai.agent

import android.content.Context
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.OllamaClient
import com.nyx.ai.network.ToolCall
import com.nyx.ai.network.ToolCallingUnsupportedException
import org.json.JSONArray
import org.json.JSONObject

data class AgentSpec(
    val id: String,
    val name: String,
    val model: String,
    val systemPrompt: String,
    val maxSteps: Int = 6,
    val allowedTools: Set<String> = AgentTools.DEFAULT_TOOLS
)

data class AgentEvent(
    val kind: String,
    val text: String,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null
)

data class AgentOutcome(
    val result: String,
    val events: List<AgentEvent>,
    val steps: Int,
    val evidence: List<ToolExecutionEvidence> = emptyList(),
)

/**
 * Executes model -> tool selection -> allowlist validation -> tool -> observation cycles.
 *
 * The runner is provider-neutral: it accepts either a raw [OllamaClient] (existing call sites) or an
 * [InferenceProvider] with optional extra tools such as the authorized terminal workspace.
 */
class AgentRunner private constructor(
    context: Context,
    private val chat: suspend (String, List<ApiMessage>, JSONArray?, InferenceOptions, (String) -> Unit) -> ChatResult,
    private val extraTools: AgentToolProvider?,
) {
    private val app = context.applicationContext

    constructor(context: Context, client: OllamaClient) : this(
        context,
        { model, messages, tools, options, onToken -> client.chat(model, messages, tools, options, onToken) },
        null,
    )

    constructor(context: Context, provider: InferenceProvider, extraTools: AgentToolProvider? = null) : this(
        context,
        { model, messages, tools, options, onToken -> provider.chat(model, messages, tools, options, onToken) },
        extraTools,
    )

    suspend fun run(
        spec: AgentSpec,
        task: String,
        sharedContext: String = "",
        options: InferenceOptions = InferenceOptions(),
        onEvent: (AgentEvent) -> Unit = {},
        onToken: (String) -> Unit = {}
    ): AgentOutcome {
        require(spec.model.isNotBlank()) { "Choose a local model before starting an agent." }
        val tools = AgentTools(app, spec.id)
        val available = AgentTools.DEFAULT_TOOLS + (extraTools?.names ?: emptySet())
        val allowed = spec.allowedTools intersect available
        val toolSchemas = tools.schemas().filtered(allowed)
        extraTools?.schemas()?.let { extra ->
            val filteredExtra = extra.filtered(allowed)
            for (i in 0 until filteredExtra.length()) toolSchemas.put(filteredExtra.opt(i))
        }
        val system = buildString {
            append(spec.systemPrompt.ifBlank { "You are ${spec.name}, a local AI agent. Be accurate and concise." })
            append("\n\nOperate only through the supplied allowlisted tools. Never claim a tool action succeeded before its result. The user controls which local model endpoint is used.")
            if (extraTools != null) {
                append("\n\nTerminal/file tools operate on a user-authorized workspace through Termux. Destructive and privileged commands are blocked by policy. Report real command output and exit codes; never invent them.")
            }
            if (sharedContext.isNotBlank()) append("\n\nShared workflow context from previous steps:\n").append(sharedContext.take(20_000))
        }
        val messages = mutableListOf(ApiMessage("system", system), ApiMessage("user", task))
        val events = mutableListOf<AgentEvent>()
        val evidence = mutableListOf<ToolExecutionEvidence>()
        var currentStep = 0
        val maxSteps = spec.maxSteps.coerceIn(1, 16)
        while (currentStep < maxSteps) {
            currentStep++
            onEvent(AgentEvent("thinking", "${spec.name}: reasoning step $currentStep/$maxSteps"))
            val response = try {
                chat(spec.model, messages, if (allowed.isEmpty()) null else toolSchemas, options, onToken)
            } catch (e: ToolCallingUnsupportedException) {
                val detail = e.message?.take(260)?.let { " Server: $it" }.orEmpty()
                val warning = "${spec.name}: native tool calling is unavailable for this model/server; retrying once without tools.$detail"
                val event = AgentEvent("warning", warning)
                events += event
                onEvent(event)
                messages[0] = ApiMessage("system", system + "\n\nThe server rejected native tool calling. Do not request tools or claim to have run tools. Answer directly from the available conversation context; be explicit about the limitation.")
                val fallback = chat(spec.model, messages, null, options, onToken).content
                val result = fallback.ifBlank { "Native tool calling is unsupported by this model/server. No tools were executed. Retry with a tool-capable model or ask for a text-only answer." }
                return AgentOutcome(result, events, currentStep, evidence)
            }
            if (response.toolCalls.isEmpty()) {
                val result = response.content.ifBlank { "The model returned an empty response." }
                return AgentOutcome(result, events, currentStep, evidence)
            }
            val assistantCalls = JSONArray()
            for (call in response.toolCalls) {
                assistantCalls.put(JSONObject()
                    .put("id", call.id)
                    .put("type", "function")
                    .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
            }
            messages += ApiMessage("assistant", response.content, toolCalls = assistantCalls)
            for (call in response.toolCalls) {
                onEvent(AgentEvent("tool_invocation", "${spec.name} requested ${call.name}", call.name, call.arguments.toString()))
                val observation = if (call.name !in allowed) {
                    "Tool denied: '${call.name}' is not enabled for this agent."
                } else if (extraTools != null && call.name in extraTools.names) {
                    try {
                        extraTools.execute(call)
                    } catch (e: Exception) {
                        "Tool error: ${e.message?.take(300) ?: "tool execution failed"}"
                    }
                } else {
                    tools.execute(call)
                }
                extraTools?.drainEvidence()?.let { evidence += it }
                val event = AgentEvent("tool_observation", "${call.name}: ${observation.take(800)}", call.name, call.arguments.toString(), observation.take(4_000))
                events += event
                onEvent(event)
                messages += ApiMessage("tool", observation.take(6_000), toolName = call.name, toolCallId = call.id)
            }
        }
        // A final no-tools turn lets the model summarize observations after the bounded tool budget.
        val final = chat(spec.model, messages, null, options, onToken).content
        val result = final.ifBlank { "Execution stopped after reaching the ${maxSteps}-step limit. Review the run log and continue with a new task if needed." }
        return AgentOutcome(result, events, currentStep, evidence)
    }

    private fun JSONArray.filtered(names: Set<String>): JSONArray {
        val result = JSONArray()
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val function = item.optJSONObject("function") ?: continue
            if (function.optString("name") in names) result.put(item)
        }
        return result
    }
}

package com.nyx.ai.agent

import android.content.Context
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.OllamaClient
import com.nyx.ai.network.ToolCall
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

data class AgentEvent(val kind: String, val text: String)
data class AgentOutcome(val result: String, val events: List<AgentEvent>, val steps: Int)

/** Executes model -> tool selection -> allowlist validation -> tool -> observation cycles. */
class AgentRunner(context: Context, private val client: OllamaClient) {
    private val app = context.applicationContext

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
        val allowed = spec.allowedTools intersect AgentTools.DEFAULT_TOOLS
        val toolSchemas = tools.schemas().filtered(allowed)
        val system = buildString {
            append(spec.systemPrompt.ifBlank { "You are ${spec.name}, a local AI agent. Be accurate and concise." })
            append("\n\nOperate only through the supplied allowlisted tools. Never claim a tool action succeeded before its result. The user controls which local model endpoint is used.")
            if (sharedContext.isNotBlank()) append("\n\nShared workflow context from previous steps:\n").append(sharedContext.take(20_000))
        }
        val messages = mutableListOf(ApiMessage("system", system), ApiMessage("user", task))
        val events = mutableListOf<AgentEvent>()
        var currentStep = 0
        val maxSteps = spec.maxSteps.coerceIn(1, 12)
        while (currentStep < maxSteps) {
            currentStep++
            onEvent(AgentEvent("thinking", "${spec.name}: reasoning step $currentStep/$maxSteps"))
            val response = client.chat(spec.model, messages, if (allowed.isEmpty()) null else toolSchemas, options, onToken)
            if (response.toolCalls.isEmpty()) {
                val result = response.content.ifBlank { "The model returned an empty response." }
                return AgentOutcome(result, events, currentStep)
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
                val observation = if (call.name !in allowed) {
                    "Tool denied: '${call.name}' is not enabled for this agent."
                } else {
                    onEvent(AgentEvent("tool", "${spec.name} requested ${call.name}"))
                    tools.execute(call)
                }
                val event = AgentEvent("observation", "${call.name}: ${observation.take(800)}")
                events += event
                onEvent(event)
                messages += ApiMessage("tool", observation, toolName = call.name, toolCallId = call.id)
            }
        }
        // A final no-tools turn lets the model summarize observations after the bounded tool budget.
        val final = client.chat(spec.model, messages, null, options, onToken).content
        val result = final.ifBlank { "Execution stopped after reaching the ${maxSteps}-step limit. Review the run log and continue with a new task if needed." }
        return AgentOutcome(result, events, currentStep)
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

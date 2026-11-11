package com.nyx.ai.agent

import android.content.Context
import com.nyx.ai.network.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal

/** Local tools intentionally avoid shell access and unrestricted storage access. */
class AgentTools(context: Context, private val agentId: String) {
    companion object {
        val DEFAULT_TOOLS = setOf("calculator", "memory_read", "memory_write", "plan_task", "workspace_read", "workspace_write")
    }

    private val app = context.applicationContext
    private val memory = app.getSharedPreferences("nyx_agent_memory", Context.MODE_PRIVATE)
    private val workspace = File(app.filesDir, "agent-workspace/${safeSegment(agentId)}").apply { mkdirs() }

    fun schemas(): JSONArray = JSONArray().apply {
        put(schema("calculator", "Evaluate a basic arithmetic expression. No code execution.", obj("expression" to "string"), listOf("expression")))
        put(schema("memory_read", "Read one item from this agent's private local memory.", obj("key" to "string"), listOf("key")))
        put(schema("memory_write", "Save one item to this agent's private local memory.", obj("key" to "string", "value" to "string"), listOf("key", "value")))
        put(schema("plan_task", "Save a structured task plan as a JSON list of steps.", obj("goal" to "string", "steps" to "array"), listOf("goal", "steps")))
        put(schema("workspace_read", "Read a text file in the agent's private sandbox workspace.", obj("filename" to "string"), listOf("filename")))
        put(schema("workspace_write", "Create or replace a text file in the agent's private sandbox workspace.", obj("filename" to "string", "content" to "string"), listOf("filename", "content")))
    }

    fun execute(call: ToolCall): String = try {
        when (call.name) {
            "calculator" -> Calculator.eval(call.arguments.optString("expression")).toPlainString()
            "memory_read" -> memory.getString(memoryKey(call.arguments.optString("key")), "")
                ?.ifEmpty { "No saved value for that key." } ?: "No saved value for that key."
            "memory_write" -> {
                val key = cleanKey(call.arguments.optString("key"))
                val value = call.arguments.optString("value").take(12_000)
                memory.edit().putString(memoryKey(key), value).apply()
                "Saved privately on this device."
            }
            "plan_task" -> {
                val steps = call.arguments.optJSONArray("steps") ?: JSONArray()
                val normalized = JSONArray()
                for (i in 0 until minOf(steps.length(), 30)) normalized.put(steps.optString(i).take(500))
                val plan = JSONObject().put("goal", call.arguments.optString("goal").take(1_000)).put("steps", normalized)
                memory.edit().putString(memoryKey("last_plan"), plan.toString()).apply()
                plan.toString(2)
            }
            "workspace_read" -> {
                val file = workspaceFile(call.arguments.optString("filename"))
                if (!file.isFile) "File not found in agent workspace." else file.readText().take(30_000)
            }
            "workspace_write" -> {
                val file = workspaceFile(call.arguments.optString("filename"))
                file.parentFile?.mkdirs()
                file.writeText(call.arguments.optString("content").take(30_000))
                "Saved ${file.name} in this agent's private workspace."
            }
            else -> "Tool denied: this tool is not in the local allowlist."
        }
    } catch (e: Exception) {
        "Tool error: ${e.message ?: "invalid input"}"
    }

    private fun memoryKey(key: String) = "${safeSegment(agentId)}:${cleanKey(key)}"
    private fun cleanKey(key: String): String = key.trim().take(100).ifBlank { "untitled" }
    private fun workspaceFile(raw: String): File {
        val name = raw.trim()
        require(name.isNotBlank() && name.length <= 120 && !name.contains('/') && !name.contains('\\') && name != "." && name != "..") {
            "Use a simple filename without path separators."
        }
        val file = File(workspace, name).canonicalFile
        require(file.parentFile == workspace.canonicalFile) { "Path outside sandbox." }
        return file
    }

    private fun safeSegment(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "agent" }

    private fun schema(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required))))

    private fun obj(vararg fields: Pair<String, String>) = JSONObject().apply {
        fields.forEach { (key, type) ->
            put(key, if (type == "array") JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")) else JSONObject().put("type", type))
        }
    }

    internal object Calculator {
        fun eval(input: String): BigDecimal {
            require(input.length <= 256) { "Expression is too long." }
            val parser = Parser(input)
            val value = parser.expression()
            parser.skipSpaces()
            require(parser.atEnd()) { "Unexpected input in expression." }
            return value.stripTrailingZeros()
        }
        private class Parser(private val s: String) {
            var i = 0
            fun atEnd() = i >= s.length
            fun skipSpaces() { while (!atEnd() && s[i].isWhitespace()) i++ }
            fun expression(): BigDecimal {
                var v = term()
                while (true) {
                    skipSpaces()
                    if (take('+')) v += term() else if (take('-')) v -= term() else return v
                }
            }
            private fun term(): BigDecimal {
                var v = factor()
                while (true) {
                    skipSpaces()
                    if (take('*')) v *= factor() else if (take('/')) {
                        val d = factor(); require(d.compareTo(BigDecimal.ZERO) != 0) { "Division by zero." }; v = v.divide(d, 12, java.math.RoundingMode.HALF_UP)
                    } else return v
                }
            }
            private fun factor(): BigDecimal {
                skipSpaces()
                if (take('+')) return factor()
                if (take('-')) return factor().negate()
                if (take('(')) { val v = expression(); skipSpaces(); require(take(')')) { "Missing closing parenthesis." }; return v }
                val start = i
                while (!atEnd() && (s[i].isDigit() || s[i] == '.')) i++
                require(start < i) { "Expected a number." }
                return s.substring(start, i).toBigDecimal()
            }
            private fun take(c: Char): Boolean = if (!atEnd() && s[i] == c) { i++; true } else false
        }
    }
}

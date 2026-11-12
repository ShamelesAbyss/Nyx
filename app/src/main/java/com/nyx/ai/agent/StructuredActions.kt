package com.nyx.ai.agent

import com.nyx.ai.network.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

sealed interface ModelAction {
    data class Tool(val call: ToolCall) : ModelAction
    data class Final(val text: String) : ModelAction
}

/** No extracting shell commands from prose, no guessing/repairing arguments locally. */
object StructuredActions {
    fun parse(raw: String, schemas: JSONArray, allowed: Set<String>): ModelAction {
        require(raw.length in 2..24_000) { "Action must be a bounded JSON object." }
        val tokener = JSONTokener(raw.trim())
        val obj = tokener.nextValue() as? JSONObject ?: error("Action must be an object.")
        require(tokener.nextClean() == '\u0000') { "Trailing prose is not an action." }
        return when (obj.optString("type")) {
            "final" -> {
                require(obj.keys().asSequence().toSet() == setOf("type", "text")) { "Unknown final fields." }
                val text = obj.get("text") as? String ?: error("Final text must be a string.")
                require(text.isNotBlank()) { "Empty final response." }; ModelAction.Final(text)
            }
            "tool" -> {
                require(obj.keys().asSequence().toSet() == setOf("type", "name", "arguments")) { "Unknown tool fields." }
                val name = obj.get("name") as? String ?: error("Tool name must be a string.")
                require(name in allowed) { "Tool not authorized." }
                val args = obj.get("arguments") as? JSONObject ?: error("Arguments must be an object.")
                require(args.toString().length <= 16_000) { "Arguments exceed action budget." }
                val fn = (0 until schemas.length()).mapNotNull { schemas.optJSONObject(it)?.optJSONObject("function") }.firstOrNull { it.optString("name") == name } ?: error("No schema for tool.")
                validate(args, fn.getJSONObject("parameters"))
                ModelAction.Tool(ToolCall("text-action", name, args))
            }
            else -> error("Unknown action type.")
        }
    }

    fun validate(args: JSONObject, schema: JSONObject) {
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        require(args.keys().asSequence().toSet().all { it in properties.keys().asSequence().toSet() }) { "Unknown tool argument." }
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) require(args.has(required.getString(i)) && !args.isNull(required.getString(i))) { "Missing required argument." }
        for (key in args.keys().asSequence().toSet()) {
            val value = args.get(key)
            val type = properties.getJSONObject(key).optString("type")
            require(when (type) {
                "string" -> value is String && value.length <= 16_000 && '\u0000' !in value
                "number", "integer" -> value is Number && value.toDouble().isFinite() && (type != "integer" || value.toDouble() % 1.0 == 0.0)
                "boolean" -> value is Boolean
                "array" -> value is JSONArray && value.length() <= 64
                "object" -> value is JSONObject
                else -> false
            }) { "Wrong argument type for $key." }
        }
    }
}

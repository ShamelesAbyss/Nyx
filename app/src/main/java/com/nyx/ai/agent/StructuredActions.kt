package com.nyx.ai.agent

import com.nyx.ai.network.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

sealed interface ModelAction {
    data class Tool(val call: ToolCall, val summary: String? = null) : ModelAction
    data class Final(val text: String, val summary: String? = null) : ModelAction
}

data class ParsedAction(
    val action: ModelAction,
    val repaired: Boolean = false,
    val repairNote: String? = null,
)

/**
 * No extracting shell commands from prose, no guessing/repairing arguments locally. The tolerant
 * path only locates one complete JSON action object inside fences or surrounding text; the object
 * still passes the same schema, allowlist, and unknown-field checks.
 */
object StructuredActions {
    fun parse(raw: String, schemas: JSONArray, allowed: Set<String>): ModelAction {
        require(raw.length in 2..24_000) { "Action must be a bounded JSON object." }
        val tokener = JSONTokener(raw.trim())
        val obj = tokener.nextValue() as? JSONObject ?: error("Action must be an object.")
        require(tokener.nextClean() == '\u0000') { "Trailing prose is not an action." }
        return when (obj.optString("type")) {
            "final" -> {
                require(obj.keys().asSequence().toSet() in setOf(setOf("type", "text"), setOf("type", "text", "summary"))) { "Unknown final fields." }
                val text = obj.get("text") as? String ?: error("Final text must be a string.")
                require(text.isNotBlank()) { "Empty final response." }
                ModelAction.Final(text, summary(obj))
            }
            "tool" -> {
                require(obj.keys().asSequence().toSet() in setOf(
                    setOf("type", "name", "arguments"),
                    setOf("type", "name", "arguments", "summary"),
                )) { "Unknown tool fields." }
                val name = obj.get("name") as? String ?: error("Tool name must be a string.")
                require(name in allowed) { "Tool not authorized." }
                val args = obj.get("arguments") as? JSONObject ?: error("Arguments must be an object.")
                require(args.toString().length <= 16_000) { "Arguments exceed action budget." }
                val fn = (0 until schemas.length()).mapNotNull { schemas.optJSONObject(it)?.optJSONObject("function") }.firstOrNull { it.optString("name") == name } ?: error("No schema for tool.")
                validate(args, fn.getJSONObject("parameters"))
                ModelAction.Tool(ToolCall("text-action", name, args), summary(obj))
            }
            else -> error("Unknown action type.")
        }
    }

    /**
     * Tries strict parsing first, then accepts exactly one complete JSON object found inside a
     * Markdown fence or surrounding explanatory text. Multiple objects, trailing actions, and prose
     * that merely resembles a command remain invalid.
     */
    fun parseTolerant(raw: String, schemas: JSONArray, allowed: Set<String>): ParsedAction {
        try {
            return ParsedAction(parse(raw, schemas, allowed))
        } catch (strict: Exception) {
            val extracted = extractSingleObject(raw) ?: throw strict
            val action = parse(extracted, schemas, allowed)
            return ParsedAction(action, repaired = true, repairNote = "Extracted one JSON action from fenced or surrounding text.")
        }
    }

    private fun summary(obj: JSONObject): String? {
        if (!obj.has("summary")) return null
        val value = obj.get("summary") as? String ?: error("Summary must be a string.")
        require(value.length <= 500 && '\u0000' !in value) { "Summary exceeds its bound." }
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun extractSingleObject(raw: String): String? {
        if (raw.length !in 2..24_000) return null
        var text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.startsWith("```")) {
            val firstLine = text.lineSequence().first()
            val language = firstLine.removePrefix("```").trim().lowercase()
            if (language !in setOf("", "json")) return null
            val closing = text.indexOf("\n```", startIndex = firstLine.length)
            if (closing < 0) return null
            text = text.substring(firstLine.length, closing).trim()
        }
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = index
                        break
                    }
                    if (depth < 0) return null
                }
            }
        }
        if (end < 0) return null
        val candidate = text.substring(start, end + 1)
        if (candidate.length > 24_000) return null
        val remainder = text.substring(end + 1)
        if (remainder.contains('{') || remainder.contains('}')) return null
        return candidate
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

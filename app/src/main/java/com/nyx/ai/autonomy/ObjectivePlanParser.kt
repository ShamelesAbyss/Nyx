package com.nyx.ai.autonomy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import java.util.UUID

/** One validated task. Execution identity is supplied by the caller, never by the model. */
data class PlannedObjectiveTask(
    val id: String,
    val key: String,
    val title: String,
    val instructions: String,
    val verification: String,
    /** Stable UUID strings of prerequisite tasks, not model-controlled keys. */
    val dependsOn: List<String>,
    val agentId: String
)

class ObjectivePlanValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Parses bounded, strict JSON objective plans. The model controls only the task key,
 * title, instruction, dependencies, and verification text; all other properties are ignored.
 */
object ObjectivePlanValidator {
    const val MAX_JSON_BYTES = 65_536
    const val MAX_TASKS = 12
    const val MAX_KEY_LENGTH = 64
    const val MAX_TITLE_LENGTH = 160
    const val MAX_INSTRUCTION_LENGTH = 4_000
    const val MAX_VERIFY_LENGTH = 1_000

    private const val MAX_JSON_CHARS = 65_536
    private const val MAX_NESTING_DEPTH = 32
    private val taskKeyPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * @param rawJson model-produced JSON, bounded to 64 KiB in both UTF-8 bytes and chars.
     * @param objectiveId caller-owned stable objective identifier.
     * @param agentId fixed agent identity authorized by the caller; never taken from JSON.
     */
    fun parse(
        rawJson: String,
        objectiveId: String,
        agentId: String
    ): List<PlannedObjectiveTask> {
        requireExternalValue("objectiveId", objectiveId, 128)
        requireExternalValue("agentId", agentId, 128)

        if (rawJson.length > MAX_JSON_CHARS || rawJson.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) {
            invalid("Plan JSON exceeds the $MAX_JSON_BYTES-byte limit.")
        }
        try {
            StrictJsonSyntaxValidator(rawJson, MAX_NESTING_DEPTH).validate()
        } catch (e: ObjectivePlanValidationException) {
            throw e
        } catch (e: RuntimeException) {
            throw ObjectivePlanValidationException("Plan is not valid strict JSON.", e)
        }

        val root = try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            throw ObjectivePlanValidationException("Plan must be a JSON object.", e)
        }
        val taskArray = root.opt("tasks") as? JSONArray
            ?: invalid("Plan must contain a 'tasks' array.")
        if (taskArray.length() !in 1..MAX_TASKS) {
            invalid("Plan must contain between 1 and $MAX_TASKS tasks.")
        }

        data class ParsedTask(
            val key: String,
            val title: String,
            val instruction: String,
            val dependencyKeys: List<String>,
            val verify: String
        )

        val parsed = ArrayList<ParsedTask>(taskArray.length())
        val byKey = LinkedHashMap<String, Int>()
        for (index in 0 until taskArray.length()) {
            val obj = taskArray.optJSONObject(index)
                ?: invalid("Task at index $index must be a JSON object.")
            // `id` is accepted as a wire-format alias for the stable task key used by
            // the existing planner prompt. Its value is never used as the task UUID.
            val key = requiredAliasedString(obj, "key", "id", index, MAX_KEY_LENGTH)
            if (!taskKeyPattern.matches(key)) {
                invalid("Task key '$key' must use 1-$MAX_KEY_LENGTH ASCII letters, digits, '.', '_' or '-'; it must start with a letter or digit.")
            }
            if (byKey.putIfAbsent(key, index) != null) {
                invalid("Task key '$key' is duplicated.")
            }

            val title = requiredString(obj, "title", index, MAX_TITLE_LENGTH)
            val instruction = requiredAliasedString(obj, "instruction", "instructions", index, MAX_INSTRUCTION_LENGTH)
            val verify = requiredAliasedString(obj, "verify", "verification", index, MAX_VERIFY_LENGTH)
            val depends = obj.opt("dependsOn") as? JSONArray
                ?: invalid("Task '$key' must contain a 'dependsOn' array.")
            if (depends.length() > MAX_TASKS) {
                invalid("Task '$key' has more than $MAX_TASKS dependencies.")
            }
            val dependencyKeys = ArrayList<String>(depends.length())
            val seenDependencies = HashSet<String>()
            for (dependencyIndex in 0 until depends.length()) {
                val dependency = depends.opt(dependencyIndex) as? String
                    ?: invalid("Dependency $dependencyIndex of task '$key' must be a string key.")
                if (!taskKeyPattern.matches(dependency)) {
                    invalid("Dependency '$dependency' of task '$key' is not a valid task key.")
                }
                if (!seenDependencies.add(dependency)) {
                    invalid("Task '$key' lists dependency '$dependency' more than once.")
                }
                if (dependency == key) {
                    invalid("Task '$key' cannot depend on itself.")
                }
                dependencyKeys += dependency
            }
            parsed += ParsedTask(key, title, instruction, dependencyKeys, verify)
        }

        for (task in parsed) {
            for (dependency in task.dependencyKeys) {
                if (dependency !in byKey) {
                    invalid("Task '${task.key}' depends on missing task '$dependency'.")
                }
            }
        }

        val idsByKey = parsed.associate { it.key to stableTaskId(objectiveId, it.key) }
        if (idsByKey.values.toSet().size != parsed.size) {
            invalid("Stable task identifier collision detected.")
        }
        val outgoing = Array(parsed.size) { mutableListOf<Int>() }
        val indegree = IntArray(parsed.size)
        parsed.forEachIndexed { taskIndex, task ->
            task.dependencyKeys.forEach { dependencyKey ->
                val dependencyIndex = byKey.getValue(dependencyKey)
                outgoing[dependencyIndex] += taskIndex
                indegree[taskIndex]++
            }
        }

        // The input-index priority makes ordering deterministic when multiple tasks are ready.
        val ready = PriorityQueue<Int>()
        indegree.indices.filterTo(ready) { indegree[it] == 0 }
        val orderedIndices = ArrayList<Int>(parsed.size)
        while (ready.isNotEmpty()) {
            val current = ready.remove()
            orderedIndices += current
            for (dependent in outgoing[current]) {
                indegree[dependent]--
                if (indegree[dependent] == 0) ready += dependent
            }
        }
        if (orderedIndices.size != parsed.size) {
            invalid("Task dependencies contain a cycle.")
        }

        return orderedIndices.map { index ->
            val task = parsed[index]
            PlannedObjectiveTask(
                id = idsByKey.getValue(task.key).toString(),
                key = task.key,
                title = task.title,
                instructions = task.instruction,
                verification = task.verify,
                dependsOn = task.dependencyKeys.map { idsByKey.getValue(it).toString() },
                agentId = agentId
            )
        }
    }

    private fun stableTaskId(objectiveId: String, key: String): UUID {
        val name = "com.nyx.ai.autonomy.task:${objectiveId.length}:$objectiveId:${key.length}:$key"
        return UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8))
    }

    private fun requiredAliasedString(
        obj: JSONObject,
        primaryField: String,
        aliasField: String,
        taskIndex: Int,
        maxLength: Int
    ): String {
        if (obj.has(primaryField) && obj.has(aliasField)) {
            invalid("Task at index $taskIndex must not contain both '$primaryField' and '$aliasField'.")
        }
        val field = if (obj.has(primaryField)) primaryField else aliasField
        val value = obj.opt(field) as? String
            ?: invalid("Task at index $taskIndex must contain a string '$primaryField'.")
        if (value.isBlank()) invalid("Task at index $taskIndex has an empty '$field'.")
        if (value.length > maxLength) {
            invalid("Task at index $taskIndex has a '$field' longer than $maxLength characters.")
        }
        return value
    }

    private fun requiredString(
        obj: JSONObject,
        field: String,
        taskIndex: Int,
        maxLength: Int
    ): String {
        val value = obj.opt(field) as? String
            ?: invalid("Task at index $taskIndex must contain a string '$field'.")
        if (value.isBlank()) invalid("Task at index $taskIndex has an empty '$field'.")
        if (value.length > maxLength) {
            invalid("Task at index $taskIndex has a '$field' longer than $maxLength characters.")
        }
        return value
    }

    private fun requireExternalValue(name: String, value: String, maxLength: Int) {
        if (value.isBlank() || value.length > maxLength) {
            invalid("Caller-supplied $name must be non-empty and at most $maxLength characters.")
        }
    }

    private fun invalid(message: String): Nothing = throw ObjectivePlanValidationException(message)
}

/** Enforces RFC-style JSON grammar (including duplicate object-key rejection) before org.json. */
private class StrictJsonSyntaxValidator(
    private val source: String,
    private val maxDepth: Int
) {
    private var position = 0

    fun validate() {
        skipWhitespace()
        parseValue(0)
        skipWhitespace()
        if (position != source.length) fail("Trailing content after the JSON value.")
    }

    private fun parseValue(parentDepth: Int) {
        skipWhitespace()
        if (position >= source.length) fail("Unexpected end of JSON.")
        when (source[position]) {
            '{' -> parseObject(parentDepth + 1)
            '[' -> parseArray(parentDepth + 1)
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> fail("Unexpected character at position $position.")
        }
    }

    private fun parseObject(depth: Int) {
        checkDepth(depth)
        position++ // {
        skipWhitespace()
        if (consume('}')) return
        val keys = HashSet<String>()
        while (true) {
            skipWhitespace()
            if (position >= source.length || source[position] != '"') fail("Object keys must be quoted strings.")
            val key = parseString()
            if (!keys.add(key)) fail("Duplicate JSON object property '$key'.")
            skipWhitespace()
            expect(':')
            parseValue(depth)
            skipWhitespace()
            if (consume('}')) return
            expect(',')
        }
    }

    private fun parseArray(depth: Int) {
        checkDepth(depth)
        position++ // [
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue(depth)
            skipWhitespace()
            if (consume(']')) return
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (position < source.length) {
            val char = source[position++]
            when {
                char == '"' -> return value.toString()
                char == '\\' -> {
                    if (position >= source.length) fail("Unfinished string escape.")
                    when (val escape = source[position++]) {
                        '"', '\\', '/' -> value.append(escape)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (position + 4 > source.length) fail("Incomplete Unicode escape.")
                            var code = 0
                            repeat(4) {
                                val digit = source[position++].digitToIntOrNull(16)
                                    ?: fail("Invalid Unicode escape.")
                                code = (code shl 4) or digit
                            }
                            value.append(code.toChar())
                        }
                        else -> fail("Invalid string escape.")
                    }
                }
                char.code < 0x20 -> fail("Unescaped control character in string.")
                else -> value.append(char)
            }
        }
        fail("Unterminated JSON string.")
    }

    private fun parseNumber() {
        consume('-')
        if (consume('0')) {
            if (position < source.length && source[position] in '0'..'9') fail("Leading zero in number.")
        } else {
            parseDigits(requireAtLeastOne = true)
        }
        if (consume('.')) parseDigits(requireAtLeastOne = true)
        if (position < source.length && source[position] in "eE") {
            position++
            if (position < source.length && source[position] in "+-") position++
            parseDigits(requireAtLeastOne = true)
        }
    }

    private fun parseDigits(requireAtLeastOne: Boolean) {
        val start = position
        while (position < source.length && source[position] in '0'..'9') position++
        if (requireAtLeastOne && position == start) fail("Expected a digit.")
    }

    private fun parseLiteral(literal: String) {
        if (!source.startsWith(literal, position)) fail("Invalid JSON literal.")
        position += literal.length
    }

    private fun checkDepth(depth: Int) {
        if (depth > maxDepth) fail("JSON nesting exceeds the $maxDepth-level limit.")
    }

    private fun expect(char: Char) {
        if (!consume(char)) fail("Expected '$char' at position $position.")
    }

    private fun consume(char: Char): Boolean {
        if (position < source.length && source[position] == char) {
            position++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (position < source.length && source[position] in " \t\r\n") position++
    }

    private fun fail(message: String): Nothing = throw ObjectivePlanValidationException(message)
}

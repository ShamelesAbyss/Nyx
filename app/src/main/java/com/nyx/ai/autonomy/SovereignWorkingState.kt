package com.nyx.ai.autonomy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, bounded state that survives context compaction and process restarts. It records only
 * observed facts, requirements, open errors, the next intended action, and evidence pointers. It is
 * not a transcript and must never contain credentials or hidden reasoning.
 */
data class SovereignWorkingState(
    val objective: String,
    val task: String,
    val attempt: Int = 1,
    val facts: List<String> = emptyList(),
    val requirements: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val nextAction: String? = null,
    val evidence: List<String> = emptyList(),
    val completedSteps: List<String> = emptyList(),
) {
    fun withFact(value: String): SovereignWorkingState =
        copy(facts = boundedAppend(facts, value, MAX_FACTS))

    fun withRequirement(value: String): SovereignWorkingState =
        copy(requirements = boundedAppend(requirements, value, MAX_REQUIREMENTS))

    fun withError(value: String): SovereignWorkingState =
        copy(errors = boundedAppend(errors, value, MAX_ERRORS))

    fun withEvidence(value: String): SovereignWorkingState =
        copy(evidence = boundedAppend(evidence, value, MAX_EVIDENCE))

    fun withStep(value: String): SovereignWorkingState =
        copy(completedSteps = boundedAppend(completedSteps, value, MAX_STEPS))

    fun withNextAction(value: String?): SovereignWorkingState =
        copy(nextAction = value?.trim()?.take(MAX_FIELD_CHARS)?.takeIf { it.isNotEmpty() })

    fun promptBlock(maxChars: Int = 3_500): String {
        if (maxChars <= 0) return ""
        val output = StringBuilder()
        fun line(value: String) {
            if (output.length >= maxChars) return
            output.append(value.take(maxChars - output.length)).append('\n')
        }
        line("SOVEREIGN WORKING STATE (durable; verify before relying on it)")
        line("Objective: ${objective.take(800)}")
        line("Task: ${task.take(800)} · attempt $attempt")
        section("Established facts", facts, output, maxChars)
        section("Requirements", requirements, output, maxChars)
        section("Open errors", errors, output, maxChars)
        nextAction?.let { line("Next action: ${it.take(500)}") }
        section("Evidence", evidence, output, maxChars)
        section("Completed steps", completedSteps, output, maxChars)
        return output.toString().take(maxChars)
    }

    fun toJson(): String = JSONObject()
        .put("version", VERSION)
        .put("objective", objective.take(MAX_FIELD_CHARS))
        .put("task", task.take(MAX_FIELD_CHARS))
        .put("attempt", attempt.coerceAtLeast(1))
        .put("facts", JSONArray(facts.take(MAX_FACTS)))
        .put("requirements", JSONArray(requirements.take(MAX_REQUIREMENTS)))
        .put("errors", JSONArray(errors.take(MAX_ERRORS)))
        .put("nextAction", nextAction ?: JSONObject.NULL)
        .put("evidence", JSONArray(evidence.take(MAX_EVIDENCE)))
        .put("completedSteps", JSONArray(completedSteps.take(MAX_STEPS)))
        .toString()

    companion object {
        const val VERSION = 1
        private const val MAX_FACTS = 12
        private const val MAX_REQUIREMENTS = 8
        private const val MAX_ERRORS = 8
        private const val MAX_EVIDENCE = 8
        private const val MAX_STEPS = 12
        private const val MAX_FIELD_CHARS = 600

        fun start(objective: String, task: String, attempt: Int): SovereignWorkingState =
            SovereignWorkingState(
                objective = objective.trim().take(MAX_FIELD_CHARS),
                task = task.trim().take(MAX_FIELD_CHARS),
                attempt = attempt.coerceAtLeast(1),
            )

        fun fromJson(raw: String?): SovereignWorkingState? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                if (root.optInt("version", -1) != VERSION) return null
                val objective = root.optString("objective").trim().takeIf { it.isNotEmpty() } ?: return null
                val task = root.optString("task").trim().takeIf { it.isNotEmpty() } ?: return null
                SovereignWorkingState(
                    objective = objective.take(MAX_FIELD_CHARS),
                    task = task.take(MAX_FIELD_CHARS),
                    attempt = root.optInt("attempt", 1).coerceAtLeast(1),
                    facts = strings(root.optJSONArray("facts"), MAX_FACTS),
                    requirements = strings(root.optJSONArray("requirements"), MAX_REQUIREMENTS),
                    errors = strings(root.optJSONArray("errors"), MAX_ERRORS),
                    nextAction = if (root.isNull("nextAction")) null else
                        root.optString("nextAction").trim().take(MAX_FIELD_CHARS).takeIf { it.isNotEmpty() },
                    evidence = strings(root.optJSONArray("evidence"), MAX_EVIDENCE),
                    completedSteps = strings(root.optJSONArray("completedSteps"), MAX_STEPS),
                )
            }.getOrNull()
        }

        /** Reads state from the task checkpoint envelope or from a bare state document. */
        fun fromCheckpoint(raw: String?): SovereignWorkingState? {
            if (raw.isNullOrBlank()) return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            return fromJson(root.optString("sovereignState")) ?: fromJson(raw)
        }

        private fun strings(array: JSONArray?, limit: Int): List<String> {
            if (array == null) return emptyList()
            return (0 until minOf(array.length(), limit)).mapNotNull { index ->
                array.optString(index).trim().take(MAX_FIELD_CHARS).takeIf { it.isNotEmpty() }
            }
        }

        private fun boundedAppend(existing: List<String>, value: String, limit: Int): List<String> {
            val safe = SovereignPolicy.redact(value).replace(Regex("\\s+"), " ").trim().take(MAX_FIELD_CHARS)
            if (safe.isEmpty()) return existing
            val withoutDuplicate = existing.filterNot { it.equals(safe, ignoreCase = true) }
            return (withoutDuplicate + safe).takeLast(limit)
        }

        private fun section(heading: String, values: List<String>, output: StringBuilder, maxChars: Int) {
            if (values.isEmpty() || output.length >= maxChars) return
            output.append(heading).append(":\n")
            for (value in values) {
                if (output.length >= maxChars) break
                output.append("- ").append(value.take(maxChars - output.length)).append('\n')
            }
        }
    }
}

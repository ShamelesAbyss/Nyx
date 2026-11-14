package com.nyx.ai.autonomy

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Small, validated decisions for models that should not be asked for a whole graph at once.
 * Kotlin owns identifiers, dependencies, scheduling and evidence; the model supplies only the
 * next instruction and an allowed worker label.
 */
object IncrementalPlanning {
    const val MAX_EXPANSIONS = 6
    const val MAX_INSTRUCTION_CHARS = 600
    const val MAX_GOAL_CHARS = 800

    /** Deterministic plan for a simple text-only objective: no planning inference at all. */
    fun directPlan(goal: String, agentId: String): PlannedObjectiveTask {
        val instruction = goal.trim()
        require(instruction.isNotEmpty()) { "Direct execution requires a non-empty objective." }
        return PlannedObjectiveTask(
            id = UUID.randomUUID().toString(),
            key = "direct",
            title = "Answer the objective",
            instructions = instruction.take(ObjectivePlanValidator.MAX_INSTRUCTION_LENGTH),
            verification = "The response is non-empty and addresses the stated objective.",
            dependsOn = emptyList(),
            agentId = agentId,
            workerKind = "inference",
            evidence = listOf(
                EvidenceSpec(
                    kind = EvidenceKind.ARTIFACT_NONEMPTY,
                    description = "A non-empty assistant response exists.",
                ),
            ),
        )
    }

    fun prompt(
        goal: String,
        completedTitles: List<String>,
        allowedWorkers: Set<String>,
        guided: Boolean,
    ): String = buildString {
        val workers = allowedWorkers.sorted().joinToString(", ").ifBlank { "inference" }
        appendLine("Decide ONLY the next task. NYX owns identifiers, dependencies, permissions and verification.")
        appendLine("Objective: ${goal.trim().take(MAX_GOAL_CHARS)}")
        if (completedTitles.isNotEmpty()) appendLine("Already completed: ${completedTitles.takeLast(4).joinToString("; ").take(400)}")
        if (guided) {
            appendLine("Reply with exactly one line, no prose or code fences:")
            appendLine("NEXT <worker>: <one concrete instruction>")
            appendLine("or reply exactly DONE when no further task is needed.")
            appendLine("Allowed workers: $workers. Guided mode can only request inference work; tool work needs constrained JSON. An instruction is a request, never a permission or a raw shell command.")
        } else {
            appendLine("""Reply with ONLY one complete JSON object: {"next":{"do":"one concrete instruction","worker":"inference"}} or {"done":true}""")
            appendLine("""Tool workers also need a future local check: "check":{"kind":"file_exists","target":"path"} or {"kind":"exit_code","expected":"0"}.""")
            appendLine("Allowed workers: $workers. Use 1 task only; no dependencies, identifiers or shell commands. A plan grants no permission.")
        }
    }

    /**
     * @return the next validated task, or null when the model declared the objective complete.
     * A malformed or unauthorized decision is rejected rather than repaired into execution.
     */
    fun parseNext(
        raw: String,
        guided: Boolean,
        profile: PlanningProfile,
        objectiveId: String,
        agentId: String,
        dependsOn: List<String>,
    ): PlannedObjectiveTask? {
        val text = raw.trim()
        if (text.isEmpty()) invalid("Continuation response was empty.")
        if (text.length > ObjectivePlanValidator.MAX_JSON_BYTES) invalid("Continuation response exceeded its size limit.")
        // A guided model may still answer with valid JSON; accept only the strict schema.
        if (!guided || text.startsWith("{")) {
            val root = JSONObject(ObjectivePlanValidator.extractSingleObject(text))
            if (root.optBoolean("done", false)) return null
            val next = root.optJSONObject("next") ?: invalid("Continuation must contain a next task or done=true.")
            val wrapper = JSONObject().put("steps", JSONArray().put(next))
            val tasks = ObjectivePlanValidator.parseResponse(wrapper.toString(), objectiveId, agentId)
            if (tasks.size != 1) invalid("A continuation decision must contain exactly one task.")
            CompactPlanningPolicy.validateWorkers(tasks, profile)
            return tasks.single().copy(dependsOn = dependsOn)
        }
        if (text.equals("DONE", ignoreCase = true)) return null
        val match = guidedLine.matchEntire(text) ?: invalid("Guided decision must be one NEXT line or DONE.")
        val worker = match.groupValues[1].lowercase()
        val allowed = profile.allowedWorkers ?: CompactPlanningPolicy.ALL_WORKERS
        if (worker !in allowed) invalid("Guided decision selected an unavailable worker.")
        val instruction = match.groupValues[2].trim()
        if (instruction.isEmpty() || instruction.length > MAX_INSTRUCTION_CHARS) {
            invalid("Guided instruction must be 1-$MAX_INSTRUCTION_CHARS characters.")
        }
        val step = JSONObject().put("do", instruction).put("worker", worker)
        val wrapper = JSONObject().put("steps", JSONArray().put(step))
        val tasks = ObjectivePlanValidator.parseResponse(wrapper.toString(), objectiveId, agentId)
        if (tasks.size != 1) invalid("A continuation decision must contain exactly one task.")
        CompactPlanningPolicy.validateWorkers(tasks, profile)
        return tasks.single().copy(dependsOn = dependsOn)
    }

    private val guidedLine = Regex("(?is)^NEXT\\s+([a-z]+)\\s*[:\\-]\\s*(.{1,$MAX_INSTRUCTION_CHARS})$")

    private fun invalid(message: String): Nothing = throw ObjectivePlanValidationException(message)
}

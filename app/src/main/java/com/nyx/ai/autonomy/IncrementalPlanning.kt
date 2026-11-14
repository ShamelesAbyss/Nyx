package com.nyx.ai.autonomy

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Small validated decisions. Kotlin, not model output, owns identity, dependencies and authority. */
object IncrementalPlanning {
    const val MAX_EXPANSIONS = 6
    const val MAX_INSTRUCTION_CHARS = 600
    const val MAX_GOAL_CHARS = 800

    fun directPlan(goal: String, agentId: String): PlannedObjectiveTask {
        val instruction = goal.trim()
        require(instruction.isNotEmpty()) { "Direct execution requires a non-empty objective." }
        require(instruction.length <= ObjectivePlanValidator.MAX_INSTRUCTION_LENGTH) { "Direct objective exceeds its instruction limit; no prefix was accepted." }
        return PlannedObjectiveTask(
            id = UUID.randomUUID().toString(), key = "direct", title = "Answer the objective",
            instructions = instruction, verification = "The response is non-empty; semantic correctness is not proven.",
            dependsOn = emptyList(), agentId = agentId, workerKind = "inference",
            evidence = listOf(EvidenceSpec(EvidenceKind.ARTIFACT_NONEMPTY, description = "A non-empty assistant response exists.")),
        )
    }

    /** Never reuse the initial steps/do schema for next/done. Guided decisions remain inference-only. */
    fun continuationProfile(initial: PlanningProfile, guided: Boolean): PlanningProfile = initial.copy(
        compact = !guided, singleStep = false, maxSteps = 1, continuation = true, fullGraph = false,
        allowedWorkers = if (guided) setOf("inference") else initial.allowedWorkers,
        exitCodeWorkers = if (guided) emptySet() else initial.exitCodeWorkers,
        maxOutputTokens = minOf(initial.maxOutputTokens, if (guided) 160 else 384),
        maxContextTokens = minOf(initial.maxContextTokens ?: 2_048, 2_048),
    )

    fun responseSchema(profile: PlanningProfile): String {
        val step = JSONObject(CompactPlanningPolicy.responseSchemaFor(
            profile.allowedWorkers ?: CompactPlanningPolicy.ALL_WORKERS,
            allowExitCode = profile.exitCodeWorkers == null || profile.exitCodeWorkers.isNotEmpty(), maxSteps = 1,
        )).getJSONObject("properties").getJSONObject("steps").getJSONObject("items")
        step.getJSONObject("properties").getJSONObject("do").put("maxLength", MAX_INSTRUCTION_CHARS)
        fun branch(field: String, value: JSONObject) = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray().put(field)).put("properties", JSONObject().put(field, value))
        return JSONObject().put("oneOf", JSONArray()
            .put(branch("next", step))
            .put(branch("done", JSONObject().put("type", "boolean").put("enum", JSONArray().put(true))))).toString()
    }

    fun prompt(goal: String, completedTitles: List<String>, allowedWorkers: Set<String>, guided: Boolean): String = buildString {
        require(goal.trim().length <= MAX_GOAL_CHARS) { "Continuation objective exceeds its limit; no prefix was planned." }
        val workers = (if (guided) setOf("inference") else allowedWorkers).sorted().joinToString(", ").ifBlank { "inference" }
        appendLine("Decide ONLY the next task. NYX owns identifiers, dependencies, permissions and verification.")
        appendLine("Objective: ${goal.trim()}")
        if (completedTitles.isNotEmpty()) appendLine("Already completed: ${completedTitles.takeLast(4).joinToString("; ").take(400)}")
        if (guided) {
            appendLine("Reply with exactly one line, no prose or code fences:")
            appendLine("NEXT inference: <one concrete instruction>")
            appendLine("or reply exactly DONE when no further task is needed.")
            appendLine("Allowed workers: inference. Guided mode can only request inference work; tool work needs validated JSON. An instruction is never a permission or a raw shell command.")
        } else {
            appendLine("""Reply with ONLY one complete JSON object: {"next":{"do":"one concrete instruction","worker":"inference"}} or {"done":true}""")
            appendLine("""Tool workers need a future local check: "check":{"kind":"file_exists","target":"path"} or, only when command evidence is available, {"kind":"exit_code","expected":"0"}.""")
            appendLine("Allowed workers: $workers. Use 1 task only, at most $MAX_INSTRUCTION_CHARS instruction characters; no dependencies, identifiers or shell commands. A plan grants no permission.")
        }
    }

    /**
     * decisionKey is caller-owned and checkpoint-stable (e.g. continuation_1). Legacy callers use a
     * domain-separated dependency anchor, not step_1. Supply ALL existingTaskIds before persistence;
     * an existing row is never overwritten by a newly accepted decision. Dependencies must be
     * completed tasks owned by this objective; only the engine can establish that fact.
     */
    fun parseNext(
        raw: String, guided: Boolean, profile: PlanningProfile, objectiveId: String, agentId: String,
        dependsOn: List<String>, decisionKey: String? = null, existingTaskIds: Set<String> = emptySet(),
    ): PlannedObjectiveTask? {
        val text = raw.trim()
        if (text.isEmpty()) invalid("Continuation response was empty.")
        if (text.length > ObjectivePlanValidator.MAX_JSON_BYTES) invalid("Continuation response exceeded its size limit.")
        val step: JSONObject
        if (!guided || text.startsWith("{")) {
            val root = ObjectivePlanValidator.parseStrictObject(text)
            val keys = root.keys().asSequence().toSet()
            if (keys == setOf("done") && root.opt("done") == true) return null
            if (keys != setOf("next")) invalid("Continuation must contain exactly next or done=true, without other fields.")
            step = root.opt("next") as? JSONObject ?: invalid("Continuation next must be an object.")
        } else {
            if (text.equals("DONE", ignoreCase = true)) return null
            val match = guidedLine.matchEntire(text) ?: invalid("Guided decision must be one NEXT line or DONE.")
            step = JSONObject().put("do", match.groupValues[2].trim()).put("worker", match.groupValues[1].lowercase())
        }
        val instruction = step.opt("do") as? String ?: invalid("Continuation instruction must be a string.")
        if (instruction.isBlank() || instruction.length > MAX_INSTRUCTION_CHARS) invalid("Continuation instruction must be 1-$MAX_INSTRUCTION_CHARS characters.")
        if (guided && step.opt("worker") != "inference") invalid("Guided decisions cannot request tool workers, even using JSON.")
        val effective = continuationProfile(profile, guided)
        val expanded = CompactPlanningPolicy.expand(JSONObject().put("steps", JSONArray().put(step)))
        val key = decisionKey ?: "continuation_" + UUID.nameUUIDFromBytes(
            JSONArray(dependsOn.sorted()).toString().toByteArray(StandardCharsets.UTF_8)).toString().replace("-", "")
        if (!Regex("continuation_[A-Za-z0-9._-]{1,48}").matches(key)) invalid("Caller continuation key must be bounded and domain-separated.")
        expanded.getJSONArray("tasks").getJSONObject(0).put("id", key)
        val tasks = ObjectivePlanValidator.parseResponse(expanded.toString(), objectiveId, agentId)
        CompactPlanningPolicy.validateWorkers(tasks, effective)
        val task = tasks.single()
        if (dependsOn.size > ObjectivePlanValidator.MAX_TASKS || dependsOn.any { it.isBlank() } || dependsOn.distinct().size != dependsOn.size)
            invalid("Continuation prerequisites are invalid or duplicated.")
        if (task.id in dependsOn || task.id in existingTaskIds) invalid("Continuation identifier collides with existing task state or its own prerequisite.")
        return task.copy(dependsOn = dependsOn)
    }

    private val guidedLine = Regex("(?i)^NEXT\\s+([a-z]+)\\s*[:\\-]\\s*([^\\r\\n]{1,$MAX_INSTRUCTION_CHARS})$")
    private fun invalid(message: String): Nothing = throw ObjectivePlanValidationException(message)
}

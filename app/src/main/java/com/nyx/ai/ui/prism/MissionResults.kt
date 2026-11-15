package com.nyx.ai.ui.prism

import com.nyx.ai.data.*
import com.nyx.ai.autonomy.QuickAction
import com.nyx.ai.autonomy.QuickActionType
import com.nyx.ai.autonomy.WorkerActivityBus
import com.nyx.ai.workbench.WorkbenchExecutionConfiguration
import com.nyx.ai.workbench.WorkbenchManifest
import org.json.JSONObject

/** A read-only projection. No provider, filesystem, terminal, network or authorization API. */
data class MissionFact(val label: String, val value: String)
data class MissionOutput(val title: String, val text: String, val verified: Boolean)
data class MissionResult(val objectiveId: String, val title: String, val status: String, val summary: String,
    val verification: String, val facts: List<MissionFact>, val outputs: List<MissionOutput>, val quickAction: Boolean,
    val needsReview: Boolean, val copyText: String)

object MissionResults {
    private fun json(value: String?): JSONObject? {
        if (value == null || value.length > 64_000) return null
        var depth = 0; var quoted = false; var escape = false
        for (character in value) {
            if (quoted) {
                if (escape) escape = false else if (character == '\\') escape = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) return null }
                '}', ']' -> { depth--; if (depth < 0) return null }
            }
        }
        return runCatching { JSONObject(value) }.getOrNull()
    }
    private fun clean(value: String) = WorkerActivityBus.sanitizeTerminalText(WorkerActivityBus.redact(value))
    fun present(objective: ObjectiveEntity, tasks: List<ObjectiveTaskEntity>, events: List<ObjectiveEventEntity>,
                reports: List<ObjectiveArtifactEntity> = emptyList(), manifest: WorkbenchManifest? = null): MissionResult {
        val owned = tasks.filter { it.objectiveId == objective.id }.sortedBy { it.ordinal }
        val ids = owned.map { it.id }.toSet()
        val history = events.filter { it.objectiveId == objective.id && (it.taskId == null || it.taskId in ids) }
        val snapshot = WorkbenchExecutionConfiguration.latest(history.filter { it.kind == "execution_config" }.mapNotNull { json(it.detail) })
        val action = runCatching { snapshot?.let { QuickAction.fromSnapshot(it) }?.validate(objective.id) }.getOrNull()
        fun checks(task: ObjectiveTaskEntity): List<JSONObject> = json(task.evidenceJson)?.optJSONArray("results")?.let { array ->
            if ((0 until array.length()).any { array.optJSONObject(it) == null }) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
        }.orEmpty()
        fun passed(task: ObjectiveTaskEntity): Boolean = task.status == "completed" && task.verificationStatus == "passed" &&
            checks(task).let { it.isNotEmpty() && it.all { check -> check.opt("passed") == true } }
        val verified = owned.filter(::passed)
        val checkCount = owned.sumOf { checks(it).size }
        val passing = owned.sumOf { checks(it).count { check -> check.opt("passed") == true } }
        val verification = if (checkCount == 0) "No local check details loaded" else "$passing/$checkCount recorded local checks passed"
        val facts = mutableListOf<MissionFact>()
        val workspace = action?.workspace?.ifBlank { null } ?: snapshot?.optString("workspace")?.takeIf { it.isNotBlank() }
        workspace?.let { facts += MissionFact("Project location", clean(it)) }
        snapshot?.optString("environment")?.takeIf { it.isNotBlank() }?.let { facts += MissionFact("Selected environment", clean(it.replace('_', ' '))) }
        val elapsed = objective.completedAt?.minus(objective.createdAt)?.takeIf { it >= 0 }
        if (elapsed != null) facts += MissionFact("Recorded elapsed time", if (elapsed < 1_000) "$elapsed ms" else "${elapsed / 1000}s")
        facts += MissionFact("Task results", "${verified.size}/${owned.size} tasks have passing local evidence")
        var summary = when (objective.status) {
            "completed" -> if (verified.isNotEmpty() && verified.size == owned.size) "Mission completed. The recorded results and local checks are below." else "Recorded complete; local verification details are incomplete. Review technical records before relying on this result."
            "planning", "pending" -> "The mission has not produced a verified result yet. Planning or authorization may still be in progress."
            "running" -> "Work is in progress. Verified task results appear here as they are recorded."
            "paused" -> "Mission paused. Review completed work and uncertain operations before continuing."
            "cancelled" -> "Mission cancelled. Already dispatched tools may have changed project files."
            else -> "This mission needs attention. Review the observed reason and completed task results below."
        }
        objective.error?.takeIf { it.isNotBlank() }?.let { facts += MissionFact("Observed reason", clean(it.lineSequence().firstOrNull().orEmpty())) }
        if (objective.status in setOf("paused", "needs_attention", "failed", "cancelled")) {
            facts += MissionFact("Execution", if (owned.any { it.attemptCount > 0 }) "One or more tasks were attempted; an attempt is not proof of success." else "No task attempt is recorded.")
            facts += MissionFact("Recovery", if (action != null) "Select and explicitly approve a new Quick Action. No automatic replay." else "Review any possible side effects before explicitly resuming.")
        }
        if (action?.type == QuickActionType.INSPECT_PROJECT) {
            val task = verified.singleOrNull()
            val inspection = task?.outcome?.let(::json)?.takeIf { it.optString("path") == action.workspace }
            val observation = history.lastOrNull { event -> event.kind == "quick_operation_evidence" && event.taskId == task?.id &&
                json(event.detail)?.let { it.optString("objectiveId") == objective.id && it.optString("taskId") == task?.id &&
                    it.optString("tool") == "project_inspect" && (it.opt("exitCode") as? Number)?.toDouble() == 0.0 } == true }
            if (inspection != null && observation != null) {
                val raw = observation.toolOutput.orEmpty().lines()
                val empty = inspection.optString("projectType") == "empty" || raw.any { it == "EMPTY:1" } && raw.none { it == "EMPTY:0" }
                summary = if (empty) "Inspection completed: this workspace was observed to be empty. No project files were created or modified." else "Inspection completed. Observed project information is shown below; no project scripts were run."
                facts += MissionFact("Workspace", if (empty) "Empty at inspection time" else "Project contents were observed")
                facts += MissionFact("Project type", inspection.optString("projectType").ifBlank { "Not detected" })
                val markers = inspection.optJSONArray("markers")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                facts += MissionFact("Build markers", markers.joinToString().ifBlank { "None recorded" })
                val sources = raw.filter { it.startsWith("SOURCE:") }.map { it.removePrefix("SOURCE:") }
                facts += MissionFact("Observed root sources", sources.joinToString().ifBlank { "No root source names retained in this observation" })
                val tools = raw.filter { it.startsWith("TOOL:") }.map { it.removePrefix("TOOL:").substringBefore('=') }.filter { Regex("[A-Za-z0-9_-]{1,40}").matches(it) }.distinct()
                facts += MissionFact("Available tools observed", tools.joinToString().ifBlank { "Tool listing unavailable in retained output" })
                facts += MissionFact("Development readiness", if (inspection.opt("ready") == true) "Existing fixed build/test procedures were discovered" else inspection.optString("reason").ifBlank { "Not established" })
            } else facts += MissionFact("Inspection details", "A complete owned inspection observation is not available. Technical records remain accessible.")
        }
        if (action?.type == QuickActionType.RUN_TESTS) {
            val execution = history.lastOrNull { it.kind == "quick_project_execution" }?.let { event -> json(event.detail)?.takeIf {
                it.optString("objectiveId") == objective.id && it.optString("taskId") == event.taskId && it.optString("workspace") == action.workspace && it.optString("kind") == "TEST"
            } }
            if (execution != null) {
                facts += MissionFact("Test outcome", "${execution.optString("resultKind")} · exit ${execution.opt("exitCode")?.takeIf { it != JSONObject.NULL } ?: "unavailable"}")
                facts += MissionFact("Recorded fixed procedure", execution.optString("command"))
                if (verified.size == owned.size && verified.isNotEmpty()) summary = "The approved fixed project tests completed with passing local evidence. Actual output is below."
            }
        }
        if (action?.type == QuickActionType.VERIFY_ARTIFACT && verified.size == owned.size && verified.isNotEmpty()) {
            summary = "The existing Workbench output passed its retained-byte integrity check. No new artifact file was produced."
            facts += MissionFact("Original objective", action.sourceObjectiveId)
            facts += MissionFact("Artifact identity", action.artifactId)
            facts += MissionFact("Integrity", "Recorded SHA-256 ${action.sha256}. This does not prove factual accuracy.")
        }
        owned.filter { it.workerKind == "development" && it.outcome != null }.forEach { task ->
            task.outcome!!.lineSequence().firstOrNull { it.startsWith("Changed files:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }?.let {
                facts += MissionFact("Recorded edited paths", "$it (execution report; current file bytes are not rechecked by this view)")
            }
        }
        if (manifest?.objectiveId == objective.id) {
            facts += MissionFact("Registered Workbench files", manifest.artifacts.size.toString() + " · checked again on preview/save/share")
            manifest.sources.filter { it.status == "FETCHED" }.forEach { facts += MissionFact("Retained source record", it.url) }
        }
        val outputs = owned.mapNotNull { task -> task.outcome?.takeIf { it.isNotBlank() }?.let {
            MissionOutput(clean(task.title), clean(it), passed(task))
        } }.toMutableList()
        if (outputs.isEmpty()) reports.filter { it.objectiveId == objective.id && it.taskId in ids }.forEach {
            outputs += MissionOutput(clean(it.name), clean(it.content), owned.any { task -> task.id == it.taskId && passed(task) })
        }
        // Inspection JSON is available secondarily; the primary result is the deterministic facts above.
        val presented = when {
            action?.type == QuickActionType.INSPECT_PROJECT && facts.any { it.label == "Project type" } -> emptyList()
            action?.type == QuickActionType.VERIFY_ARTIFACT && verified.isNotEmpty() -> emptyList()
            action?.type == QuickActionType.RUN_TESTS -> outputs.map { output ->
                val result = json(output.text)
                if (result == null) output else output.copy(title = "Observed test output", text = listOf(result.optString("stdout"), result.optString("stderr")).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "The process returned no retained output text." })
            }
            else -> outputs
        }
        val cleanedFacts = facts.map { MissionFact(clean(it.label), clean(it.value)) }
        val copy = buildString { appendLine(clean(objective.title)); appendLine("Status: ${objective.status}"); appendLine(summary); appendLine(verification)
            cleanedFacts.forEach { appendLine("${it.label}: ${it.value}") }; presented.forEach { appendLine("${it.title}:\n${it.text}") } }
        return MissionResult(objective.id, clean(objective.title), objective.status, summary, verification, cleanedFacts, presented,
            action != null, objective.status in setOf("paused", "failed", "needs_attention"), copy)
    }
}

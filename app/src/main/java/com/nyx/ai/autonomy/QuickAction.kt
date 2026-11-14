package com.nyx.ai.autonomy

import com.nyx.ai.agent.AgentOutcome
import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.development.DevelopmentPaths
import com.nyx.ai.network.ToolCall
import com.nyx.ai.terminal.TerminalAgentTools
import com.nyx.ai.workbench.WorkbenchStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class QuickActionType(val label: String) {
    INSPECT_PROJECT("Inspect project"), RUN_TESTS("Run approved project tests"), VERIFY_ARTIFACT("Verify Workbench output")
}

/** Local, explicit, immutable selection. Never constructed from a model response or goal text. */
data class QuickAction(
    val ownerId: String,
    val type: QuickActionType,
    val approved: Boolean,
    val workspace: String = "",
    val sourceObjectiveId: String = "",
    val artifactId: String = "",
    val sha256: String = "",
) {
    fun validate(objectiveId: String): QuickAction {
        require(ownerId == objectiveId && safeId(ownerId)) { "Quick Action objective ownership mismatch." }
        require(approved) { "Explicit action permission is required." }
        if (type == QuickActionType.VERIFY_ARTIFACT) {
            require(workspace.isEmpty() && sourceObjectiveId != ownerId && safeId(sourceObjectiveId) && safeId(artifactId) && Regex("[a-f0-9]{64}").matches(sha256)) { "Select an existing owned artifact and exact digest." }
        } else {
            require(workspace.length <= 512 && DevelopmentPaths.isAbsoluteNormalizedWorkspace(workspace)) { "Select a normalized authorized project workspace." }
            require(sourceObjectiveId.isEmpty() && artifactId.isEmpty() && sha256.isEmpty()) { "Project actions cannot carry artifact authority." }
        }
        return this
    }

    fun task(agentId: String): PlannedObjectiveTask {
        validate(ownerId)
        return PlannedObjectiveTask(UUID.randomUUID().toString(), "quick_action", "Quick Action: ${type.label}",
            "Execute only the explicitly selected ${type.name} contract.", "Check actual owned operation evidence.",
            emptyList(), agentId, "verification",
            listOf(if (type == QuickActionType.RUN_TESTS) EvidenceSpec(EvidenceKind.EXIT_CODE, expected = "0")
                else EvidenceSpec(EvidenceKind.ARTIFACT_NONEMPTY)))
    }

    fun toJson(): JSONObject = JSONObject().put("ownerId", ownerId).put("type", type.name).put("approved", approved)
        .put("workspace", workspace).put("sourceObjectiveId", sourceObjectiveId).put("artifactId", artifactId).put("sha256", sha256)

    companion object {
        const val KEY = "quickAction"
        private fun safeId(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}").matches(value)
        fun fromSnapshot(snapshot: JSONObject): QuickAction? = snapshot.optJSONObject(KEY)?.let { j ->
            require(j.keys().asSequence().toSet() == setOf("ownerId", "type", "approved", "workspace", "sourceObjectiveId", "artifactId", "sha256") &&
                j.get("approved") is Boolean && listOf("ownerId", "type", "workspace", "sourceObjectiveId", "artifactId", "sha256").all { j.get(it) is String }) { "Unexpected Quick Action fields or types." }
            QuickAction(j.getString("ownerId"), QuickActionType.valueOf(j.getString("type")), j.getBoolean("approved"),
                j.getString("workspace"), j.getString("sourceObjectiveId"), j.getString("artifactId"), j.getString("sha256")).validate(j.getString("ownerId"))
        }
    }
}

/** Exact calls into existing tools/storage. No provider, prompt, shell string or privilege inference. */
object QuickActionOperation {
    suspend fun run(action: QuickAction, context: AgentToolTaskContext, terminal: TerminalAgentTools?,
                    artifactRoot: File, sourceExists: suspend (String) -> Boolean): AgentOutcome {
        action.validate(context.objectiveId)
        val started = System.currentTimeMillis()
        val output: String
        val evidence: List<ToolExecutionEvidence>
        if (action.type == QuickActionType.VERIFY_ARTIFACT) {
            require(terminal == null) { "Artifact verification cannot inherit terminal authority." }
            require(sourceExists(action.sourceObjectiveId)) { "Selected source objective no longer exists." }
            val store = WorkbenchStore(artifactRoot, action.sourceObjectiveId)
            val record = store.listArtifacts().singleOrNull { it.id == action.artifactId } ?: error("Selected artifact is unavailable.")
            require(record.sha256 == action.sha256 && store.verifyArtifact(record)) { "Artifact ownership/integrity check failed." }
            output = JSONObject().put("sourceObjectiveId", store.objectiveId).put("artifactId", record.id)
                .put("name", record.fileName).put("sha256", record.sha256).put("bytes", record.bytes)
                .put("verified", true).put("note", "Existing retained bytes verified; no new file produced. Checksums do not prove factual accuracy.").toString()
            evidence = listOf(ToolExecutionEvidence("quick_artifact_verify", stdout = output, startedAt = started,
                objectiveId = context.objectiveId, taskId = context.taskId))
        } else {
            val tools = requireNotNull(terminal) { "Explicit project permission and terminal availability are required." }
            require(tools.workspaceRoot == action.workspace) { "Unauthorized workspace; saved selection cannot be rebound." }
            tools.drainEvidence(); tools.drainProjectExecutions()
            val name = if (action.type == QuickActionType.RUN_TESTS) "test_run" else "project_inspect"
            output = tools.execute(ToolCall("quick-${context.taskId}", name, JSONObject()))
            evidence = tools.drainEvidence()
            require(evidence.isNotEmpty() && evidence.all { it.objectiveId == context.objectiveId && it.taskId == context.taskId }) { "Operation failed or lacked successful owned evidence." }
            if (action.type == QuickActionType.INSPECT_PROJECT) {
                require(evidence.all { it.exitCode == 0 }) { "Inspection command failed." }
                require(JSONObject(output).getString("path") == action.workspace) { "Inspection did not return the selected workspace." }
            }
        }
        return AgentOutcome(output, emptyList(), 0, evidence)
    }
}

/** No provider is constructed, probed, loaded or contacted for deterministic work. */
fun quickActionSession(timeouts: ExecutionTimeoutPolicy): InferenceSession = InferenceSession(
    object : com.nyx.ai.network.InferenceProvider {
        override val id = "quick-action"
        override val displayName = "Deterministic Quick Action"
        override val kind = "deterministic"
        override val local = true
        override suspend fun capabilities(): com.nyx.ai.network.ProviderCapabilities = error("Quick Actions do not probe models")
        override suspend fun listModels(): List<com.nyx.ai.network.LocalModel> = error("Quick Actions do not list models")
        override suspend fun chat(model: String, messages: List<com.nyx.ai.network.ApiMessage>, tools: org.json.JSONArray?,
            options: com.nyx.ai.network.InferenceOptions, onToken: (String) -> Unit): com.nyx.ai.network.ChatResult = error("Quick Actions cannot request inference")
    }, "quick-action", com.nyx.ai.network.InferenceOptions(executionTimeouts = timeouts), timeouts)

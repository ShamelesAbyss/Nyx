package com.nyx.ai.workbench

import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.development.ProjectRunKind
import com.nyx.ai.terminal.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-selected generated output; never inferred from model prose or an arbitrary filesystem path. */
data class CombinedOutput(val selectionId: String, val relativePath: String, val fileName: String, val format: ArtifactFormat)

data class CombinedDevelopmentApproval(
    val objectiveId: String = "", // Draft only; bound once before execution_config is persisted.
    val environment: TerminalEnvironment,
    val guestAlias: String,
    val workspaceRoot: String,
    val outputs: List<CombinedOutput>,
) {
    fun validate(bound: Boolean = false): CombinedDevelopmentApproval {
        require(!bound || objectiveId.isNotBlank()) { "Combined approval must be bound to its original objective." }
        TerminalOutputApproval(objectiveId.ifBlank { "draft" }, environment, guestAlias, workspaceRoot,
            outputs.map { TerminalOutputSelection(it.selectionId, it.relativePath) })
        require(outputs.map { it.fileName.lowercase() }.distinct().size == outputs.size) { "Duplicate output destination." }
        outputs.forEach {
            require(it.format in setOf(ArtifactFormat.TEXT, ArtifactFormat.MARKDOWN, ArtifactFormat.JSON, ArtifactFormat.CSV, ArtifactFormat.HTML)) {
                "Combined outputs support bounded text/JSON/CSV/static HTML only; no JAR/APK/PDF/ZIP."
            }
        }
        return this
    }
    fun bind(id: String): CombinedDevelopmentApproval {
        require(objectiveId.isBlank() || objectiveId == id) { "Cannot rebind combined authority to another objective." }
        return copy(objectiveId = id, outputs = outputs.toList()).validate(bound = true)
    }
    fun terminalApproval(id: String): TerminalOutputApproval {
        validate(bound = true)
        require(objectiveId == id) { "Foreign combined objective." }
        return TerminalOutputApproval(id, environment, guestAlias, workspaceRoot, outputs.map { TerminalOutputSelection(it.selectionId, it.relativePath) })
    }
    fun toJson(): JSONObject = JSONObject().put("objectiveId", objectiveId).put("environment", environment.name)
        .put("guestAlias", guestAlias).put("workspaceRoot", workspaceRoot).put("outputs", JSONArray().apply {
            outputs.forEach { put(JSONObject().put("selectionId", it.selectionId).put("relativePath", it.relativePath)
                .put("fileName", it.fileName).put("format", it.format.name)) }
        })
    companion object {
        fun fromJson(json: JSONObject): CombinedDevelopmentApproval {
            val outputs = json.getJSONArray("outputs")
            require(outputs.length() in 1..8) { "Select 1–8 combined outputs." }
            return CombinedDevelopmentApproval(json.getString("objectiveId"), TerminalEnvironment.valueOf(json.getString("environment")),
                json.getString("guestAlias"), json.getString("workspaceRoot"), (0 until outputs.length()).map { index ->
                    val output = outputs.getJSONObject(index)
                    CombinedOutput(output.getString("selectionId"), output.getString("relativePath"), output.getString("fileName"), ArtifactFormat.valueOf(output.getString("format")))
                }).validate(bound = true)
        }
    }
}

/** Shared with terminal transactions; no second scheduler and no claims of OS isolation. */
object CombinedDevelopmentTransaction {
    suspend fun <T> run(approval: CombinedDevelopmentApproval, task: AgentToolTaskContext, block: suspend () -> T): T {
        approval.terminalApproval(task.objectiveId)
        return TerminalToolWorkspaceRegistry.withLease(TerminalOperationContext(
            objectiveId = task.objectiveId, taskId = task.taskId,
            namespace = if (approval.environment == TerminalEnvironment.DEBIAN_PROOT) "proot:${approval.guestAlias}" else "termux_host",
            workspace = approval.workspaceRoot, readOnly = false), block)
    }
}

/** These policies are called by the existing objective engine, not exposed as model tools. */
object CombinedDevelopmentRegistration {
    const val REGISTER = "workbench_register_output"
    fun successfulCommands(approval: CombinedDevelopmentApproval, task: AgentToolTaskContext,
                           executions: List<FixedProjectExecution>): List<FixedProjectExecution> {
        approval.terminalApproval(task.objectiveId)
        require(executions.size in 2..32) { "Actual fixed build AND behavioral-test observations are required." }
        require(executions.all { it.objectiveId == task.objectiveId && it.taskId == task.taskId &&
            it.environment == approval.environment && it.guestAlias == approval.guestAlias && it.workspaceRoot == approval.workspaceRoot &&
            it.command.isNotBlank() && it.infrastructureOk && it.startedAt <= it.finishedAt &&
            runCatching { UUID.fromString(it.operationId).toString() == it.operationId }.getOrDefault(false) }) {
            "Foreign, incomplete or uncertain project process evidence."
        }
        require(executions.map { it.operationId }.distinct().size == executions.size) { "Reused process operation identity." }
        val final = executions.takeLast(2)
        require(final.map { it.kind } == listOf(ProjectRunKind.BUILD, ProjectRunKind.TEST) &&
            final.all { it.completedSuccessfully } && final[0].finishedAt <= final[1].startedAt) {
            "Latest fixed build and subsequent behavioral tests must both complete successfully."
        }
        return final
    }

    suspend fun register(approval: WorkbenchAuthorization, store: WorkbenchStore, task: AgentToolTaskContext,
                         terminal: TerminalAgentTools, executions: List<FixedProjectExecution>,
                         checkpoint: suspend (String, String) -> Unit): List<ToolExecutionEvidence> {
        val combined = requireNotNull(approval.validate().combinedDevelopment) { "Explicit combined development approval is required." }
        require(store.objectiveId == task.objectiveId) { "Foreign Workbench store." }
        val final = successfulCommands(combined, task, executions)
        // Collect and validate ALL snapshots before registering any. No raw Android/guest File opening.
        val snapshots = combined.outputs.map { output ->
            currentCoroutineContext().ensureActive()
            val snapshot = requireNotNull(terminal.snapshotOutput(output.selectionId)) { "Selected output unavailable or unsafe: ${output.selectionId}" }
            validateSnapshot(combined, task, output, snapshot)
            output to snapshot
        }
        require(snapshots.map { it.second.operationId }.distinct().size == snapshots.size &&
            snapshots.none { pair -> executions.any { it.operationId == pair.second.operationId } }) { "Reused output snapshot operation identity." }
        val writes = mutableListOf<ToolExecutionEvidence>()
        for ((output, snapshot) in snapshots) {
            currentCoroutineContext().ensureActive()
            val provenance = JSONObject().put("objectiveId", task.objectiveId).put("taskId", task.taskId)
                .put("environment", combined.environment.name).put("guestAlias", combined.guestAlias).put("workspaceRoot", combined.workspaceRoot)
                .put("selectionId", output.selectionId).put("relativePath", output.relativePath).put("snapshotOperationId", snapshot.operationId)
                .put("bytes", snapshot.sizeBytes).put("sha256", snapshot.sha256).put("fileName", output.fileName)
                .put("buildOperationId", final[0].operationId).put("testOperationId", final[1].operationId)
                .put("buildExit", final[0].exitCode).put("testExit", final[1].exitCode)
            // A failed durable checkpoint prevents registration/witness; a crash never synthesizes success.
            checkpoint("selected_output_observed", provenance.toString())
            val started = System.currentTimeMillis()
            val record = store.registerVerifiedSnapshot(output.fileName, output.format, snapshot.copyBytes(), snapshot.sizeBytes.toLong(), snapshot.sha256)
            val path = "workbench/${record.fileName}"
            val json = WorkbenchAgentTools.recordJson(record).put("path", path).put("terminalProvenance", provenance)
            checkpoint("selected_output_registered", json.toString())
            writes += ToolExecutionEvidence(REGISTER, 0, json.toString(), files = listOf(path), mutating = true,
                startedAt = started, finishedAt = System.currentTimeMillis(), objectiveId = task.objectiveId, taskId = task.taskId)
        }
        return writes
    }

    internal fun validateSnapshot(approval: CombinedDevelopmentApproval, task: AgentToolTaskContext,
                                  output: CombinedOutput, snapshot: TerminalOutputSnapshot) {
        require(snapshot.objectiveId == task.objectiveId && snapshot.taskId == task.taskId &&
            snapshot.environment == approval.environment && snapshot.guestAlias == approval.guestAlias &&
            snapshot.workspaceRoot == approval.workspaceRoot && snapshot.selectionId == output.selectionId && snapshot.relativePath == output.relativePath) {
            "Selected output snapshot ownership mismatch."
        }
        require(UUID.fromString(snapshot.operationId).toString() == snapshot.operationId && snapshot.sizeBytes in 1..128_000 &&
            snapshot.sha256.matches(Regex("[0-9a-f]{64}")) && WorkbenchStore.sha256(snapshot.copyBytes()) == snapshot.sha256) {
            "Invalid complete terminal output snapshot."
        }
    }
}

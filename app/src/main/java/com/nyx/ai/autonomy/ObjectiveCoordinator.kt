package com.nyx.ai.autonomy

import android.content.Context
import com.nyx.ai.agent.AgentEvent
import com.nyx.ai.agent.AgentRunner
import com.nyx.ai.agent.AgentSpec
import com.nyx.ai.agent.AgentTools
import com.nyx.ai.data.AgentEntity
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.data.ObjectiveArtifactEntity
import com.nyx.ai.data.ObjectiveEntity
import com.nyx.ai.data.ObjectiveEventEntity
import com.nyx.ai.data.ObjectiveTaskEntity
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.OllamaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable, single-inference objective coordinator. It deliberately executes one task at a time
 * (rather than overwhelming a phone or local model server) and never retries an uncertain task
 * without an explicit user resume action.
 */
class ObjectiveCoordinator(
    context: Context,
    private val database: NyxDatabase,
    private val client: OllamaClient
) {
    private val app = context.applicationContext
    private val dao get() = database.objectiveDao()

    suspend fun planAndExecute(
        initial: ObjectiveEntity,
        agent: AgentEntity,
        options: InferenceOptions,
        onToken: (String) -> Unit = {}
    ): ObjectiveEntity {
        val modelId = requireNotNull(initial.modelId?.takeIf { it.isNotBlank() }) { "Choose a model before planning this objective." }
        dao.upsertObjective(initial.copy(status = "planning", agentId = agent.id, modelId = modelId, updatedAt = System.currentTimeMillis(), error = null))
        event(initial.id, null, "planning", "Generating a bounded task graph; model output is validated before execution.")
        try {
            val planResponse = client.chat(
                modelId,
                listOf(
                    ApiMessage("system", PLAN_SYSTEM),
                    ApiMessage("user", "Create an executable plan for this objective. Use only tasks that can be attempted through the selected agent's existing allowlisted tools. Do not include shell commands, arbitrary tools, permission changes, or claims of external access.\n\nObjective:\n${initial.goal.take(8_000)}")
                ),
                tools = null,
                options = options
            )
            val parsed = ObjectivePlanValidator.parse(planResponse.content, initial.id, agent.id)
            require(parsed.isNotEmpty()) { "The planner returned no executable tasks." }
            dao.upsertTasks(parsed.mapIndexed { index, task ->
                ObjectiveTaskEntity(
                    id = task.id,
                    objectiveId = initial.id,
                    ordinal = index,
                    title = task.title,
                    instructions = task.instructions,
                    verification = task.verification,
                    dependsOnJson = JSONArray(task.dependsOn).toString(),
                    status = "pending",
                    attemptCount = 0,
                    maxAttempts = 2,
                    outcome = null,
                    error = null,
                    updatedAt = System.currentTimeMillis()
                )
            })
            dao.upsertObjective(initial.copy(status = "running", agentId = agent.id, updatedAt = System.currentTimeMillis(), error = null))
            event(initial.id, null, "plan_created", "Validated ${parsed.size} tasks. Execution is sequential; only tool allowlists attached to the selected agent are available.")
            return executeReady(initial.id, modelId, agent, options, onToken)
        } catch (cancel: CancellationException) {
            dao.upsertObjective(initial.copy(status = "paused", agentId = agent.id, updatedAt = System.currentTimeMillis(), error = "Planning was interrupted; it can be resumed by the user."))
            event(initial.id, null, "paused", "Planning stopped before tools were executed.")
            throw cancel
        } catch (e: Exception) {
            dao.upsertObjective(initial.copy(status = "needs_attention", agentId = agent.id, updatedAt = System.currentTimeMillis(), error = safeError(e)))
            event(initial.id, null, "planning_failed", safeError(e))
            throw e
        }
    }

    /** Resume is called only after the user has reviewed the interrupted/failed task. */
    suspend fun resumeAfterExplicitReview(
        objectiveId: String,
        agent: AgentEntity,
        options: InferenceOptions,
        onToken: (String) -> Unit = {}
    ): ObjectiveEntity {
        val objective = requireNotNull(dao.getObjective(objectiveId)) { "Objective no longer exists." }
        val modelId = requireNotNull(objective.modelId?.takeIf { it.isNotBlank() }) { "This objective has no saved model. Re-plan it with a connected model." }
        val tasks = dao.getTasks(objectiveId)
        val uncertain = tasks.filter { it.status == "needs_review" || it.status == "failed" }
        require(uncertain.all { it.attemptCount < it.maxAttempts }) { "A task reached its retry limit; create a new objective instead." }
        if (uncertain.isNotEmpty()) {
            uncertain.forEach { task ->
                dao.upsertTask(task.copy(status = "pending", error = null, updatedAt = System.currentTimeMillis()))
                event(objectiveId, task.id, "retry_approved", "User explicitly reviewed and approved another attempt. Previous tool side effects may already have occurred.")
            }
        }
        dao.upsertObjective(objective.copy(status = "running", agentId = agent.id, updatedAt = System.currentTimeMillis(), error = null))
        event(objectiveId, null, "resumed", "Execution resumed after explicit review.")
        return executeReady(objectiveId, modelId, agent, options, onToken)
    }

    private suspend fun executeReady(
        objectiveId: String,
        modelId: String,
        agent: AgentEntity,
        options: InferenceOptions,
        onToken: (String) -> Unit
    ): ObjectiveEntity {
        val objective = requireNotNull(dao.getObjective(objectiveId))
        val outcomes = linkedMapOf<String, String>()
        var allTasks = dao.getTasks(objectiveId).sortedBy { it.ordinal }
        allTasks.filter { it.status == "completed" }.forEach { outcomes[it.id] = it.outcome.orEmpty() }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                allTasks = dao.getTasks(objectiveId).sortedBy { it.ordinal }
                if (allTasks.isNotEmpty() && allTasks.all { it.status == "completed" }) {
                    val finished = objective.copy(status = "completed", updatedAt = System.currentTimeMillis(), completedAt = System.currentTimeMillis(), error = null)
                    dao.upsertObjective(finished)
                    event(objectiveId, null, "completed", "All ${allTasks.size} tasks reached the verified local completion checks.")
                    return finished
                }
                val snapshots = allTasks.map { task -> TaskSnapshot(task.id, task.ordinal, decodeDependencies(task.dependsOnJson).toSet(), task.status) }
                val readyId = TaskGraphScheduler.nextReady(snapshots)?.id
                val ready = readyId?.let { id -> allTasks.firstOrNull { it.id == id } }
                if (ready == null) {
                    val pending = allTasks.filter { it.status == "pending" }
                    if (pending.isEmpty()) {
                        val message = "No runnable task remains. Review the failed, blocked, or interrupted task before continuing."
                        val attention = objective.copy(status = "needs_attention", updatedAt = System.currentTimeMillis(), error = message)
                        dao.upsertObjective(attention)
                        return attention
                    }
                    val blockedIds = TaskGraphScheduler.blocked(snapshots).map { it.id }.toSet()
                    val blocked = pending.filter { it.id in blockedIds }
                    blocked.forEach { task ->
                        dao.upsertTask(task.copy(status = "blocked", error = "A prerequisite task did not complete.", updatedAt = System.currentTimeMillis()))
                        event(objectiveId, task.id, "blocked", "A prerequisite task did not complete; this task was not run.")
                    }
                    val message = if (blocked.isEmpty()) "Task graph stalled; no task is dependency-ready." else "${blocked.size} task(s) are blocked by an incomplete prerequisite."
                    val attention = objective.copy(status = "needs_attention", updatedAt = System.currentTimeMillis(), error = message)
                    dao.upsertObjective(attention)
                    event(objectiveId, null, "needs_attention", message)
                    return attention
                }
                runTask(objectiveId, ready, modelId, agent, options, outcomes, onToken)
                outcomes[ready.id] = dao.getTasks(objectiveId).firstOrNull { it.id == ready.id }?.outcome.orEmpty()
            }
        } catch (cancel: CancellationException) {
            val latest = dao.getTasks(objectiveId)
            latest.filter { it.status == "running" || it.status == "verifying" }.forEach { task ->
                dao.upsertTask(task.copy(status = "needs_review", error = "Interrupted while work may have had side effects. Review output before explicitly retrying.", updatedAt = System.currentTimeMillis()))
                event(objectiveId, task.id, "checkpoint_interrupted", "Checkpoint saved as uncertain; it will not be automatically repeated.")
            }
            dao.getObjective(objectiveId)?.let { dao.upsertObjective(it.copy(status = "paused", updatedAt = System.currentTimeMillis(), error = "Paused. Review any task marked needs_review before resuming.")) }
            throw cancel
        } catch (e: Exception) {
            dao.getObjective(objectiveId)?.let { dao.upsertObjective(it.copy(status = "needs_attention", updatedAt = System.currentTimeMillis(), error = safeError(e))) }
            throw e
        }
    }

    private suspend fun runTask(
        objectiveId: String,
        task: ObjectiveTaskEntity,
        modelId: String,
        agent: AgentEntity,
        options: InferenceOptions,
        priorOutcomes: Map<String, String>,
        onToken: (String) -> Unit
    ) = coroutineScope {
        require(task.attemptCount < task.maxAttempts) { "Task retry budget exhausted." }
            val startedAt = System.currentTimeMillis()
            val started = task.copy(
                status = "running", attemptCount = task.attemptCount + 1, outcome = null, error = null,
                verificationStatus = "pending",
                checkpointJson = JSONObject().put("phase", "running").put("attempt", task.attemptCount + 1).put("startedAt", startedAt).toString(),
                updatedAt = startedAt
            )
        dao.upsertTask(started)
        event(objectiveId, task.id, "task_started", "${task.title} · attempt ${started.attemptCount}/${task.maxAttempts}")
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val writer = launch {
            for (item in channel) event(objectiveId, task.id, item.kind, item.text.take(1_000), item.toolName, item.toolInput, item.toolOutput)
        }
        try {
            val config = runCatching { JSONObject(agent.configurationJson ?: "{}") }.getOrDefault(JSONObject())
            val raw = config.optJSONArray("tools")
            val configured = if (raw == null) AgentTools.DEFAULT_TOOLS else (0 until raw.length()).map { raw.optString(it) }.toSet()
            val allowedTools = configured intersect AgentTools.DEFAULT_TOOLS
            val spec = AgentSpec(agent.id, agent.name, modelId, agent.instructions, config.optInt("maxSteps", 6), allowedTools)
            val deps = decodeDependencies(task.dependsOnJson)
            val context = buildString {
                append("Objective: ").append(dao.getObjective(objectiveId)?.goal.orEmpty().take(8_000))
                if (deps.isNotEmpty()) {
                    append("\n\nCompleted prerequisite results:\n")
                    deps.forEach { dep -> append("\n---\n").append(priorOutcomes[dep].orEmpty().take(8_000)) }
                }
                append("\n\nCompletion criterion: ").append(task.verification.orEmpty().take(500))
            }.take(20_000)
            event(objectiveId, task.id, "tool_execution", "Task is running through agent '${agent.name}' and its existing tool allowlist; arbitrary shell execution is not enabled here.")
            val outcome = AgentRunner(app, client).run(
                spec = spec,
                task = task.instructions,
                sharedContext = context,
                options = options,
                onEvent = { channel.trySend(it) },
                onToken = onToken
            )
            channel.close()
            writer.join()
            val output = outcome.result.trim().take(30_000)
            val observations = outcome.events.filter { it.kind.contains("observation") }.map { it.toolOutput ?: it.text }
            val verifyAt = System.currentTimeMillis()
            dao.upsertTask(task.copy(status = "verifying", attemptCount = started.attemptCount, outcome = output, error = null,
                verificationStatus = "checking", checkpointJson = JSONObject().put("phase", "verifying").put("attempt", started.attemptCount).put("outputChars", output.length).toString(), updatedAt = verifyAt))
            event(objectiveId, task.id, "verification_started", "Checking that the agent returned a non-empty result and reported no tool error or denied action. This is not an external proof of claims.")
            require(TaskGraphScheduler.hasVerifiedLocalOutcome(output, observations)) { "Task produced no result, or at least one tool call failed/was denied; inspect observations before retrying." }
            dao.upsertArtifact(ObjectiveArtifactEntity(
                id = UUID.randomUUID().toString(), objectiveId = objectiveId, taskId = task.id,
                name = task.title.take(100), contentType = "text/plain", content = output.take(30_000), createdAt = System.currentTimeMillis()
            ))
            dao.upsertTask(task.copy(status = "completed", attemptCount = started.attemptCount, outcome = output, error = null,
                verificationStatus = "passed", checkpointJson = JSONObject().put("phase", "completed").put("attempt", started.attemptCount).put("verifiedAt", System.currentTimeMillis()).toString(), updatedAt = System.currentTimeMillis()))
            event(objectiveId, task.id, "verification_passed", "Non-empty outcome recorded and no tool error/denial was observed. Review the artifact for semantic correctness.")
            event(objectiveId, task.id, "task_completed", "${task.title} completed.")
        } catch (cancel: CancellationException) {
            channel.close()
            writer.join()
            throw cancel
        } catch (e: Exception) {
            channel.close()
            writer.join()
            dao.upsertTask(task.copy(status = "failed", attemptCount = started.attemptCount, outcome = task.outcome, error = safeError(e),
                verificationStatus = "failed", checkpointJson = JSONObject().put("phase", "failed").put("attempt", started.attemptCount).toString(), updatedAt = System.currentTimeMillis()))
            event(objectiveId, task.id, "task_failed", "${safeError(e)} Automatic retry was not attempted because tool side effects may be uncertain.")
            dao.getObjective(objectiveId)?.let { dao.upsertObjective(it.copy(status = "needs_attention", updatedAt = System.currentTimeMillis(), error = safeError(e))) }
        }
    }

    private suspend fun event(objectiveId: String, taskId: String?, kind: String, detail: String, toolName: String? = null, toolInput: String? = null, toolOutput: String? = null) {
        dao.appendEvent(ObjectiveEventEntity(
            id = UUID.randomUUID().toString(), objectiveId = objectiveId, taskId = taskId,
            kind = kind.take(64), detail = detail.take(2_000), createdAt = System.currentTimeMillis(),
            toolName = toolName?.take(120), toolInput = toolInput?.take(4_000), toolOutput = toolOutput?.take(4_000)
        ))
    }

    private fun decodeDependencies(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun safeError(e: Throwable) = (e.message ?: e.javaClass.simpleName).replace(Regex("(?i)(bearer\\s+)[^\\s]+"), "$1[redacted]").take(350)

    private companion object {
        val PLAN_SYSTEM = """
            You create a small executable task graph for NYX. Return ONLY a JSON object: {"tasks":[{"id":"short_key","title":"...","instructions":"...","verification":"...","dependsOn":[]}]}. Use 1 to 8 tasks. IDs must be unique short alphanumeric/underscore/hyphen keys. Dependencies refer only to other task IDs and must form a DAG. Every task executes through one user-selected NYX agent with its preconfigured safe tools. Do not propose shell commands, arbitrary code execution, network calls, publication, credential access, Android UI actions, or permissions. If the goal cannot be completed with those tools, make the final task explain the blocker rather than inventing capabilities. Criteria should be checkable from task results/tool observations; never claim a task ran when it did not.
        """.trimIndent()
    }
}

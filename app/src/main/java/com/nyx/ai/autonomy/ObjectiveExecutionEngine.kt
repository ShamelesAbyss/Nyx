package com.nyx.ai.autonomy

import android.content.Context
import com.nyx.ai.agent.AgentEvent
import com.nyx.ai.agent.AgentRunner
import com.nyx.ai.agent.AgentSpec
import com.nyx.ai.agent.AgentToolProvider
import com.nyx.ai.agent.AgentTools
import com.nyx.ai.agent.FileProbe
import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.context.ContextEngine
import com.nyx.ai.context.ContextRecord
import com.nyx.ai.context.ContextRequest
import com.nyx.ai.context.ContextStore
import com.nyx.ai.data.AgentEntity
import com.nyx.ai.data.ContextRecordEntity
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.data.ObjectiveArtifactEntity
import com.nyx.ai.data.ObjectiveEntity
import com.nyx.ai.data.ObjectiveEventEntity
import com.nyx.ai.data.ObjectiveTaskEntity
import com.nyx.ai.data.TaskEvidenceEntity
import com.nyx.ai.network.ApiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable objective engine owned by [com.nyx.ai.service.ObjectiveExecutionService].
 *
 * The engine reads and writes Room as the source of truth, so it survives Activity destruction and
 * process restarts. It runs dependency-ready tasks concurrently within a resource-derived policy,
 * verifies them against locally observed evidence, and never retries a task that may have changed
 * external state without an explicit review.
 */
class ObjectiveExecutionEngine(
    context: Context,
    private val database: NyxDatabase,
    private val sessions: InferenceSessionFactory,
    private val terminalTools: AgentToolProvider?,
    private val fileProbe: FileProbe?,
    private val contextStore: ContextStore,
    private val onToken: (String) -> Unit = {},
) {
    private val app = context.applicationContext
    private val dao = database.objectiveDao()

    suspend fun planAndExecute(
        objectiveId: String,
        agent: AgentEntity,
        policy: SchedulingPolicy,
        control: ExecutionControl,
    ): ObjectiveEntity {
        val objective = requireNotNull(dao.getObjective(objectiveId)) { "Objective no longer exists." }
        val session = sessions.create(objective, agent)
        dao.upsertObjective(objective.copy(status = "planning", agentId = agent.id, modelId = session.modelId, updatedAt = now(), error = null))
        event(objectiveId, null, "planning", "Generating a bounded task graph; model output is validated before any tool runs.")
        return try {
            val planResponse = session.provider.chat(
                session.modelId,
                listOf(
                    ApiMessage("system", plannerPrompt()),
                    ApiMessage("user", "Objective:\n${objective.goal.take(8_000)}")
                ),
                tools = null,
                options = session.options,
            )
            val parsed = ObjectivePlanValidator.parse(planResponse.content, objective.id, agent.id)
            require(parsed.isNotEmpty()) { "The planner returned no executable tasks." }
            val now = now()
            dao.upsertTasks(parsed.mapIndexed { index, task ->
                ObjectiveTaskEntity(
                    id = task.id,
                    objectiveId = objective.id,
                    ordinal = index,
                    title = task.title,
                    instructions = task.instructions,
                    verification = task.verification,
                    dependsOnJson = JSONArray(task.dependsOn).toString(),
                    status = "pending",
                    attemptCount = 0,
                    maxAttempts = if (task.workerKind == "verification") 3 else 2,
                    outcome = null,
                    error = null,
                    updatedAt = now,
                    priority = priorityFor(task.workerKind),
                    workerKind = task.workerKind,
                    idempotencyKey = if (task.workerKind == "verification") task.id else null,
                    evidenceJson = encodeSpecs(task.evidence),
                    startedAt = null,
                    finishedAt = null,
                )
            })
            dao.upsertObjective(objective.copy(status = "running", agentId = agent.id, modelId = session.modelId, updatedAt = now(), error = null))
            event(objectiveId, null, "plan_created", "Validated ${parsed.size} tasks (limit ${ObjectivePlanValidator.MAX_TASKS}); execution is dependency-aware and resource-bounded.")
            execute(objectiveId, agent, session, policy, control)
        } catch (pause: PauseRequested) {
            markPaused(objectiveId, "Paused during planning; no task was executed.")
            throw pause
        } catch (cancel: CancellationException) {
            markPaused(objectiveId, "Planning was cancelled.")
            throw cancel
        } catch (e: Exception) {
            dao.getObjective(objectiveId)?.let {
                dao.upsertObjective(it.copy(status = "needs_attention", updatedAt = now(), error = safeError(e)))
            }
            event(objectiveId, null, "planning_failed", safeError(e))
            throw e
        }
    }

    suspend fun resumeAfterReview(
        objectiveId: String,
        agent: AgentEntity,
        policy: SchedulingPolicy,
        control: ExecutionControl,
    ): ObjectiveEntity {
        val objective = requireNotNull(dao.getObjective(objectiveId)) { "Objective no longer exists." }
        val session = sessions.create(objective, agent)
        val tasks = dao.getTasks(objectiveId)
        val uncertain = tasks.filter { it.status == "needs_review" || it.status == "failed" }
        require(uncertain.all { it.attemptCount < it.maxAttempts }) { "A task reached its retry limit; create a new objective instead." }
        uncertain.forEach { task ->
            dao.upsertTask(task.copy(status = "pending", error = null, finishedAt = null, updatedAt = now()))
            event(objectiveId, task.id, "retry_approved", "User reviewed the task and approved another attempt. Previous side effects may already have occurred.")
        }
        dao.upsertObjective(objective.copy(status = "running", agentId = agent.id, modelId = session.modelId, updatedAt = now(), error = null))
        event(objectiveId, null, "resumed", "Execution resumed after explicit review.")
        return execute(objectiveId, agent, session, policy, control)
    }

    private suspend fun execute(
        objectiveId: String,
        agent: AgentEntity,
        session: InferenceSession,
        policy: SchedulingPolicy,
        control: ExecutionControl,
    ): ObjectiveEntity = coroutineScope {
        val objective = requireNotNull(dao.getObjective(objectiveId))
        val finished = Channel<String>(Channel.UNLIMITED)
        val active = linkedMapOf<String, Job>()
        try {
            while (true) {
                control.checkpoint()
                val tasks = dao.getTasks(objectiveId)
                ExecutionMonitor.update {
                    it.copy(
                        phase = "executing",
                        activeTasks = tasks.count { task -> task.status == "running" || task.status == "verifying" },
                        queuedTasks = tasks.count { task -> task.status == "pending" },
                    )
                }
                if (tasks.isNotEmpty() && tasks.all { it.status == "completed" }) {
                    val done = objective.copy(
                        status = "completed",
                        updatedAt = now(),
                        completedAt = now(),
                        leaseOwner = null,
                        leaseExpiresAt = null,
                        error = null,
                    )
                    dao.upsertObjective(done)
                    event(objectiveId, null, "completed", "All ${tasks.size} tasks passed their locally checked evidence.")
                    return@coroutineScope done
                }
                val snapshots = tasks.map { task ->
                    ScheduledTask(
                        id = task.id,
                        ordinal = task.ordinal,
                        priority = task.priority,
                        workerKind = parseWorker(task.workerKind),
                        dependsOn = decodeDependencies(task.dependsOnJson).toSet(),
                        status = task.status,
                    )
                }
                val decision = ConcurrentTaskScheduler.decide(snapshots, policy)
                decision.blocked.forEach { blocked ->
                    tasks.firstOrNull { it.id == blocked.id }?.let { task ->
                        if (task.status == "pending") {
                            dao.upsertTask(task.copy(status = "blocked", error = "A prerequisite task did not complete.", updatedAt = now()))
                            event(objectiveId, task.id, "blocked", "A prerequisite task did not complete; this task was not run.")
                        }
                    }
                }
                if (decision.deadlocked) {
                    return@coroutineScope needsAttention(objectiveId, "The task graph contains a dependency cycle; no task can become ready.")
                }
                for (scheduled in decision.start) {
                    if (active.containsKey(scheduled.id)) continue
                    val task = tasks.firstOrNull { it.id == scheduled.id } ?: continue
                    active[scheduled.id] = launch {
                        try {
                            runTask(objectiveId, task, agent, session, control)
                        } catch (pause: PauseRequested) {
                            // Recorded by runTask; the loop below performs the pause transition.
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            event(objectiveId, task.id, "task_error", safeError(e))
                        } finally {
                            runCatching { finished.send(scheduled.id) }
                        }
                    }
                }
                if (active.isEmpty()) {
                    val pending = dao.getTasks(objectiveId).filter { it.status == "pending" }
                    val message = if (pending.isEmpty()) {
                        "No runnable task remains. Review failed, blocked, or interrupted work before continuing."
                    } else {
                        "Task graph stalled; no pending task is dependency-ready."
                    }
                    return@coroutineScope needsAttention(objectiveId, message)
                }
                val completedId = finished.receive()
                active.remove(completedId)?.join()
            }
            @Suppress("UNREACHABLE_CODE")
            objective
        } catch (pause: PauseRequested) {
            active.values.forEach { it.cancel() }
            markPaused(objectiveId, "Paused. Any task with possible side effects is marked for review.")
            throw pause
        } catch (cancel: CancellationException) {
            active.values.forEach { it.cancel() }
            markPaused(objectiveId, "Cancelled. Any task with possible side effects is marked for review.")
            throw cancel
        } finally {
            active.values.forEach { runCatching { it.cancel() } }
        }
    }

    private suspend fun runTask(
        objectiveId: String,
        task: ObjectiveTaskEntity,
        agent: AgentEntity,
        session: InferenceSession,
        control: ExecutionControl,
    ) = coroutineScope {
        control.checkpoint()
        val attempt = task.attemptCount + 1
        val startedAt = now()
        dao.upsertTask(
            task.copy(
                status = "running",
                attemptCount = attempt,
                startedAt = startedAt,
                finishedAt = null,
                error = null,
                verificationStatus = "pending",
                checkpointJson = JSONObject().put("phase", "running").put("attempt", attempt).put("startedAt", startedAt).toString(),
                updatedAt = startedAt,
            )
        )
        event(objectiveId, task.id, "task_started", "${task.title} · attempt $attempt/${task.maxAttempts} · ${task.workerKind}")

        val specs = EvidenceSpecParser.parse(task.evidenceJson)
        val records = contextStore.list(objectiveId)
        val dependencyIds = decodeDependencies(task.dependsOnJson)
        val built = ContextEngine.build(
            ContextRequest(
                objectiveId = objectiveId,
                goal = dao.getObjective(objectiveId)?.goal.orEmpty(),
                taskTitle = task.title,
                taskInstructions = task.instructions,
                verification = task.verification,
                dependencyTaskIds = dependencyIds,
                records = records,
                budgetChars = 12_000,
            ),
            contextStore,
        )

        val config = runCatching { JSONObject(agent.configurationJson ?: "{}") }.getOrDefault(JSONObject())
        val configured = config.optJSONArray("tools")?.let { raw ->
            (0 until raw.length()).map { raw.optString(it) }.toSet()
        } ?: AgentTools.DEFAULT_TOOLS
        val allowed = configured + (terminalTools?.names ?: emptySet())
        val spec = AgentSpec(
            id = agent.id,
            name = agent.name,
            model = session.modelId,
            systemPrompt = agent.instructions,
            maxSteps = config.optInt("maxSteps", 8),
            allowedTools = allowed,
        )

        val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)
        val writer = launch {
            for (item in eventChannel) {
                event(objectiveId, task.id, item.kind, item.text.take(1_000), item.toolName, item.toolInput, item.toolOutput)
            }
        }
        val outcome = try {
            AgentRunner(app, session.provider, terminalTools).run(
                spec = spec,
                task = task.instructions,
                sharedContext = built.text,
                options = session.options,
                onEvent = { eventChannel.trySend(it) },
                onToken = { if (control.isPauseRequested.not() && !control.isCancelRequested) onToken(it) },
            )
        } finally {
            eventChannel.close()
            writer.join()
        }
        control.checkpoint()

        val toolFailures = outcome.events.count { event ->
            event.kind == "tool_observation" && (event.text.contains("Tool error:") || event.text.contains("Tool denied:"))
        }
        val lastExit = outcome.evidence.lastOrNull { it.exitCode != null }
        val observation = ObservationBundle(
            exitCode = lastExit?.exitCode,
            stdout = outcome.evidence.joinToString("\n") { it.stdout }.take(64_000),
            stderr = outcome.evidence.joinToString("\n") { it.stderr }.take(16_000),
            agentOutput = outcome.result,
            fileExists = { path -> fileProbe?.exists(path) ?: false },
            fileText = { path, max -> fileProbe?.read(path, max) },
            checksum = { path -> fileProbe?.sha256(path) },
        )
        val report = TaskVerifier.verify(specs, observation, toolFailures)

        val verifying = task.copy(
            status = "verifying",
            attemptCount = attempt,
            outcome = outcome.result.take(30_000),
            verificationStatus = if (report.passed) "passed" else "failed",
            evidenceJson = mergeEvidence(task.evidenceJson, report),
            updatedAt = now(),
        )
        dao.upsertTask(verifying)
        persistEvidence(objectiveId, task.id, report)
        event(
            objectiveId,
            task.id,
            "verification_${if (report.passed) "passed" else "failed"}",
            report.reason ?: "${report.results.count { it.passed }}/${report.results.size} evidence checks passed.",
        )

        if (report.passed) {
            val artifact = ObjectiveArtifactEntity(
                id = UUID.randomUUID().toString(),
                objectiveId = objectiveId,
                taskId = task.id,
                name = task.title.take(100),
                contentType = "text/markdown",
                content = outcome.result.take(30_000),
                createdAt = now(),
            )
            dao.upsertArtifact(artifact)
            writeContext(objectiveId, task.id, "artifact", task.title, outcome.result)
            dao.upsertTask(
                verifying.copy(
                    status = "completed",
                    finishedAt = now(),
                    error = null,
                    verificationStatus = "passed",
                    checkpointJson = JSONObject().put("phase", "completed").put("attempt", attempt).put("verifiedAt", now()).toString(),
                    updatedAt = now(),
                )
            )
            event(objectiveId, task.id, "task_completed", "${task.title} completed with verified local evidence.")
            return@coroutineScope
        }

        val mutating = outcome.evidence.any { it.mutating }
        val signature = TaskVerifier.failureSignature(task.id, report.reason)
        val decision = RecoveryPolicy.decide(
            attemptCount = attempt,
            maxAttempts = task.maxAttempts,
            idempotent = !mutating && task.workerKind == "verification",
            failureSignature = signature,
            previousFailureSignatures = listOfNotNull(task.failureSignature),
            risk = if (mutating) "mutating" else "read-only",
        )
        event(objectiveId, task.id, "recovery_${decision.action.name.lowercase()}", decision.reason)
        when (decision.action) {
            RecoveryAction.RETRY -> {
                delay(decision.delayMillis)
                dao.upsertTask(
                    verifying.copy(
                        status = "pending",
                        error = report.reason,
                        failureSignature = signature,
                        updatedAt = now(),
                    )
                )
            }
            RecoveryAction.NEEDS_REVIEW -> dao.upsertTask(
                verifying.copy(
                    status = "needs_review",
                    error = report.reason,
                    failureSignature = signature,
                    finishedAt = now(),
                    checkpointJson = JSONObject().put("phase", "needs_review").put("attempt", attempt).toString(),
                    updatedAt = now(),
                )
            )
            RecoveryAction.FAIL_PERMANENTLY -> dao.upsertTask(
                verifying.copy(
                    status = "failed",
                    error = report.reason,
                    failureSignature = signature,
                    finishedAt = now(),
                    checkpointJson = JSONObject().put("phase", "failed").put("attempt", attempt).toString(),
                    updatedAt = now(),
                )
            )
        }
        writeContext(objectiveId, task.id, "failure", task.title, report.reason ?: "Verification failed")
        if (decision.action != RecoveryAction.RETRY) {
            dao.getObjective(objectiveId)?.let {
                dao.upsertObjective(it.copy(status = "needs_attention", updatedAt = now(), error = report.reason))
            }
        }
    }

    private suspend fun markPaused(objectiveId: String, message: String) {
        val tasks = dao.getTasks(objectiveId)
        tasks.filter { it.status == "running" || it.status == "verifying" }.forEach { task ->
            dao.upsertTask(
                task.copy(
                    status = "needs_review",
                    error = "Interrupted while work may have had side effects. Review output before explicitly retrying.",
                    finishedAt = now(),
                    updatedAt = now(),
                )
            )
            event(objectiveId, task.id, "checkpoint_interrupted", "Checkpoint saved as uncertain; it will not be repeated automatically.")
        }
        dao.getObjective(objectiveId)?.let {
            dao.upsertObjective(it.copy(status = "paused", updatedAt = now(), leaseOwner = null, leaseExpiresAt = null, error = message))
        }
    }

    private suspend fun needsAttention(objectiveId: String, message: String): ObjectiveEntity {
        val objective = requireNotNull(dao.getObjective(objectiveId))
        val attention = objective.copy(status = "needs_attention", updatedAt = now(), leaseOwner = null, leaseExpiresAt = null, error = message)
        dao.upsertObjective(attention)
        event(objectiveId, null, "needs_attention", message)
        return attention
    }

    private suspend fun writeContext(objectiveId: String, taskId: String?, kind: String, title: String, text: String) {
        runCatching {
            val record = contextStore.write(objectiveId, taskId, kind, title, text)
            database.contextRecordDao().insert(
                ContextRecordEntity(
                    id = record.id,
                    objectiveId = record.objectiveId,
                    taskId = record.taskId,
                    kind = record.kind,
                    title = record.title,
                    filePath = record.filePath,
                    summary = record.summary,
                    createdAt = record.createdAt,
                )
            )
        }
    }

    private suspend fun persistEvidence(objectiveId: String, taskId: String, report: VerificationReport) {
        runCatching {
            database.taskEvidenceDao().insertAll(
                report.results.map { result ->
                    TaskEvidenceEntity(
                        id = UUID.randomUUID().toString(),
                        objectiveId = objectiveId,
                        taskId = taskId,
                        kind = result.spec.kind.name.lowercase(),
                        target = result.spec.target,
                        expected = result.spec.expected,
                        actual = result.actual.take(1_000),
                        passed = result.passed,
                        createdAt = now(),
                    )
                }
            )
        }
    }

    private suspend fun event(
        objectiveId: String,
        taskId: String?,
        kind: String,
        detail: String,
        toolName: String? = null,
        toolInput: String? = null,
        toolOutput: String? = null,
    ) {
        dao.appendEvent(
            ObjectiveEventEntity(
                id = UUID.randomUUID().toString(),
                objectiveId = objectiveId,
                taskId = taskId,
                kind = kind.take(64),
                detail = detail.take(2_000),
                createdAt = now(),
                toolName = toolName?.take(120),
                toolInput = toolInput?.take(4_000),
                toolOutput = toolOutput?.take(4_000),
            )
        )
    }

    private fun encodeSpecs(specs: List<EvidenceSpec>): String? {
        if (specs.isEmpty()) return null
        return JSONObject().put("specs", JSONArray().apply {
            specs.forEach { spec ->
                put(
                    JSONObject()
                        .put("kind", spec.kind.name.lowercase())
                        .put("target", spec.target)
                        .put("expected", spec.expected)
                        .put("description", spec.description)
                )
            }
        }).toString()
    }

    private fun mergeEvidence(existing: String?, report: VerificationReport): String {
        val root = runCatching { JSONObject(existing ?: "{}") }.getOrDefault(JSONObject())
        return root.put("results", JSONArray(report.evidenceJson)).toString()
    }

    private fun priorityFor(workerKind: String): Int = when (workerKind) {
        "verification" -> 30
        "development" -> 20
        "terminal" -> 15
        "filesystem" -> 10
        else -> 0
    }

    private fun parseWorker(value: String): WorkerKind =
        runCatching { WorkerKind.valueOf(value.uppercase()) }.getOrDefault(WorkerKind.INFERENCE)

    private fun decodeDependencies(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun safeError(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName)
            .replace(Regex("(?i)(bearer\\s+)[^\\s]+"), "$1[redacted]")
            .take(350)

    private fun now(): Long = System.currentTimeMillis()

    private fun plannerPrompt(): String = buildString {
        append(
            """
            You create a bounded executable task graph for NYX, an Android autonomy engine.
            Return ONLY strict JSON: {"tasks":[{"id":"short_key","title":"...","instructions":"...","verification":"...","worker":"inference|terminal|filesystem|verification|development","dependsOn":[],"evidence":[]}]}
            Rules:
            - 1 to ${ObjectivePlanValidator.MAX_TASKS} tasks. Prefer the smallest graph that actually completes the objective.
            - IDs are unique short keys. Dependencies refer only to other task IDs and must form a DAG.
            - Tasks run concurrently when their dependencies allow it, within a device resource limit.
            - "worker" selects the kind of work: inference (reasoning/text), terminal (run commands in the authorized workspace), filesystem (read/write project files), verification (inspect and report), development (inspect, edit, build, test, repair).
            - Optional "evidence" entries are checked locally after the task: {"kind":"exit_code","expected":"0"}, {"kind":"output_contains","expected":"BUILD SUCCESSFUL"}, {"kind":"file_exists","target":"app/build/outputs/apk/debug/app-debug.apk"}, {"kind":"file_contains","target":"path","expected":"text"}, {"kind":"checksum_sha256","target":"path","expected":"hex"}.
            - Use evidence whenever a command or file can prove completion. Never claim a task succeeded.
            - Commands and files are limited to the user-authorized workspace. Destructive, privileged, credential, and publication actions are blocked.
            - If the objective cannot be completed with these capabilities, make the final task explain the blocker instead of inventing capabilities.
            """.trimIndent()
        )
        append("\n- Terminal tools are ")
        append(if (terminalTools != null) "ENABLED for this run." else "DISABLED for this run; do not plan terminal or development tasks.")
    }

    @Suppress("unused")
    private fun ContextRecord.pathForDisplay(): String = filePath
}

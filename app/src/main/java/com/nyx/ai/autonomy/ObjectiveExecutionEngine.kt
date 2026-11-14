package com.nyx.ai.autonomy

import android.content.Context
import androidx.room.withTransaction
import com.nyx.ai.agent.AgentEvent
import com.nyx.ai.agent.AgentOutcome
import com.nyx.ai.terminal.TerminalAgentTools
import com.nyx.ai.terminal.TerminalExecutionScope
import com.nyx.ai.development.*
import com.nyx.ai.skills.*
import com.nyx.ai.agent.AgentRunner
import com.nyx.ai.agent.AgentSpec
import com.nyx.ai.agent.AgentTools
import com.nyx.ai.agent.AgentToolProvider
import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.agent.CompositeToolProvider
import com.nyx.ai.agent.NetworkAgentTools
import com.nyx.ai.agent.SovereignRunConfig
import com.nyx.ai.agent.WorkerRoles
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
import com.nyx.ai.network.InferenceFabric
import com.nyx.ai.performance.PerformanceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest

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
    private val extraTools: AgentToolProvider? = null,
    private val arsenal: com.nyx.ai.skills.ArsenalRepository? = null,
    private val projectMemory: com.nyx.ai.context.ProjectMemory? = null,
    private val fileProbe: FileProbe?,
    private val contextStore: ContextStore,
    private val onToken: (String) -> Unit = {},
    private val suppliedRunId: String? = null,
    private val beforeTasksExecute: suspend (List<ObjectiveTaskEntity>) -> Boolean = { true },
) {
    private val app = context.applicationContext
    private val dao = database.objectiveDao()
    private var activeRunId: String? = suppliedRunId

    private companion object {
        val builtinToolLocks = mutableMapOf<String, Mutex>()
    }

    suspend fun planAndExecute(
        objectiveId: String,
        agent: AgentEntity,
        policy: SchedulingPolicy,
        control: ExecutionControl,
    ): ObjectiveEntity {
        activeRunId = suppliedRunId ?: "run-${UUID.randomUUID()}"
        val objective = requireNotNull(dao.getObjective(objectiveId)) { "Objective no longer exists." }
        val session = sessions.create(objective, agent)
        saveObjective(requireNotNull(dao.getObjective(objectiveId)).copy(status = "planning", agentId = agent.id, modelId = session.modelId, updatedAt = now(), error = null))
        event(objectiveId, null, "planning", "Generating a bounded task graph; model output is validated before any tool runs.")
        return try {
            control.checkpoint()
            val parsed = requestValidatedPlan(objective, session, agent.id, control)
            control.checkpoint()
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
            val plannedTasks = dao.getTasks(objectiveId)
            if (!beforeTasksExecute(plannedTasks)) {
                return requireNotNull(dao.getObjective(objectiveId))
            }
            saveObjective(requireNotNull(dao.getObjective(objectiveId)).copy(status = "running", agentId = agent.id, modelId = session.modelId, updatedAt = now(), error = null))
            event(objectiveId, null, "plan_created", "Validated ${parsed.size} tasks (limit ${ObjectivePlanValidator.MAX_TASKS}); execution is dependency-aware and resource-bounded.", status = "completed")
            execute(objectiveId, agent, session, policy, control)
        } catch (failure: PlanningFailureException) {
            planningNeedsAttention(objectiveId, failure.failure)
        } catch (pause: PauseRequested) {
            withContext(NonCancellable) { markPaused(objectiveId, "Paused during planning; no task was executed.") }
            throw pause
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { markPaused(objectiveId, "Planning was cancelled.") }
            throw cancel
        } catch (e: Exception) {
            planningNeedsAttention(objectiveId, PlanningRecovery.classify(e))
        }
    }

    /**
     * Requests a plan with a hard deadline and progress events. A completed response that fails
     * validation gets exactly one structured-output repair; transport failures do not.
     */
    private suspend fun requestValidatedPlan(
        objective: ObjectiveEntity,
        session: InferenceSession,
        agentId: String,
        control: ExecutionControl,
    ): List<PlannedObjectiveTask> {
        val toolNames = AgentTools.DEFAULT_TOOLS + terminalTools?.names.orEmpty() + extraTools?.names.orEmpty()
        val profile = CompactPlanningPolicy.profile(objective.goal, terminalTools != null, toolNames, session.options.contextLength)
        event(objective.id, null, "planning_profile", "${if (profile.compact) "Compact sequential" else "Full DAG"} planner; output cap ${profile.maxOutputTokens} tokens. A plan is not permission to execute tools.", status = "running")
        return PlanningRecovery.validatedPlan(
            objectiveId = objective.id,
            agentId = agentId,
            request = { attempt, repair ->
                control.checkpoint()
                event(objective.id, null, "planning_attempt", "Planning attempt $attempt/${PlanningRecovery.MAX_PLAN_ATTEMPTS}; waiting for a bounded model response.", status = "running")
                requestPlanText(objective, session, repair, profile, control)
            },
            onRejected = { attempt, failure ->
                event(objective.id, null, "planning_validation_failed", "Attempt $attempt rejected: ${failure.reason}", status = "failed")
            },
            onRepair = { failure ->
                event(objective.id, null, "planning_repair", "One bounded structured-output repair attempt is being made for ${failure.kind.name.lowercase()}.", status = "waiting")
            },
            validate = { tasks -> CompactPlanningPolicy.validateWorkers(tasks, profile) },
        )
    }

    private suspend fun requestPlanText(
        objective: ObjectiveEntity,
        session: InferenceSession,
        repair: Pair<String, PlanningFailure>?,
        profile: PlanningProfile,
        control: ExecutionControl,
    ): String {
        if (objective.goal.length > 8_000) {
            throw PlanningFailureException(PlanningFailure(PlanningFailureKind.SCHEMA_REJECTED,
                "Objective exceeds the 8000-character planner input limit. Shorten or split it; no truncated objective was planned."))
        }
        val clock = { System.nanoTime() / 1_000_000 }
        val limits = session.timeouts.planningLimits()
        val lifecycle = PlanningLifecyclePolicy(clock(), limits)
        val toolNames = AgentTools.DEFAULT_TOOLS + terminalTools?.names.orEmpty() + extraTools?.names.orEmpty()
        val systemPrompt = if (profile.compact) CompactPlanningPolicy.prompt(profile, terminalTools != null, toolNames)
            else plannerPrompt(terminalTools != null, toolNames)
        val input = CompactPlanningPolicy.prepareInput(objective.goal, systemPrompt, profile, repair)
        val messages = buildList {
            add(ApiMessage("system", input.systemPrompt))
            // Do not silently truncate an objective and execute a plan for only its prefix.
            add(ApiMessage("user", input.objectivePrompt))
            input.repairPrompt?.let { add(ApiMessage("user", it)) }
        }
        event(objective.id, null, "planning_input", input.metrics.summary(), status = "waiting")
        var firstTextReported = false
        suspend fun report(snapshot: PlanningProgress, kind: String = "planning_progress") {
            if (!firstTextReported && snapshot.firstTokenAfterMs != null) {
                firstTextReported = true
                event(objective.id, null, "planning_first_text", "First planning text observed ${snapshot.firstTokenAfterMs}ms after submission. Queue, model load and prompt processing are not separately measured.", status = "running")
            }
            val fabric = InferenceFabric.metrics.value
            val complete = kind == "planning_response"
            val detail = if (complete) "Provider returned a planning response; validating the entire plan before any task is accepted. ${snapshot.characters} streamed characters observed; elapsed ${snapshot.elapsedMs / 1_000}s."
                else snapshot.detail(fabric.queued, fabric.active)
            ExecutionMonitor.update { current ->
                if (current.objectiveId == objective.id) current.copy(phase = if (complete) "planning_validation" else "planning_${snapshot.stage.name.lowercase()}", message = detail)
                else current
            }
            event(objective.id, null, kind, detail, status = if (complete) "completed" else if (snapshot.stage == PlanningStage.GENERATING) "running" else "waiting")
        }
        event(objective.id, null, "planning_timeouts", "${session.timeouts.profile.name.lowercase()} profile: first text ${limits.firstTokenMs / 1_000}s, text inactivity ${limits.stallMs / 1_000}s, finite request ${limits.hardLimitMs / 1_000}s; service segments and overall allowance are separate.", status = "waiting")
        val result = withTimeoutOrNull(limits.hardLimitMs) {
            coroutineScope {
                report(lifecycle.progress(clock()), "planning_waiting")
                val pending = async {
                    InferenceFabric.wrap(session.provider).chat(session.modelId, messages, tools = null,
                        options = session.options.copy(temperature = 0.1,
                            maxOutputTokens = profile.maxOutputTokens, responseSchema = profile.schema,
                            contextLength = profile.maxContextTokens ?: session.options.contextLength,
                            executionTimeouts = session.timeouts),
                        onToken = { chunk -> lifecycle.token(clock(), chunk) })
                }
                try {
                    var lastStage = PlanningStage.WAITING_FOR_PROVIDER
                    var lastReportAt = clock()
                    while (!pending.isCompleted) {
                        control.checkpoint()
                        val snapshot = lifecycle.progress(clock())
                        snapshot.failure?.let { failure ->
                            report(snapshot, "planning_limit")
                            throw PlanningFailureException(failure)
                        }
                        if (snapshot.characters > ObjectivePlanValidator.MAX_JSON_BYTES) {
                            throw PlanningFailureException(PlanningFailure(PlanningFailureKind.SCHEMA_REJECTED, "Planning stream exceeded the bounded response size; no partial plan was accepted."))
                        }
                        if (snapshot.stage != lastStage || clock() - lastReportAt >= PlanningRecovery.PROGRESS_INTERVAL_MS) {
                            report(snapshot)
                            lastStage = snapshot.stage
                            lastReportAt = clock()
                        }
                        delay(PlanningRecovery.WATCHDOG_INTERVAL_MS)
                    }
                    control.checkpoint()
                    val completed = pending.await()
                    val snapshot = lifecycle.progress(clock())
                    snapshot.failure?.let { throw PlanningFailureException(it) }
                    report(snapshot, "planning_response")
                    completed
                } finally {
                    pending.cancel()
                }
            }
        } ?: throw PlanningTimeoutException("Planning reached the ${limits.hardLimitMs / 1_000}s hard request limit; no partial plan was accepted.")
        currentCoroutineContext().ensureActive()
        control.checkpoint()
        if (!result.done) throw PlanningFailureException(PlanningFailure(PlanningFailureKind.INTERRUPTED, "Provider marked the planning response incomplete; no partial plan was accepted."))
        if (result.toolCalls.isNotEmpty()) throw PlanningFailureException(PlanningFailure(PlanningFailureKind.SCHEMA_REJECTED, "Planning returned tool calls despite a tool-free request; no task was accepted."))
        return result.content
    }

    private suspend fun planningNeedsAttention(objectiveId: String, failure: PlanningFailure): ObjectiveEntity {
        val message = "${PlanningRecovery.diagnostic(failure.kind)} ${failure.reason}".trim().take(600)
        val current = dao.getObjective(objectiveId)
        if (current != null) {
            saveObjective(current.copy(status = "needs_attention", updatedAt = now(), error = message, leaseOwner = null, leaseExpiresAt = null))
        }
        event(objectiveId, null, "planning_failed", "${failure.kind.name.lowercase()}: $message", status = "failed")
        return requireNotNull(dao.getObjective(objectiveId))
    }

    suspend fun resumeAfterReview(
        objectiveId: String,
        agent: AgentEntity,
        policy: SchedulingPolicy,
        control: ExecutionControl,
    ): ObjectiveEntity {
        activeRunId = suppliedRunId ?: "run-${UUID.randomUUID()}"
        val objective = requireNotNull(dao.getObjective(objectiveId)) { "Objective no longer exists." }
        val session = sessions.create(objective, agent)
        val tasks = dao.getTasks(objectiveId)
        if (!beforeTasksExecute(tasks)) {
            return requireNotNull(dao.getObjective(objectiveId))
        }
        val uncertain = tasks.filter { it.status == "needs_review" || it.status == "failed" }
        require(uncertain.all { it.attemptCount < it.maxAttempts }) { "A task reached its retry limit; create a new objective instead." }
        uncertain.forEach { task ->
            dao.upsertTask(task.copy(status = "pending", error = null, finishedAt = null, updatedAt = now()))
            event(objectiveId, task.id, "retry_approved", "User reviewed the task and approved another attempt. Previous side effects may already have occurred.")
        }
        saveObjective(requireNotNull(dao.getObjective(objectiveId)).copy(status = "running", agentId = agent.id, modelId = session.modelId, updatedAt = now(), error = null))
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
                    val done = requireNotNull(dao.getObjective(objectiveId)).copy(
                        status = "completed",
                        updatedAt = now(),
                        completedAt = now(),
                        leaseOwner = null,
                        leaseExpiresAt = null,
                        error = null,
                    )
                    saveObjective(done)
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
                        // Launch reservations count even before runTask's first Room write.
                        status = if (task.id in active) "running" else task.status,
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
                            withContext(TerminalExecutionScope(objectiveId, task.id)) {
                                runTask(objectiveId, task, agent, session, control)
                            }
                        } catch (pause: PauseRequested) {
                            // Recorded by runTask; the loop below performs the pause transition.
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            withContext(NonCancellable) {
                                dao.getTask(task.id)?.let { current -> dao.upsertTask(current.copy(status = "needs_review", error = safeError(e), updatedAt = now())) }
                                event(objectiveId, task.id, "task_error", safeError(e))
                            }
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
            withContext(NonCancellable) { markPaused(objectiveId, "Paused. Any task with possible side effects is marked for review.") }
            throw pause
        } catch (cancel: CancellationException) {
            active.values.forEach { it.cancel() }
            withContext(NonCancellable) { markPaused(objectiveId, "Cancelled. Any task with possible side effects is marked for review.") }
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
        val toolContext = AgentToolTaskContext(objectiveId, task.id, session.timeouts)
        val terminalTools = this@ObjectiveExecutionEngine.terminalTools?.forTask(toolContext)
        val extraTools = this@ObjectiveExecutionEngine.extraTools?.forTask(toolContext)
        // Verification must use the same task-local terminal instance, never drain its parent.
        val fileProbe = (terminalTools as? FileProbe) ?: this@ObjectiveExecutionEngine.fileProbe
        val attempt = task.attemptCount + 1
        val startedAt = now()
        val objectiveGoal = dao.getObjective(objectiveId)?.goal.orEmpty()
        val performanceLevel = runCatching { PerformanceController.tuning().level }.getOrNull()
        val depth = SovereignPolicy.depthFor(
            workerKind = task.workerKind,
            title = task.title,
            instructions = task.instructions,
            performanceLevel = performanceLevel,
        )
        val budget = SovereignPolicy.budgetFor(
            depth = depth,
            performanceLevel = performanceLevel,
            workerKind = task.workerKind,
            contextLength = session.options.contextLength,
        )
        var working = (SovereignWorkingState.fromCheckpoint(task.checkpointJson)
            ?: SovereignWorkingState.start(objectiveGoal, task.title, attempt))
            .copy(attempt = attempt)
        val runningCheckpoint = JSONObject()
            .put("phase", "running")
            .put("attempt", attempt)
            .put("startedAt", startedAt)
            .put("sovereignState", working.toJson())
        dao.upsertTask(
            task.copy(
                status = "running",
                attemptCount = attempt,
                startedAt = startedAt,
                finishedAt = null,
                error = null,
                verificationStatus = "pending",
                checkpointJson = runningCheckpoint.toString(),
                updatedAt = startedAt,
            )
        )
        event(objectiveId, task.id, "task_started", "${task.title} · attempt $attempt/${task.maxAttempts} · ${task.workerKind}")
        event(objectiveId, task.id, "execution_profile", SovereignPolicy.executionProfile(depth, budget))

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
                budgetChars = budget.maxContextChars,
            ),
            contextStore,
        )
        val workingBlock = working.promptBlock(minOf(3_500, budget.maxContextChars / 3))
        val retryHint = if (attempt > 1) SovereignPolicy.retryHint(task.error, attempt, task.maxAttempts) else null
        val sharedContext = buildString {
            append(built.text)
            if (workingBlock.isNotBlank()) append("\n\n").append(workingBlock)
            if (retryHint != null) append("\n\nRETRY GUIDANCE: ").append(retryHint)
        }.take(budget.maxContextChars + 4_000)

        val config = runCatching { JSONObject(agent.configurationJson ?: "{}") }.getOrDefault(JSONObject())
        val configured = config.optJSONArray("tools")?.let { raw ->
            (0 until raw.length()).map { raw.optString(it) }.toSet()
        } ?: AgentTools.DEFAULT_TOOLS
        val builtins = AgentTools(app, agent.id)
        val storageId = agent.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "agent" }
        val builtinLock = synchronized(builtinToolLocks) {
            builtinToolLocks.getOrPut("${app.filesDir.absolutePath}/$storageId") { Mutex() }
        }
        val builtinProvider = object : AgentToolProvider {
            override val names = AgentTools.DEFAULT_TOOLS
            override fun schemas(): JSONArray = builtins.schemas()
            override suspend fun execute(call: com.nyx.ai.network.ToolCall): String =
                builtinLock.withLock { builtins.execute(call) }
        }
        // Preserve agent-private memory/workspace semantics, but prevent sibling read/write races.
        val objectiveTools: AgentToolProvider = CompositeToolProvider(
            listOfNotNull(builtinProvider, terminalTools, extraTools),
        )
        val approvedSkills = if (terminalTools != null) {
            val project = (terminalTools as? TerminalAgentTools)?.inspectProject()
            val preconditions = setOf(SkillPrecondition.WORKSPACE_SELECTED, SkillPrecondition.TERMINAL_AVAILABLE) +
                (if (project?.ready == true) setOf(SkillPrecondition.PROJECT_DETECTED) else emptySet())
            val hostContext = SkillValidationContext(terminalTools.names, SkillPermission.entries.toSet(), preconditions)
            val ids = if (task.workerKind == "development") listOf(BuiltinSkillRegistry.SOURCE_REPAIR, BuiltinSkillRegistry.BUILD_TEST)
                else if (task.workerKind == "verification") listOf(BuiltinSkillRegistry.WORKSPACE_INSPECTION, BuiltinSkillRegistry.BUILD_TEST)
                else listOf(BuiltinSkillRegistry.WORKSPACE_INSPECTION, BuiltinSkillRegistry.LOCAL_FILESYSTEM_RESEARCH, BuiltinSkillRegistry.SOURCE_REPAIR, BuiltinSkillRegistry.BUILD_TEST)
            ids.filter { it != BuiltinSkillRegistry.BUILD_TEST || project?.ready == true }.map { id ->
                val checked = BuiltinSkillRegistry.validate(id, hostContext)
                requireNotNull(checked.skill) { "Builtin skill validation failed: ${checked.issues.joinToString { it.message }}" }
            }
        } else emptyList()
        val arsenalSkills = runCatching {
            arsenal?.selectForGoal("$objectiveGoal ${task.title} ${task.instructions}", 3).orEmpty()
        }.getOrDefault(emptyList())
        val allowed = configured + approvedSkills.flatMap { it.allowedTools } +
            arsenalSkills.flatMap { it.manifest.tools } +
            extraTools?.names.orEmpty()
        val availableTools = AgentTools.DEFAULT_TOOLS + terminalTools?.names.orEmpty() + extraTools?.names.orEmpty()
        val toolScope = TaskCapabilityScope.forTask(
            workerKind = task.workerKind,
            configured = allowed,
            available = availableTools,
            terminalAvailable = terminalTools != null,
        )
        val scopedAllowed = toolScope.allowed
        if (approvedSkills.isNotEmpty()) event(objectiveId, task.id, "skill_scope", approvedSkills.joinToString { "${it.manifest.id}@${it.manifest.version}" })
        if (arsenalSkills.isNotEmpty()) event(objectiveId, task.id, "skill_scope", arsenalSkills.joinToString { "${it.id}@${it.version}" })
        event(objectiveId, task.id, "tool_scope", "${toolScope.reason} Enabled: ${scopedAllowed.sorted().joinToString(", ").ifBlank { "none" }}.")
        val role = WorkerRoles.forTask(task.workerKind, task.title)
        event(objectiveId, task.id, "delegation_role", "${role.label} worker assigned to \"${task.title.take(80)}\".")
        val spec = AgentSpec(
            id = agent.id,
            name = agent.name,
            model = session.modelId,
            systemPrompt = WorkerRoles.prompt(role) + "\n\n" + agent.instructions +
                approvedSkills.joinToString("\n", prefix = "\nValidated offline procedures:\n") { it.manifest.instructions.joinToString("\n") } +
                runCatching { arsenal?.promptBlock("$objectiveGoal ${task.title}", 3).orEmpty() }.getOrDefault("") +
                runCatching { projectMemory?.buildPromptBlock("$objectiveGoal ${task.title}", 3_500).orEmpty() }
                    .getOrDefault("")
                    .let { if (it.isNotBlank()) "\n\n$it" else "" },
            maxSteps = minOf(config.optInt("maxSteps", 8), budget.maxSteps),
            allowedTools = scopedAllowed,
        )

        val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)
        val writer = launch {
            for (item in eventChannel) {
                event(objectiveId, task.id, item.kind, item.text.take(1_000), item.toolName, item.toolInput, item.toolOutput)
            }
        }
        var developmentPassed: Boolean? = null
        var developmentFinalCommands: List<DevelopmentCommand>? = null
        // Skill/toolchain preflight is not execution evidence for a worker's completion claim.
        objectiveTools?.drainEvidence()
        val outcome = try {
            if (task.workerKind == "development") {
                val concrete = terminalTools as? TerminalAgentTools ?: error("Development requires an explicitly approved Termux workspace and available toolchain.")
                val preferences = app.getSharedPreferences("nyx_settings", 0)
                val budget = DevelopmentBudget(
                    maxIterations = preferences.getInt("repair_iterations", 3).coerceIn(1, 5),
                    maxInferenceCalls = preferences.getInt("repair_calls", 4).coerceIn(1, 6),
                    maxElapsedMillis = minOf(session.timeouts.buildMs, preferences.getLong("repair_millis", session.timeouts.buildMs).coerceIn(30_000, ExecutionTimeoutPolicy.MAX_SERVICE_SEGMENT_MS)),
                    maxEditedFiles = preferences.getInt("repair_files", 8).coerceIn(1, 12),
                    maxContextChars = minOf(24_000, session.options.contextLength * 2).coerceAtLeast(1024),
                )
                event(objectiveId, task.id, "development_budget", "Repair loop has a ${budget.maxElapsedMillis / 1_000}s effective elapsed ceiling (selected build policy ${session.timeouts.buildMs / 1_000}s; any shorter saved repair budget still applies). Terminal and build operations retain their separate finite policy ceilings.")
                val report = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(concrete)).run(task.instructions, InferenceFabric.wrap(session.provider), session.modelId, session.options.copy(executionTimeouts = session.timeouts), budget) {
                    eventChannel.trySend(AgentEvent("development_" + it.phase, "Iteration ${it.iteration}: ${it.message}"))
                }
                developmentPassed = report.passed
                developmentFinalCommands = report.iterations.lastOrNull()?.commands
                val journal = JSONObject().put("passed", report.passed).put("reason", report.reason).put("calls", report.inferenceCalls).put("elapsedMillis", report.elapsedMillis)
                    .put("commands", JSONArray().apply { report.commands.forEach { put(JSONObject().put("command", it.command).put("exit", it.exitCode).put("stdout", it.stdout).put("stderr", it.stderr)) } })
                    .put("files", JSONArray(report.files)).put("diff", report.diff).put("checkpointIds", JSONArray(report.checkpoints.map { it.id }))
                writeContext(objectiveId, task.id, "observation", "Development evidence", journal.toString())
                contextStore.checkpoint(objectiveId, task.id, "Development ${report.reason}; checkpoints ${report.checkpoints.joinToString { it.id }}")
                val text = "Development ${if (report.passed) "verified" else "NOT completed"}: ${report.reason}\n" +
                    "Inference calls: ${report.inferenceCalls} · elapsed ${report.elapsedMillis}ms\n" +
                    report.commands.joinToString("\n") { "${it.kind}: ${it.command} -> exit ${it.exitCode}" } +
                    "\nChanged files: ${report.files.joinToString()}\nCheckpoints: ${report.checkpoints.joinToString { it.id }}\nDiff:\n${report.diff}"
                AgentOutcome(text, emptyList(), report.inferenceCalls, report.evidence)
            } else AgentRunner(app, InferenceFabric.wrap(session.provider), objectiveTools).run(
                spec = spec,
                task = task.instructions,
                sharedContext = sharedContext,
                options = session.options.copy(executionTimeouts = session.timeouts),
                onEvent = { eventChannel.trySend(it) },
                sovereign = SovereignRunConfig(
                    depth = depth,
                    budget = budget,
                    requiresToolEvidence = task.workerKind != "inference" && scopedAllowed.isNotEmpty(),
                    workingState = working,
                ),
                onToken = { if (control.isPauseRequested.not() && !control.isCancelRequested) onToken(it) },
            )
        } finally {
            eventChannel.close()
            withContext(NonCancellable) { writer.join() }
        }
        control.checkpoint()

        check(outcome.evidence.all { it.objectiveId == objectiveId && it.taskId == task.id }) {
            "Task evidence ownership mismatch; no cross-task completion can be established."
        }
        working = SovereignWorkingState.fromJson(outcome.workingStateJson) ?: working
        if (outcome.falseSuccessRisk) {
            working = working.withError("Completion claim rejected: no successful tool observation supported it.")
            event(objectiveId, task.id, "completion_guard", "The final answer claimed completion without successful tool evidence; verification treats this as a failed tool observation.", status = "failed")
        }
        val toolFailures = if (developmentPassed != null) { if (developmentPassed == true) 0 else 1 } else
            outcome.events.count { it.kind == "tool_observation" && Regex("(?i)Tool (error|denied)").containsMatchIn(it.text) } +
                outcome.evidence.count { it.exitCode != null && it.exitCode != 0 } +
                (if (outcome.falseSuccessRisk) 1 else 0)
        val effectiveSpecs = if (task.workerKind == "development") specs + EvidenceSpec(EvidenceKind.EXIT_CODE, expected = "0") else specs
        require(task.workerKind == "inference" || effectiveSpecs.isNotEmpty()) { "Tool task has no declared evidence; no completion can be established." }
        val lastExit = outcome.evidence.lastOrNull { it.exitCode != null }
        val observation = ObservationBundle(
            exitCode = developmentFinalCommands?.lastOrNull()?.exitCode ?: lastExit?.exitCode,
            stdout = (developmentFinalCommands?.joinToString("\n") { it.stdout } ?: outcome.evidence.joinToString("\n") { it.stdout }).take(64_000),
            stderr = (developmentFinalCommands?.joinToString("\n") { it.stderr } ?: outcome.evidence.joinToString("\n") { it.stderr }).take(16_000),
            agentOutput = outcome.result,
            fileExists = { path -> fileProbe?.exists(path) ?: false },
            fileText = { path, max -> fileProbe?.read(path, max) },
            checksum = { path -> fileProbe?.sha256(path) },
        )
        val report = TaskVerifier.verify(effectiveSpecs, observation, toolFailures)

        val verifying = task.copy(
            status = "verifying",
            attemptCount = attempt,
            outcome = outcome.result.take(30_000),
            verificationStatus = if (report.passed) "passed" else "failed",
            evidenceJson = mergeEvidence(task.evidenceJson, report),
            checkpointJson = JSONObject().put("phase", "verifying").put("attempt", attempt)
                .put("sovereignState", working.toJson()).toString(),
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
                    checkpointJson = JSONObject().put("phase", "completed").put("attempt", attempt).put("verifiedAt", now())
                        .put("sovereignState", working.toJson()).toString(),
                    updatedAt = now(),
                )
            )
            event(objectiveId, task.id, "task_completed", "${task.title} completed with verified local evidence.")
            arsenalSkills.forEach { skill ->
                runCatching {
                    arsenal?.recordExecution(
                        skillId = skill.id,
                        objectiveId = objectiveId,
                        success = true,
                        detail = "Verified task: ${task.title.take(160)}",
                        evidence = report.results.filter { it.passed }.map { it.spec.kind.name.lowercase() },
                    )
                }
            }
            runCatching {
                projectMemory?.recordOutcome(
                    title = task.title.take(160),
                    summary = "Verified objective task completed. ${outcome.result.take(600)}",
                    success = true,
                    objectiveId = objectiveId,
                    evidenceRefs = listOf(
                        com.nyx.ai.context.MemoryEvidenceRef(
                            path = "objective/$objectiveId/task/${task.id}",
                            sha256 = sha256Hex(report.evidenceJson),
                        ),
                    ),
                    tags = listOf(task.workerKind, "verified"),
                )
            }
            return@coroutineScope
        }

        val mutating = outcome.evidence.any { it.mutating }
        val signature = TaskVerifier.failureSignature(task.id, report.reason)
        val decision = RecoveryPolicy.decide(
            attemptCount = attempt,
            maxAttempts = task.maxAttempts,
            idempotent = !mutating && task.workerKind == "verification" && allowed.none { it in (terminalTools?.names ?: emptySet()) },
            failureSignature = signature,
            previousFailureSignatures = listOfNotNull(task.failureSignature),
            risk = if (mutating) "mutating" else "read-only",
        )
        event(objectiveId, task.id, "recovery_${decision.action.name.lowercase()}", decision.reason)
        arsenalSkills.forEach { skill ->
            runCatching {
                arsenal?.recordExecution(
                    skillId = skill.id,
                    objectiveId = objectiveId,
                    success = false,
                    detail = "Task did not pass verification: ${(report.reason ?: "unknown failure").take(160)}",
                )
            }
        }
        runCatching {
            projectMemory?.recordOutcome(
                title = task.title.take(160),
                summary = "Task did not pass verification: ${(report.reason ?: "unknown failure").take(500)}",
                success = false,
                objectiveId = objectiveId,
                tags = listOf(task.workerKind, "failure"),
            )
        }
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
                    checkpointJson = JSONObject().put("phase", "needs_review").put("attempt", attempt)
                        .put("sovereignState", working.toJson()).toString(),
                    updatedAt = now(),
                )
            )
            RecoveryAction.FAIL_PERMANENTLY -> dao.upsertTask(
                verifying.copy(
                    status = "failed",
                    error = report.reason,
                    failureSignature = signature,
                    finishedAt = now(),
                    checkpointJson = JSONObject().put("phase", "failed").put("attempt", attempt)
                        .put("sovereignState", working.toJson()).toString(),
                    updatedAt = now(),
                )
            )
        }
        writeContext(objectiveId, task.id, "failure", task.title, report.reason ?: "Verification failed")
        if (decision.action != RecoveryAction.RETRY) {
            dao.getObjective(objectiveId)?.let {
                saveObjective(it.copy(status = "needs_attention", updatedAt = now(), error = report.reason))
            }
        }
    }

    private suspend fun saveObjective(row: ObjectiveEntity) = database.withTransaction {
        val current = dao.getObjective(row.id)
        dao.upsertObjective(row.copy(leaseOwner = current?.leaseOwner, leaseExpiresAt = current?.leaseExpiresAt))
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
            saveObjective(it.copy(status = "paused", updatedAt = now(), leaseOwner = null, leaseExpiresAt = null, error = message))
        }
    }

    private suspend fun needsAttention(objectiveId: String, message: String): ObjectiveEntity {
        val objective = requireNotNull(dao.getObjective(objectiveId))
        val attention = objective.copy(status = "needs_attention", updatedAt = now(), leaseOwner = null, leaseExpiresAt = null, error = message)
        saveObjective(attention)
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
        status: String? = null,
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
        WorkerActivityBus.record(
            kind = activityKind(kind),
            title = kind.replace('_', ' '),
            detail = detail,
            objectiveId = objectiveId,
            taskId = taskId,
            toolName = toolName,
            toolInput = toolInput,
            toolOutput = toolOutput,
            runId = activeRunId,
            status = status ?: statusForKind(kind),
        )
    }

    private fun statusForKind(kind: String): String? = when {
        kind == "planning" || kind == "planning_attempt" || kind == "planning_progress" ||
            kind == "execution_profile" || kind == "decision_summary" || kind == "tool_scope" -> "running"
        kind == "plan_created" || kind == "task_completed" || kind == "completed" || kind == "verification_passed" -> "completed"
        kind == "planning_failed" || kind == "task_error" || kind == "verification_failed" ||
            kind == "completion_guard" || kind == "failed" -> "failed"
        kind == "planning_validation_failed" -> "failed"
        kind == "planning_repair" || kind == "recovery_retry" || kind == "blocked" -> "waiting"
        kind == "recovery_needs_review" || kind == "checkpoint_interrupted" -> "interrupted"
        kind == "task_started" || kind == "task_running" -> "running"
        else -> null
    }

    private fun activityKind(kind: String): WorkerActivityKind = when {
        kind.startsWith("tool") -> WorkerActivityKind.TOOL
        kind.startsWith("install") || kind.startsWith("dependency") -> WorkerActivityKind.TOOL
        kind.startsWith("terminal") -> WorkerActivityKind.TERMINAL
        kind.startsWith("file") -> WorkerActivityKind.FILE
        kind.startsWith("skill") -> WorkerActivityKind.SKILL
        kind.startsWith("delegation") -> WorkerActivityKind.DELEGATION
        kind.startsWith("verification") -> WorkerActivityKind.VERIFICATION
        kind.startsWith("recovery") || kind == "retry_approved" || kind == "blocked" -> WorkerActivityKind.RECOVERY
        kind.startsWith("decision") || kind == "execution_profile" || kind == "tool_scope" -> WorkerActivityKind.SUPERVISOR
        kind.startsWith("task") || kind.startsWith("development") -> WorkerActivityKind.TASK
        kind.startsWith("plan") -> WorkerActivityKind.PLAN
        kind.startsWith("supervisor") -> WorkerActivityKind.SUPERVISOR
        kind.startsWith("completion") -> WorkerActivityKind.ERROR
        kind.contains("error") || kind == "failed" -> WorkerActivityKind.ERROR
        else -> WorkerActivityKind.INFO
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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

    private fun plannerPrompt(terminalEnabled: Boolean, toolNames: Set<String>): String = buildString {
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
            - Tool/file tasks MUST declare evidence; inference-only prose proves output existence, not semantic correctness. Development tasks use a dedicated build/test/repair loop with fixed detected commands. Never claim success without a verified exit and relevant file evidence.
            - Commands and files are limited to the user-authorized workspace. Destructive, privileged, credential, and publication actions are blocked.
            - If the objective cannot be completed with these capabilities, make the final task explain the blocker instead of inventing capabilities.
            """.trimIndent()
        )
        append("\n- ").append(SovereignPolicy.plannerCapabilityLine(toolNames, terminalEnabled))
        append("\n- Plan only capabilities that are actually listed. Do not plan terminal or development tasks when terminal tools are disabled.")
        append("\n- Prefer a minimal dependency graph. A failed task may receive one changed-approach retry, so include a verification task where success is otherwise ambiguous.")
    }

    @Suppress("unused")
    private fun ContextRecord.pathForDisplay(): String = filePath
}

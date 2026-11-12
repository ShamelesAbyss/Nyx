package com.nyx.ai.autonomy

enum class WorkerKind { INFERENCE, TERMINAL, FILESYSTEM, VERIFICATION, DEVELOPMENT }

data class SchedulingPolicy(
    val maxInference: Int = 1,
    val maxTerminal: Int = 2,
    val maxFilesystem: Int = 2,
    val maxVerification: Int = 2,
    val maxDevelopment: Int = 1,
    val maxTotal: Int = 3,
)

data class ScheduledTask(
    val id: String,
    val ordinal: Int,
    val priority: Int = 0,
    val workerKind: WorkerKind = WorkerKind.INFERENCE,
    val dependsOn: Set<String> = emptySet(),
    val status: String = "pending",
)

data class SchedulingDecision(
    val start: List<ScheduledTask>,
    val blocked: List<ScheduledTask>,
    val deadlocked: Boolean,
    val activeTotal: Int,
    val activeByKind: Map<WorkerKind, Int>,
)

object ConcurrentTaskScheduler {
    private val activeStatuses = setOf("running", "verifying")
    private val terminalFailureStatuses = setOf("failed", "blocked", "needs_review", "cancelled")

    fun decide(tasks: List<ScheduledTask>, policy: SchedulingPolicy): SchedulingDecision {
        val byId = tasks.associateBy { it.id }
        val activeTasks = tasks.filter { it.status in activeStatuses }
        val activeByKind = WorkerKind.values().associateWith { kind ->
            activeTasks.count { it.workerKind == kind }
        }
        val activeTotal = activeTasks.size

        val pendingTasks = tasks.filter { it.status == "pending" }
        // Blocking propagates: a pending task whose prerequisite can never complete is blocked too,
        // so the engine reports the whole stalled subgraph instead of only its first layer.
        val blockedIds = HashSet<String>()
        var changed = true
        while (changed) {
            changed = false
            for (task in pendingTasks) {
                if (task.id in blockedIds) continue
                val isBlocked = task.dependsOn.any { dependencyId ->
                    val dependency = byId[dependencyId]
                    dependency == null || dependency.status in terminalFailureStatuses || dependencyId in blockedIds
                }
                if (isBlocked) {
                    blockedIds += task.id
                    changed = true
                }
            }
        }
        val blocked = pendingTasks.filter { it.id in blockedIds }
            .sortedWith(compareBy<ScheduledTask>({ it.ordinal }, { it.id }))
        val ready = pendingTasks.filter { task ->
            task.id !in blockedIds && task.dependsOn.all { dependencyId ->
                byId[dependencyId]?.status == "completed"
            }
        }.sortedWith(compareByDescending<ScheduledTask> { it.priority }
            .thenBy { it.ordinal }
            .thenBy { it.id })

        val usedByKind = activeByKind.toMutableMap()
        var usedTotal = activeTotal
        val selected = mutableListOf<ScheduledTask>()
        for (task in ready) {
            if (usedTotal >= policy.maxTotal) continue
            val capacity = capacityFor(task.workerKind, policy)
            val used = usedByKind.getValue(task.workerKind)
            if (used >= capacity) continue
            selected += task
            usedByKind[task.workerKind] = used + 1
            usedTotal++
        }

        // A cycle among unresolved pending prerequisites means no task in that cycle can
        // ever reach readiness. A merely saturated scheduler is not deadlocked: at least
        // one ready task exists, even if current active work prevents launching it now.
        val deadlocked = pendingTasks.isNotEmpty() && blocked.isEmpty() && ready.isEmpty() &&
            hasPendingDependencyCycle(pendingTasks, byId)

        return SchedulingDecision(
            start = selected,
            blocked = blocked,
            deadlocked = deadlocked,
            activeTotal = activeTotal,
            activeByKind = activeByKind,
        )
    }

    private fun capacityFor(kind: WorkerKind, policy: SchedulingPolicy): Int = when (kind) {
        WorkerKind.INFERENCE -> policy.maxInference
        WorkerKind.TERMINAL -> policy.maxTerminal
        WorkerKind.FILESYSTEM -> policy.maxFilesystem
        WorkerKind.VERIFICATION -> policy.maxVerification
        WorkerKind.DEVELOPMENT -> policy.maxDevelopment
    }

    private fun hasPendingDependencyCycle(
        pendingTasks: List<ScheduledTask>,
        byId: Map<String, ScheduledTask>,
    ): Boolean {
        val pendingIds = pendingTasks.mapTo(HashSet()) { it.id }
        val state = HashMap<String, Int>() // 1 = visiting, 2 = fully explored

        fun visit(id: String): Boolean {
            when (state[id]) {
                1 -> return true
                2 -> return false
            }
            state[id] = 1
            val task = byId[id] ?: return false
            for (dependencyId in task.dependsOn.sorted()) {
                if (dependencyId in pendingIds && visit(dependencyId)) return true
            }
            state[id] = 2
            return false
        }

        return pendingIds.sorted().any { id -> state[id] == null && visit(id) }
    }
}

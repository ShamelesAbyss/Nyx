package com.nyx.ai.autonomy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Thrown at a cooperative checkpoint when the user pauses an objective. */
class PauseRequested : CancellationException("Objective paused by user")

/** Cooperative pause/cancel flags checked between model turns and tool calls. */
class ExecutionControl {
    @Volatile private var pauseRequested = false
    @Volatile private var cancelRequested = false

    val isPauseRequested: Boolean get() = pauseRequested
    val isCancelRequested: Boolean get() = cancelRequested

    fun requestPause() { pauseRequested = true }
    fun requestCancel() { cancelRequested = true }
    fun clearPause() { pauseRequested = false }

    fun checkpoint() {
        if (cancelRequested) throw CancellationException("Objective cancelled by user")
        if (pauseRequested) throw PauseRequested()
    }
}

data class ExecutionSnapshot(
    val objectiveId: String? = null,
    val running: Boolean = false,
    val phase: String = "idle",
    val activeTasks: Int = 0,
    val queuedTasks: Int = 0,
    val message: String? = null,
    val startedAt: Long? = null,
)

/** Process-wide view of the service-owned objective execution, independent of any Activity. */
object ExecutionMonitor {
    private val _state = MutableStateFlow(ExecutionSnapshot())
    val state: StateFlow<ExecutionSnapshot> = _state.asStateFlow()

    fun update(transform: (ExecutionSnapshot) -> ExecutionSnapshot) = _state.update(transform)

    fun begin(objectiveId: String) {
        _state.value = ExecutionSnapshot(objectiveId = objectiveId, running = true, phase = "starting", startedAt = System.currentTimeMillis())
    }

    fun finish(message: String? = null) {
        _state.update { it.copy(running = false, phase = "idle", activeTasks = 0, queuedTasks = 0, message = message) }
    }
}

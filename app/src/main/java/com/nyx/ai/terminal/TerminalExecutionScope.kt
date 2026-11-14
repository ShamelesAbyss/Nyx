package com.nyx.ai.terminal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Engine hook: withContext(TerminalExecutionScope(objectiveId, taskId)) { executeTask() }. */
class TerminalExecutionScope(val objectiveId: String, val taskId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TerminalExecutionScope>
}

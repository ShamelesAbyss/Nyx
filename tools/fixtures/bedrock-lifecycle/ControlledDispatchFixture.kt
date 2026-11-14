package com.nyx.ai.terminal

import kotlinx.coroutines.currentCoroutineContext

/** Fake dispatcher only; the real binding, shell builder, journal and identity codec are exercised. */
class TerminalBridge {
    var bound: TerminalOperationContext? = null
    suspend fun execute(request: TerminalCommandRequest, operation: TerminalOperationContext): TerminalCommandResult {
        val context = currentCoroutineContext()
        bound = TerminalDispatchBinding.bind(request, operation, context[TerminalExecutionScope], context[TerminalCommandDispatchScope]?.identity)
        return TerminalCommandResult(operation.operationId, TerminalResultKind.SUCCEEDED, exitCode = 0)
    }
}
/** Unused guest-probe text dependency. No probe or Termux guest is simulated as passing. */
object GuestProbeParser { fun script(): String = error("Guest probe not part of lifecycle host fixture") }

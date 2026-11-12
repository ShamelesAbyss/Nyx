package com.nyx.ai.terminal

/**
 * One place to decide what a terminal result means. Kept pure so the mapping is tested without
 * Android and so the installer, repository history, and UI cannot drift apart.
 */
object TerminalResultPolicy {
    fun persistedStatus(kind: TerminalResultKind): String = when (kind) {
        TerminalResultKind.SUCCEEDED -> "succeeded"
        TerminalResultKind.PROCESS_EXIT -> "process_exit"
        TerminalResultKind.TIMEOUT -> "timeout"
        TerminalResultKind.CANCELLED -> "cancelled"
        TerminalResultKind.CONFIRMATION_REQUIRED -> "confirmation_required"
        TerminalResultKind.POLICY_BLOCKED, TerminalResultKind.DENIED -> "denied"
        else -> "failed"
    }

    fun isSuccessfulCommand(exitCode: Int?, kind: TerminalResultKind): Boolean =
        exitCode == 0 &&
            kind != TerminalResultKind.TIMEOUT &&
            kind != TerminalResultKind.POLICY_BLOCKED &&
            kind != TerminalResultKind.DENIED &&
            kind != TerminalResultKind.CANCELLED &&
            kind != TerminalResultKind.CONFIRMATION_REQUIRED

    /** Installation requires a real zero exit, never merely the absence of a parsed error. */
    fun isSuccessfulInstall(exitCode: Int?, kind: TerminalResultKind): Boolean =
        isSuccessfulCommand(exitCode, kind)

    fun isUncertain(kind: TerminalResultKind, exitCode: Int?): Boolean =
        kind == TerminalResultKind.CANCELLED || kind == TerminalResultKind.TIMEOUT ||
            (kind == TerminalResultKind.PROCESS_EXIT && exitCode == null)
}

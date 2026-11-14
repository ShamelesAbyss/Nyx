package com.nyx.ai.terminal

/** A wrapper callback does not prove all external descendants exited. Never infer PID termination. */
object TerminalOwnershipPolicy {
    fun requiresReservation(kind: String, exitCode: Int?): Boolean =
        kind in setOf("TIMEOUT", "CANCELLED", "UNKNOWN_RESULT", "TERMUX_INFRASTRUCTURE_ERROR") ||
            (kind == "PROCESS_EXIT" && (exitCode == null || exitCode == 124 || exitCode == 137))
}

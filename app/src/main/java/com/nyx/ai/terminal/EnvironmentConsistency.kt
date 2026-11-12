package com.nyx.ai.terminal

/** Current usability of the environment stored in an objective snapshot. */
data class EnvironmentState(
    val usable: Boolean,
    val message: String,
    val stalePreviousError: Boolean = false,
)

/**
 * Reconciles a freshly discovered [EnvironmentProfile] with the environment recorded in an
 * objective's execution snapshot. A historical error is never treated as current proof, and a
 * successful probe clears that stale error when the objective resumes.
 */
object EnvironmentConsistency {
    private val ENVIRONMENT_ERROR_MARKERS = listOf(
        "termux", "proot", "debian", "guest", "environment", "not verified",
        "not visible", "run_command", "allow-external-apps", "nested",
    )

    fun evaluate(
        profile: EnvironmentProfile,
        environment: TerminalEnvironment,
        guestAlias: String,
        previousError: String? = null,
    ): EnvironmentState {
        val usable = when (environment) {
            TerminalEnvironment.TERMUX_HOST -> profile.hostAvailable && !profile.nestedProot
            TerminalEnvironment.DEBIAN_PROOT -> profile.verifiedDistros.any { it.equals(guestAlias, ignoreCase = true) }
        }
        val message = when {
            usable && environment == TerminalEnvironment.DEBIAN_PROOT ->
                "Debian guest '$guestAlias' is currently verified by a real guest command."

            usable -> "The Termux host is currently verified."

            environment == TerminalEnvironment.DEBIAN_PROOT -> {
                val guest = profile.guest(guestAlias)
                profile.listError
                    ?: guest?.detail
                    ?: "Debian guest '$guestAlias' is not currently verified. Re-run environment detection in Terminal."
            }

            !profile.hostAvailable -> profile.detail
                ?: "Termux is not available to NYX. Check the RUN_COMMAND permission and allow-external-apps."

            profile.nestedProot -> profile.detail
                ?: "Termux commands are landing inside a PRoot guest instead of the host shell."

            else -> "The selected terminal environment is not currently usable."
        }
        return EnvironmentState(
            usable = usable,
            message = message.take(600),
            stalePreviousError = usable && previousError?.let { error ->
                val text = error.lowercase()
                ENVIRONMENT_ERROR_MARKERS.any { text.contains(it) }
            } == true,
        )
    }
}

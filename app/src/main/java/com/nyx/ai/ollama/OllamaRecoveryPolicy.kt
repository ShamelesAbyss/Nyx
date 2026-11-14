package com.nyx.ai.ollama

/**
 * The evidence-backed recovery states for a persisted PRoot launch.
 *
 * A saved token is not proof that a server is alive, and a missing PID is not proof that it is
 * gone. The manager must collect independent observations before it may release ownership.
 */
enum class OllamaRecoveryState {
    /** A verified NYX-owned guest process is alive. */
    HEALTHY_OWNED,

    /** An Ollama-compatible API answers but NYX cannot claim the process. */
    HEALTHY_EXTERNAL,

    /** A token/start-verified PRoot session is alive while the guest API is not ready. */
    VERIFIED_LIVE_SESSION,

    /** The saved process and its verified session are both absent. */
    STALE_RECORD_RECOVERABLE,

    /** A pending checkpoint is old enough and no process/session carries its token. */
    PENDING_LAUNCH_RECOVERABLE,

    /** The pending launch is still inside the verification grace period. */
    PENDING_LAUNCH_UNCONFIRMED,

    /** Some evidence says a protected process may still exist, but identity is not complete. */
    AMBIGUOUS_OWNERSHIP,

    /** A required read-only probe failed or did not answer. */
    VERIFICATION_UNAVAILABLE,
}

data class OllamaRecoveryAssessment(
    val state: OllamaRecoveryState,
    val canClearOwnership: Boolean,
    val detail: String,
)

/**
 * Inputs are observations, not desired outcomes. A null session/token observation means the probe
 * was unavailable and therefore can never authorize release.
 */
data class OllamaRecoverySignals(
    val apiHealthy: Boolean,
    val savedSession: SavedOllamaSession?,
    val savedRecord: SavedOllamaProcess?,
    val guestProbeSucceeded: Boolean,
    val guestOwnership: OllamaOwnership,
    val sessionAlive: Boolean?,
    val sessionIdentityMatches: Boolean,
    val tokenProcessFound: Boolean?,
    val pendingAgeMillis: Long?,
)

/**
 * Conservative recovery rules for a persisted guest launch.
 *
 * The only release paths require all of:
 * - a successful read-only guest probe;
 * - no live NYX-owned guest process;
 * - no token-bearing host/session process; and
 * - either a verified-dead saved session, or an old pending checkpoint.
 *
 * A live session or an ambiguous/unavailable observation always retains the fence.
 */
object OllamaRecoveryPolicy {
    /** Do not release a pending checkpoint until a bounded observation window has passed. */
    const val PENDING_LAUNCH_GRACE_MILLIS = 120_000L

    fun assess(signals: OllamaRecoverySignals): OllamaRecoveryAssessment {
        if (signals.apiHealthy) {
            val owned = signals.savedSession != null && signals.guestOwnership == OllamaOwnership.OWNED
            return OllamaRecoveryAssessment(
                state = if (owned) OllamaRecoveryState.HEALTHY_OWNED else OllamaRecoveryState.HEALTHY_EXTERNAL,
                canClearOwnership = false,
                detail = if (owned) {
                    "The NYX-started Ollama process is alive and answering; ownership is verified."
                } else {
                    "An Ollama server is answering, but this record does not prove NYX owns its process. It will be adopted, not signalled."
                },
            )
        }

        val session = signals.savedSession
            ?: return unavailable("No saved PRoot launch identity exists to recover.")
        if (!signals.guestProbeSucceeded) {
            return unavailable("The guest process record could not be read, so NYX cannot prove that its Ollama process is gone.")
        }
        when (signals.guestOwnership) {
            OllamaOwnership.OWNED -> return OllamaRecoveryAssessment(
                OllamaRecoveryState.HEALTHY_OWNED,
                false,
                "The saved guest Ollama process is still the verified NYX-owned process; no duplicate launch is allowed.",
            )
            OllamaOwnership.UNKNOWN -> return ambiguous(
                "The guest process exists but its start token or command line could not be verified. NYX will not release ownership or signal it.",
            )
            OllamaOwnership.MISSING,
            OllamaOwnership.PID_REUSED,
            OllamaOwnership.NOT_OLLAMA,
            -> Unit
        }

        val pending = session.mode == OllamaLaunchMode.PROOT_DETACHED &&
            (session.sessionPid == null || session.sessionStartTicks == null)
        if (pending) {
            val age = signals.pendingAgeMillis
            if (age == null || age < PENDING_LAUNCH_GRACE_MILLIS) {
                return OllamaRecoveryAssessment(
                    OllamaRecoveryState.PENDING_LAUNCH_UNCONFIRMED,
                    false,
                    "The pending launch checkpoint is still inside its verification grace period. Refresh again after the dispatch window closes.",
                )
            }
            return when (signals.tokenProcessFound) {
                true -> OllamaRecoveryAssessment(
                    OllamaRecoveryState.VERIFIED_LIVE_SESSION,
                    false,
                    "A process carrying the saved launch token is still visible; ownership remains protected.",
                )
                null -> unavailable("The token process scan did not complete, so NYX cannot prove the pending launch is absent.")
                false -> OllamaRecoveryAssessment(
                    OllamaRecoveryState.PENDING_LAUNCH_RECOVERABLE,
                    true,
                    "The pending checkpoint is older than the grace period, the guest record is gone, and no process carries its token. Stale ownership can be cleared.",
                )
            }
        }

        if (session.mode == OllamaLaunchMode.PROOT_DETACHED) {
            return when (signals.sessionAlive) {
                true -> if (signals.sessionIdentityMatches) {
                    OllamaRecoveryAssessment(
                        OllamaRecoveryState.VERIFIED_LIVE_SESSION,
                        false,
                        "The saved PRoot session is alive and its token, namespace and kernel start token match. It will not be duplicated.",
                    )
                } else {
                    ambiguous("The saved session PID is alive but its token or start identity does not match. NYX will not signal it.")
                }
                null -> unavailable("The saved PRoot session identity probe did not complete.")
                false -> when (signals.tokenProcessFound) {
                    true -> ambiguous("The saved session PID is gone, but a process carrying its token is still visible.")
                    null -> unavailable("The token process scan did not complete after the saved session disappeared.")
                    false -> OllamaRecoveryAssessment(
                        OllamaRecoveryState.STALE_RECORD_RECOVERABLE,
                        true,
                        "The saved guest process is gone, its PRoot session is absent, and no process carries the launch token. Stale ownership can be cleared.",
                    )
                }
            }
        }

        if (session.mode == OllamaLaunchMode.PROOT_FOREGROUND) {
            if (session.pendingAtMillis > 0L &&
                (signals.pendingAgeMillis == null || signals.pendingAgeMillis < PENDING_LAUNCH_GRACE_MILLIS)
            ) {
                return OllamaRecoveryAssessment(
                    OllamaRecoveryState.PENDING_LAUNCH_UNCONFIRMED,
                    false,
                    "The foreground launch checkpoint is still inside its verification grace period.",
                )
            }
            return when (signals.tokenProcessFound) {
                true -> OllamaRecoveryAssessment(
                    OllamaRecoveryState.VERIFIED_LIVE_SESSION,
                    false,
                    "A foreground PRoot process carrying the saved launch token is still visible; ownership remains protected.",
                )
                null -> unavailable("The foreground token process scan did not complete.")
                false -> OllamaRecoveryAssessment(
                    OllamaRecoveryState.STALE_RECORD_RECOVERABLE,
                    true,
                    "The saved guest process is gone and no foreground process carries the launch token. Stale ownership can be cleared.",
                )
            }
        }

        return ambiguous("The saved launch mode does not identify a guest process that NYX can verify.")
    }

    private fun unavailable(detail: String) = OllamaRecoveryAssessment(
        OllamaRecoveryState.VERIFICATION_UNAVAILABLE,
        false,
        detail,
    )

    private fun ambiguous(detail: String) = OllamaRecoveryAssessment(
        OllamaRecoveryState.AMBIGUOUS_OWNERSHIP,
        false,
        detail,
    )
}

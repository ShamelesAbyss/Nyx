package com.nyx.ai.ollama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaRecoveryPolicyTest {
    private val token = "nyx-1234567890abcdef"
    private val detached = SavedOllamaSession(
        token = token,
        mode = OllamaLaunchMode.PROOT_DETACHED,
        sessionPid = 1234,
        sessionStartTicks = 5678,
        container = "debian",
        pendingAtMillis = 1L,
    )

    private fun signals(
        session: SavedOllamaSession? = detached,
        apiHealthy: Boolean = false,
        guestProbeSucceeded: Boolean = true,
        ownership: OllamaOwnership = OllamaOwnership.MISSING,
        alive: Boolean? = false,
        matches: Boolean = false,
        tokenFound: Boolean? = false,
        pendingAge: Long? = null,
    ) = OllamaRecoverySignals(
        apiHealthy = apiHealthy,
        savedSession = session,
        savedRecord = null,
        guestProbeSucceeded = guestProbeSucceeded,
        guestOwnership = ownership,
        sessionAlive = alive,
        sessionIdentityMatches = matches,
        tokenProcessFound = tokenFound,
        pendingAgeMillis = pendingAge,
    )

    @Test
    fun healthyExternalServerIsAdoptedWithoutClaimingOwnership() {
        val result = OllamaRecoveryPolicy.assess(signals(apiHealthy = true, ownership = OllamaOwnership.UNKNOWN))
        assertEquals(OllamaRecoveryState.HEALTHY_EXTERNAL, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun healthyOwnedServerIsNotReleased() {
        val result = OllamaRecoveryPolicy.assess(
            signals(apiHealthy = true, ownership = OllamaOwnership.OWNED, alive = true, matches = true),
        )
        assertEquals(OllamaRecoveryState.HEALTHY_OWNED, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun failedGuestProbeNeverAuthorizesRelease() {
        val result = OllamaRecoveryPolicy.assess(signals(guestProbeSucceeded = false))
        assertEquals(OllamaRecoveryState.VERIFICATION_UNAVAILABLE, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun unknownGuestOwnershipRemainsAmbiguous() {
        val result = OllamaRecoveryPolicy.assess(signals(ownership = OllamaOwnership.UNKNOWN))
        assertEquals(OllamaRecoveryState.AMBIGUOUS_OWNERSHIP, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun liveOwnedGuestProcessBlocksRelease() {
        val result = OllamaRecoveryPolicy.assess(signals(ownership = OllamaOwnership.OWNED))
        assertEquals(OllamaRecoveryState.HEALTHY_OWNED, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun verifiedLiveDetachedSessionBlocksDuplicateLaunch() {
        val result = OllamaRecoveryPolicy.assess(signals(alive = true, matches = true))
        assertEquals(OllamaRecoveryState.VERIFIED_LIVE_SESSION, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun livePidWithMismatchedIdentityIsAmbiguous() {
        val result = OllamaRecoveryPolicy.assess(signals(alive = true, matches = false))
        assertEquals(OllamaRecoveryState.AMBIGUOUS_OWNERSHIP, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun unavailableSessionProbeNeverAuthorizesRelease() {
        val result = OllamaRecoveryPolicy.assess(signals(alive = null))
        assertEquals(OllamaRecoveryState.VERIFICATION_UNAVAILABLE, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun deadDetachedSessionWithVisibleTokenRemainsAmbiguous() {
        val result = OllamaRecoveryPolicy.assess(signals(alive = false, tokenFound = true))
        assertEquals(OllamaRecoveryState.AMBIGUOUS_OWNERSHIP, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun deadDetachedSessionWithUnavailableTokenScanIsNotReleased() {
        val result = OllamaRecoveryPolicy.assess(signals(alive = false, tokenFound = null))
        assertEquals(OllamaRecoveryState.VERIFICATION_UNAVAILABLE, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun deadDetachedSessionWithNoTokenAndNoGuestProcessIsRecoverable() {
        val result = OllamaRecoveryPolicy.assess(signals(alive = false, tokenFound = false))
        assertEquals(OllamaRecoveryState.STALE_RECORD_RECOVERABLE, result.state)
        assertTrue(result.canClearOwnership)
    }

    @Test
    fun foregroundTokenProcessIsLiveAndProtected() {
        val foreground = detached.copy(mode = OllamaLaunchMode.PROOT_FOREGROUND, sessionPid = null, sessionStartTicks = null, pendingAtMillis = 0L)
        val result = OllamaRecoveryPolicy.assess(signals(session = foreground, tokenFound = true))
        assertEquals(OllamaRecoveryState.VERIFIED_LIVE_SESSION, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun foregroundWithoutTokenScanIsUnavailable() {
        val foreground = detached.copy(mode = OllamaLaunchMode.PROOT_FOREGROUND, sessionPid = null, sessionStartTicks = null, pendingAtMillis = 0L)
        val result = OllamaRecoveryPolicy.assess(signals(session = foreground, tokenFound = null))
        assertEquals(OllamaRecoveryState.VERIFICATION_UNAVAILABLE, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun foregroundWithNoTokenAndNoGuestProcessIsRecoverable() {
        val foreground = detached.copy(mode = OllamaLaunchMode.PROOT_FOREGROUND, sessionPid = null, sessionStartTicks = null, pendingAtMillis = 0L)
        val result = OllamaRecoveryPolicy.assess(signals(session = foreground, tokenFound = false))
        assertEquals(OllamaRecoveryState.STALE_RECORD_RECOVERABLE, result.state)
        assertTrue(result.canClearOwnership)
    }

    @Test
    fun pendingLaunchInsideGraceIsNotReleased() {
        val pending = detached.copy(sessionPid = null, sessionStartTicks = null, pendingAtMillis = 1L)
        val result = OllamaRecoveryPolicy.assess(signals(session = pending, pendingAge = 1_000L))
        assertEquals(OllamaRecoveryState.PENDING_LAUNCH_UNCONFIRMED, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun pendingLaunchWithoutTimestampIsNotReleased() {
        val pending = detached.copy(sessionPid = null, sessionStartTicks = null, pendingAtMillis = 0L)
        val result = OllamaRecoveryPolicy.assess(signals(session = pending, pendingAge = null))
        assertEquals(OllamaRecoveryState.PENDING_LAUNCH_UNCONFIRMED, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun oldPendingLaunchWithVisibleTokenIsLive() {
        val pending = detached.copy(sessionPid = null, sessionStartTicks = null, pendingAtMillis = 1L)
        val result = OllamaRecoveryPolicy.assess(
            signals(session = pending, pendingAge = OllamaRecoveryPolicy.PENDING_LAUNCH_GRACE_MILLIS, tokenFound = true),
        )
        assertEquals(OllamaRecoveryState.VERIFIED_LIVE_SESSION, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun oldPendingLaunchWithUnavailableScanIsNotReleased() {
        val pending = detached.copy(sessionPid = null, sessionStartTicks = null, pendingAtMillis = 1L)
        val result = OllamaRecoveryPolicy.assess(
            signals(session = pending, pendingAge = OllamaRecoveryPolicy.PENDING_LAUNCH_GRACE_MILLIS, tokenFound = null),
        )
        assertEquals(OllamaRecoveryState.VERIFICATION_UNAVAILABLE, result.state)
        assertFalse(result.canClearOwnership)
    }

    @Test
    fun oldPendingLaunchWithNoTokenAndNoGuestProcessIsRecoverable() {
        val pending = detached.copy(sessionPid = null, sessionStartTicks = null, pendingAtMillis = 1L)
        val result = OllamaRecoveryPolicy.assess(
            signals(session = pending, pendingAge = OllamaRecoveryPolicy.PENDING_LAUNCH_GRACE_MILLIS, tokenFound = false),
        )
        assertEquals(OllamaRecoveryState.PENDING_LAUNCH_RECOVERABLE, result.state)
        assertTrue(result.canClearOwnership)
    }

    @Test
    fun hostDetachedIdentityCannotRecoverAGuestFence() {
        val host = detached.copy(mode = OllamaLaunchMode.HOST_DETACHED, container = "debian")
        val result = OllamaRecoveryPolicy.assess(signals(session = host))
        assertEquals(OllamaRecoveryState.AMBIGUOUS_OWNERSHIP, result.state)
        assertFalse(result.canClearOwnership)
    }
}

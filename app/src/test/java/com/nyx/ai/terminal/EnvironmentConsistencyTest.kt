package com.nyx.ai.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentConsistencyTest {
    private fun guestProfile(
        verified: Boolean = true,
        listError: String? = null,
    ) = EnvironmentProfile(
        hostAvailable = true,
        isolatedHost = true,
        verifiedDistros = if (verified) listOf("debian") else emptyList(),
        guests = mapOf("debian" to GuestProbe(verified, detail = if (verified) null else "guest command failed")),
        listError = listError,
    )

    @Test
    fun `current verified guest clears a stale historical error`() {
        val state = EnvironmentConsistency.evaluate(
            profile = guestProfile(),
            environment = TerminalEnvironment.DEBIAN_PROOT,
            guestAlias = "debian",
            previousError = "PRoot guest 'debian' is not verified.",
        )
        assertTrue(state.usable)
        assertTrue(state.stalePreviousError)
        assertTrue(state.message.contains("currently verified"))
    }

    @Test
    fun `unverified guest reports the current diagnostic instead of a stale error`() {
        val state = EnvironmentConsistency.evaluate(
            profile = guestProfile(verified = false, listError = "proot-distro list failed"),
            environment = TerminalEnvironment.DEBIAN_PROOT,
            guestAlias = "debian",
            previousError = "old generic failure",
        )
        assertFalse(state.usable)
        assertTrue(state.message.contains("proot-distro list failed"))
    }

    @Test
    fun `nested proot host is not usable as the host environment`() {
        val state = EnvironmentConsistency.evaluate(
            profile = EnvironmentProfile(hostAvailable = true, nestedProot = true, detail = "Termux is inside a guest."),
            environment = TerminalEnvironment.TERMUX_HOST,
            guestAlias = "debian",
        )
        assertFalse(state.usable)
        assertTrue(state.message.contains("guest"))
    }

    @Test
    fun `available host is usable`() {
        val state = EnvironmentConsistency.evaluate(
            profile = EnvironmentProfile(hostAvailable = true, isolatedHost = true),
            environment = TerminalEnvironment.TERMUX_HOST,
            guestAlias = "debian",
        )
        assertTrue(state.usable)
        assertFalse(state.stalePreviousError)
    }

    @Test
    fun `unrelated task failure is not labelled a stale environment error`() {
        val state = EnvironmentConsistency.evaluate(
            profile = guestProfile(),
            environment = TerminalEnvironment.DEBIAN_PROOT,
            guestAlias = "debian",
            previousError = "The build failed because a unit test asserted the wrong value.",
        )
        assertTrue(state.usable)
        assertFalse(state.stalePreviousError)
    }
}

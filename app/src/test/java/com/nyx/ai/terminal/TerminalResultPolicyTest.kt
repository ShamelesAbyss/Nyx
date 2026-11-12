package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResultPolicyTest {
    @Test
    fun `history status preserves confirmation and denial distinctions`() {
        assertEquals("confirmation_required", TerminalResultPolicy.persistedStatus(TerminalResultKind.CONFIRMATION_REQUIRED))
        assertEquals("denied", TerminalResultPolicy.persistedStatus(TerminalResultKind.POLICY_BLOCKED))
        assertEquals("denied", TerminalResultPolicy.persistedStatus(TerminalResultKind.DENIED))
        assertEquals("timeout", TerminalResultPolicy.persistedStatus(TerminalResultKind.TIMEOUT))
        assertEquals("succeeded", TerminalResultPolicy.persistedStatus(TerminalResultKind.SUCCEEDED))
    }

    @Test
    fun `install requires a zero exit and a terminal success kind`() {
        assertTrue(TerminalResultPolicy.isSuccessfulInstall(0, TerminalResultKind.SUCCEEDED))
        assertFalse(TerminalResultPolicy.isSuccessfulInstall(1, TerminalResultKind.PROCESS_EXIT))
        assertFalse(TerminalResultPolicy.isSuccessfulInstall(0, TerminalResultKind.TIMEOUT))
        assertFalse(TerminalResultPolicy.isSuccessfulInstall(0, TerminalResultKind.CONFIRMATION_REQUIRED))
        assertFalse(TerminalResultPolicy.isSuccessfulInstall(0, TerminalResultKind.POLICY_BLOCKED))
        assertFalse(TerminalResultPolicy.isSuccessfulInstall(null, TerminalResultKind.CANCELLED))
    }

    @Test
    fun `cancelled or missing exit remains uncertain`() {
        assertTrue(TerminalResultPolicy.isUncertain(TerminalResultKind.CANCELLED, null))
        assertTrue(TerminalResultPolicy.isUncertain(TerminalResultKind.TIMEOUT, 124))
        assertTrue(TerminalResultPolicy.isUncertain(TerminalResultKind.PROCESS_EXIT, null))
        assertFalse(TerminalResultPolicy.isUncertain(TerminalResultKind.SUCCEEDED, 0))
    }
}

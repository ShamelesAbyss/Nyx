package com.nyx.ai.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalCommandTimeoutTest {
    @Test fun authorizedPatientToolIsNotSilentlyCutToTenMinutes() {
        val request = ShellCommandBuilder.hostRequest("printf ok", "/work", 1800, true)
        assertEquals(1_800_000L, request.timeoutMillis)
        assertTrue(request.arguments.last().startsWith("timeout -k 5 1800 bash -lc "))
        assertTrue(TerminalRequestValidator.validate(request).isEmpty())
    }
    @Test fun terminalAndShellShareFiniteSegmentMaximumWithoutOverflow() {
        val host = ShellCommandBuilder.hostRequest("printf ok", null, Long.MAX_VALUE, true)
        val guest = ShellCommandBuilder.prootRequest("printf ok", "/work", Long.MAX_VALUE, true)
        assertEquals(7_200_000L, host.timeoutMillis)
        assertEquals(host.timeoutMillis, guest.timeoutMillis)
        assertTrue(host.arguments.last().startsWith("timeout -k 5 7200 "))
        assertTrue(TerminalRequestValidator.validate(host.copy(timeoutMillis = 7_200_001L)).any { it.field == "timeoutMillis" })
    }
    @Test fun legacyDefaultAndPermissionDenialRemainUnchanged() {
        assertEquals(120_000L, ShellCommandBuilder.hostRequest("printf ok", null, null, false).timeoutMillis)
        assertFalse(TerminalCommandRequest(TerminalEnvironment.TERMUX_HOST, "/bin/true").authorized)
        assertEquals("printf ok", ShellCommandBuilder.wrap("printf ok", null, 1800, false))
    }
}

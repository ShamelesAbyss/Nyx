package com.nyx.ai.ollama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaGuestRuntimeTest {
    @Test
    fun capabilityProbeRequiresDetachPsAndKillTogether() {
        val all = OllamaGuestRuntime.parseSessionCapabilities(
            """
            NYX_PROOT_LOGIN_DETACH=1
            NYX_PROOT_PS=1
            NYX_PROOT_KILL=1
            """.trimIndent(),
        )
        assertTrue(all.supportsDetachedSession)

        val noKill = OllamaGuestRuntime.parseSessionCapabilities(
            """
            NYX_PROOT_LOGIN_DETACH=1
            NYX_PROOT_PS=1
            NYX_PROOT_KILL=0
            """.trimIndent(),
        )
        assertFalse(noKill.supportsDetachedSession)
    }

    @Test
    fun sessionListParsesDetachedRowsAndIgnoresHeadersAndGarbage() {
        val sessions = OllamaGuestRuntime.parseSessionList(
            """
            PID         CONTAINER   TYPE      USER      UPTIME    COMMAND
            10231       debian      login*    u0_a123   00:03:12  /bin/bash -lc ollama serve
            10232       debian      login     u0_a123   00:00:11  bash
            not-a-pid   debian      login     u0_a123   00:00:01  bash
            12          bad alias!  login     u0_a123   00:00:01  bash
            """.trimIndent(),
        )
        assertEquals(2, sessions.size)
        assertEquals(10231, sessions[0].pid)
        assertTrue(sessions[0].detached)
        assertFalse(sessions[1].detached)
        assertTrue(sessions[0].command.contains("ollama serve"))
    }

    @Test
    fun newSessionChoosesOnlyTheNewRowForTheRequestedContainer() {
        val before = listOf(ProotSession(10, "debian", "login", true, "old"))
        val after = listOf(
            ProotSession(10, "debian", "login", true, "old"),
            ProotSession(11, "other", "login", true, "other"),
            ProotSession(12, "debian", "login", true, "new"),
        )
        assertEquals(12, OllamaGuestRuntime.newSession(before, after, "debian")?.pid)
        assertNull(OllamaGuestRuntime.newSession(before, before, "debian"))
    }

    @Test
    fun startupStageNeverCallsAGuestApiWaitingProcessReady() {
        assertEquals(
            OllamaStartupStage.PROCESS_GONE,
            OllamaGuestRuntime.startupStage(false, null, true, ""),
        )
        assertEquals(
            OllamaStartupStage.GUEST_API_WAITING,
            OllamaGuestRuntime.startupStage(true, false, false, ""),
        )
        assertEquals(
            OllamaStartupStage.ANDROID_REACHABILITY_WAITING,
            OllamaGuestRuntime.startupStage(true, true, false, ""),
        )
        assertEquals(
            OllamaStartupStage.READY,
            OllamaGuestRuntime.startupStage(true, true, true, ""),
        )
        assertEquals(
            OllamaStartupStage.PORT_CONFLICT,
            OllamaGuestRuntime.startupStage(true, false, false, "listen tcp 127.0.0.1:11434: bind: address already in use"),
        )
    }

    @Test
    fun guestStartScriptKeepsServerInForegroundAndRefusesDuplicateRecord() {
        val script = OllamaGuestRuntime.guestStartScript("/usr/bin/ollama", "nyx-abc12345")
        assertTrue(script.contains("export OLLAMA_HOST=127.0.0.1:11434"))
        assertTrue(script.contains("NYX_OLLAMA_DUPLICATE=1"))
        assertTrue(script.contains("exec \"\$ollama_bin\" serve"))
        assertTrue(script.contains("NYX_SESSION_TOKEN="))
        assertFalse(script.contains("setsid"))
        assertFalse(script.contains("--kill-on-exit"))
    }

    @Test
    fun guestScriptsRejectUnsafeTokensAndPids() {
        assertTrue(OllamaGuestRuntime.isValidToken("nyx-abc12345"))
        assertFalse(OllamaGuestRuntime.isValidToken("short"))
        assertFalse(OllamaGuestRuntime.isValidToken("nyx token; rm -rf /"))
        assertFalse(OllamaGuestRuntime.isValidToken("nyx-abc12345\nrm"))
        assertNull(
            runCatching { OllamaGuestRuntime.guestStartScript("/usr/bin/ollama", "bad token; reboot") }.getOrNull(),
        )
        assertNull(runCatching { OllamaGuestRuntime.sessionKillScript("/data/proot", 0) }.getOrNull())
        assertNull(runCatching { OllamaGuestRuntime.sessionKillScript("/data/proot", 9_999_999) }.getOrNull())
    }

    @Test
    fun sessionKillTargetsOneVerifiedPidOnly() {
        val script = OllamaGuestRuntime.sessionKillScript("/data/data/com.termux/files/usr/bin/proot-distro", 1234)
        assertTrue(script.contains("proot-distro"))
        assertTrue(script.contains(" kill 1234"))
        assertFalse(script.contains("pkill"))
        assertFalse(script.contains("killall"))
    }

    @Test
    fun guestReadinessHasALongerBoundedGraceThanHost() {
        assertTrue(OllamaReadiness.GUEST_TIMEOUT_MILLIS > OllamaReadiness.DEFAULT_TIMEOUT_MILLIS)
        assertTrue(OllamaReadiness.GUEST_STARTUP_GRACE_MILLIS > OllamaReadiness.STARTUP_GRACE_MILLIS)
        assertEquals(
            OllamaServerStatus.STARTING,
            OllamaLifecyclePolicy.statusForOwnedButNotReady(90_000L, OllamaReadiness.GUEST_STARTUP_GRACE_MILLIS),
        )
        assertEquals(
            OllamaServerStatus.ERROR,
            OllamaLifecyclePolicy.statusForOwnedButNotReady(90_000L, OllamaReadiness.STARTUP_GRACE_MILLIS),
        )
    }

    @Test
    fun guestApiProbeMarkerIsStrict() {
        assertEquals(true, OllamaGuestRuntime.parseGuestApi("NYX_GUEST_API=1"))
        assertEquals(false, OllamaGuestRuntime.parseGuestApi("NYX_GUEST_API=0"))
        assertNull(OllamaGuestRuntime.parseGuestApi("warning only"))
        assertEquals(
            "LISTEN 0 4096 127.0.0.1:11434 0.0.0.0:*",
            OllamaGuestRuntime.parseGuestListen("NYX_GUEST_LISTEN=LISTEN 0 4096 127.0.0.1:11434 0.0.0.0:*"),
        )
        assertNull(OllamaGuestRuntime.parseGuestListen("NYX_GUEST_LISTEN=none"))
        assertNull(OllamaGuestRuntime.parseGuestListen("warning only"))
    }
}

package com.nyx.ai.ollama

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OllamaPersistentOwnershipBedrockTest {
    private val token = "nyx-1234567890abcdef"
    private val session = SavedOllamaSession(token, OllamaLaunchMode.PROOT_DETACHED, 1234, 5678, "debian")
    private val record = SavedOllamaProcess(1235, 5679)
    private val owned = OllamaHostSnapshot(record, OllamaOwnership.OWNED, "/usr/bin/ollama serve")

    @Test fun checkpointMustSucceedBeforeDispatch() = runBlocking {
        val order = mutableListOf<String>()
        val result = OllamaPersistentOwnership.dispatchAfterCheckpoint(
            { order += "durable"; true }, { order += "side effect"; "acknowledged" },
        )
        assertEquals(listOf("durable", "side effect"), order)
        assertEquals("acknowledged", result)
    }

    @Test fun failedCommitNeverDispatches() = runBlocking {
        var dispatches = 0
        assertNull(OllamaPersistentOwnership.dispatchAfterCheckpoint({ false }, { ++dispatches }))
        assertEquals(0, dispatches)
    }

    @Test fun throwingCheckpointNeverDispatches() = runBlocking {
        var dispatches = 0
        val failure = runCatching {
            OllamaPersistentOwnership.dispatchAfterCheckpoint<Int>({ error("storage failed") }, { ++dispatches })
        }
        assertTrue(failure.isFailure)
        assertEquals(0, dispatches)
    }

    @Test fun interruptionAtEveryPostCommitBoundaryRetainsReplayFence() = runBlocking {
        // This is a fake durable store/dispatcher seam, not Android SharedPreferences or power loss.
        for (boundary in listOf("before dispatch", "after side effect", "after acknowledgement")) {
            val durableKeys = mutableSetOf<String>()
            var durableAlias: String? = null
            var dispatches = 0
            runCatching {
                OllamaPersistentOwnership.dispatchAfterCheckpoint(
                    checkpoint = {
                        durableKeys += "session_token:DEBIAN_PROOT:debian"
                        durableKeys += "session_mode:DEBIAN_PROOT:debian"
                        durableAlias = "debian"
                        true
                    },
                    dispatch = {
                        if (boundary == "before dispatch") throw CancellationException(boundary)
                        dispatches++
                        if (boundary == "after side effect") throw CancellationException(boundary)
                        "dispatch only"
                    },
                )
                if (boundary == "after acknowledgement") error("identity enrichment failed")
            }
            // Re-created owner reads the durable evidence, not dispatch return value or job state.
            assertEquals(listOf("debian"), OllamaPersistentOwnership.evidenceAliases(durableKeys, true, durableAlias))
            assertEquals(if (boundary == "before dispatch") 0 else 1, dispatches)
            assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", OllamaHostSnapshot(), null))
        }
    }

    @Test fun ambiguousDispatcherFailureDoesNotEraseCommittedIdentity() = runBlocking {
        val keys = mutableSetOf<String>()
        val failure = runCatching {
            OllamaPersistentOwnership.dispatchAfterCheckpoint<String>(
                { keys += "session_token:DEBIAN_PROOT:debian"; true },
                { throw IllegalStateException("dispatch may have reached the provider") },
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(listOf("debian"), OllamaPersistentOwnership.evidenceAliases(keys, false, null))
    }

    @Test fun corruptAndPartialJournalEvidenceFailsClosed() {
        assertEquals(listOf(""), OllamaPersistentOwnership.evidenceAliases(emptySet(), true, null))
        assertEquals(listOf("debian"), OllamaPersistentOwnership.evidenceAliases(setOf("session_mode:DEBIAN_PROOT:debian"), false, null))
        assertEquals(2, OllamaPersistentOwnership.evidenceAliases(setOf("session_token:DEBIAN_PROOT:other"), true, "debian").size)
        assertTrue(OllamaPersistentOwnership.evidenceAliases(setOf("session_token:TERMUX_HOST:"), false, null).isEmpty())
    }

    @Test fun guestOwnershipRequiresTokenNamespacePidAndExactCommandTogether() {
        assertTrue(OllamaPersistentOwnership.verifiesGuest(session, "debian", owned, token))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "other", owned, token))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", owned, "nyx-other12345678"))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", owned, null))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(null, "debian", owned, token))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session.copy(mode = OllamaLaunchMode.HOST_DETACHED), "debian", owned, token))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", owned.copy(record = null), token))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", owned.copy(cmdline = "bash -c ollama serve"), token))
        for (ownership in OllamaOwnership.entries.filter { it != OllamaOwnership.OWNED }) {
            assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", owned.copy(ownership = ownership), token))
        }
    }

    @Test fun apiAdoptionOrDispatchAcknowledgementCannotBecomeStopAuthority() {
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", OllamaHostSnapshot(), token))
        assertFalse(OllamaPersistentOwnership.verifiesGuest(session, "debian", owned, ""))
    }

    @Test fun unknownStopIdentityIsRefusedNotReleased() {
        val gone = "NYX_SESSION_ALIVE=0"
        assertFalse(OllamaPersistentOwnership.canReleaseSession(session.copy(sessionPid = null), "debian", true, true, gone))
        assertFalse(OllamaPersistentOwnership.canReleaseSession(session.copy(sessionStartTicks = null), "debian", true, true, gone))
        assertFalse(OllamaPersistentOwnership.canReleaseSession(session.copy(mode = OllamaLaunchMode.PROOT_FOREGROUND), "debian", true, true, gone))
        assertFalse(OllamaPersistentOwnership.canReleaseSession(session, "other", true, true, gone))
        assertFalse(OllamaPersistentOwnership.canReleaseSession(session, "debian", false, true, gone))
    }

    @Test fun blankTimeoutCancelledOrLiveSessionProbeCannotProveTermination() {
        for (output in listOf("", "timeout", "NYX_SESSION_ALIVE=1\nNYX_SESSION_START_TICKS=9999", "NYX_SESSION_STOPPED=1")) {
            assertFalse(OllamaPersistentOwnership.canReleaseSession(session, "debian", true, true, output))
        }
        assertFalse(OllamaPersistentOwnership.canReleaseSession(session, "debian", true, false, "NYX_SESSION_ALIVE=0"))
    }

    @Test fun releaseRequiresIndependentGuestExitAndSuccessfulSessionAbsence() {
        assertTrue(OllamaPersistentOwnership.canReleaseSession(session, "debian", true, true, "NYX_SESSION_ALIVE=0"))
    }

    @Test fun sessionDiscoveryRequiresUniqueNewTokenBoundCandidate() {
        val unrelated = ProotSession(1200, "debian", "login", true, "bash")
        val candidate = ProotSession(1234, "debian", "login", true, "bash $token")
        assertEquals(candidate, OllamaPersistentOwnership.newTokenSession(emptyList(), listOf(unrelated, candidate), "debian", token))
        assertNull(OllamaPersistentOwnership.newTokenSession(listOf(candidate), listOf(candidate), "debian", token))
        assertNull(OllamaPersistentOwnership.newTokenSession(emptyList(), listOf(candidate, candidate.copy(pid = 1236)), "debian", token))
        assertNull(OllamaPersistentOwnership.newTokenSession(emptyList(), listOf(unrelated), "debian", token))
        assertNull(OllamaPersistentOwnership.newTokenSession(emptyList(), listOf(candidate.copy(container = "other")), "debian", token))
        assertNull(OllamaPersistentOwnership.newTokenSession(emptyList(), listOf(candidate.copy(command = "bash ${token}extra")), "debian", token))
    }

    @Test fun sessionIdentityRequiresTokenNamespaceAndKernelStartEvidence() {
        val identity = "NYX_SESSION_ALIVE=1\nNYX_SESSION_START_TICKS=5678\nNYX_SESSION_CMDLINE=proot-distro login debian -c '$token'"
        assertTrue(OllamaPersistentOwnership.sessionMatchesToken(identity, "debian", token))
        assertFalse(OllamaPersistentOwnership.sessionMatchesToken(identity.replace(token, "foreign-token123"), "debian", token))
        assertFalse(OllamaPersistentOwnership.sessionMatchesToken(identity.replace("debian", "other"), "debian", token))
        assertFalse(OllamaPersistentOwnership.sessionMatchesToken(identity.replace("5678", ""), "debian", token))
        assertFalse(OllamaPersistentOwnership.sessionMatchesToken(identity.replace("proot-distro", "bash"), "debian", token))
    }

    @Test fun guestStopGuardsPrecedeEverySignalAndNeverUseSessionTreeKill() {
        val script = OllamaPersistentOwnership.guestStopScript(record, token)
        assertTrue(script.indexOf("guest launch token or record mismatch") < script.indexOf("kill -TERM"))
        assertTrue(script.indexOf("guest PID is not the exact server command") < script.indexOf("kill -TERM"))
        assertTrue(script.contains("live_start"))
        assertTrue(script.contains("still_start"))
        assertFalse(script.contains("proot-distro"))
        assertFalse(script.contains("pkill"))
        assertFalse(script.contains("killall"))
        assertTrue(runCatching { OllamaPersistentOwnership.guestStopScript(record, "bad token") }.isFailure)
    }

    private fun fixture(): Path {
        val root = Path.of(System.getProperty("bedrock.ollama.fixtures", Path.of(System.getProperty("java.io.tmpdir"), "nyx-bedrock-ollama-fixtures").toString()))
        Files.createDirectories(root)
        return Files.createTempDirectory(root, "owned-")
    }

    private fun pidOf(process: Process): Int = (Process::class.java.getMethod("pid").invoke(process) as Long).toInt()

    private fun shell(home: Path, script: String): Pair<Int, String> {
        val builder = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true)
        builder.environment()["HOME"] = home.toString()
        val process = builder.start()
        try {
            assertTrue("fixture command must be bounded", process.waitFor(5, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test fun mismatchedTokenNeverSignalsTestOwnedLivePid() {
        val home = fixture()
        val child = ProcessBuilder("/bin/sleep", "30").start()
        try {
            val pid = pidOf(child)
            val ticks = OllamaProcess.parseStartTimeFromStat(Files.readString(Path.of("/proc/$pid/stat")))!!
            Files.createDirectories(home.resolve(".nyx"))
            Files.writeString(home.resolve(".nyx/ollama.pid"), "NYX1 $pid $ticks 0\n")
            Files.writeString(home.resolve(".nyx/ollama.log"), "NYX_SESSION_TOKEN=foreign-token123\n")
            val result = shell(home, OllamaPersistentOwnership.guestStopScript(SavedOllamaProcess(pid, ticks), token))
            assertEquals(4, result.first)
            assertTrue(result.second.contains("NYX_OLLAMA_STOP_REFUSED=1"))
            assertTrue(child.isAlive)
        } finally { child.destroyForcibly().waitFor() }
    }

    @Test fun matchingTokenCannotAuthorizeUnrelatedCommandAtTestOwnedPid() {
        val home = fixture()
        val child = ProcessBuilder("/bin/sleep", "30").start()
        try {
            val pid = pidOf(child)
            val ticks = OllamaProcess.parseStartTimeFromStat(Files.readString(Path.of("/proc/$pid/stat")))!!
            Files.createDirectories(home.resolve(".nyx"))
            Files.writeString(home.resolve(".nyx/ollama.pid"), "NYX1 $pid $ticks 0\n")
            Files.writeString(home.resolve(".nyx/ollama.log"), "NYX_SESSION_TOKEN=$token\n")
            val result = shell(home, OllamaPersistentOwnership.guestStopScript(SavedOllamaProcess(pid, ticks), token))
            assertEquals(5, result.first)
            assertTrue(result.second.contains("NYX_OLLAMA_STOP_REFUSED=1"))
            assertTrue(child.isAlive)
        } finally { child.destroyForcibly().waitFor() }
    }

    @Test fun independentProcessProbeDoesNotEquateCommandCompletionWithExit() {
        val home = fixture()
        val child = ProcessBuilder("/bin/sleep", "30").start()
        try {
            val pid = pidOf(child)
            val ticks = OllamaProcess.parseStartTimeFromStat(Files.readString(Path.of("/proc/$pid/stat")))!!
            val record = SavedOllamaProcess(pid, ticks)
            assertTrue(shell(home, OllamaPersistentOwnership.processEndedScript(record)).second.contains("NYX_OWNED_PROCESS_ENDED=0"))
            child.destroyForcibly().waitFor()
            assertTrue(shell(home, OllamaPersistentOwnership.processEndedScript(record)).second.contains("NYX_OWNED_PROCESS_ENDED=1"))
        } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
    }
}

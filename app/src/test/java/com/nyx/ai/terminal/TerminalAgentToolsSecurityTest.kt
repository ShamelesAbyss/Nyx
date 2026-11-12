package com.nyx.ai.terminal

import com.nyx.ai.development.DevelopmentBudget
import com.nyx.ai.development.DevelopmentCheckpoint
import com.nyx.ai.development.DevelopmentOrchestrator
import com.nyx.ai.development.DevelopmentPatch
import com.nyx.ai.development.DevelopmentPaths
import com.nyx.ai.development.PatchFile
import com.nyx.ai.development.ProjectRunKind
import com.nyx.ai.development.TerminalDevelopmentEnvironment
import com.nyx.ai.development.developmentSha256
import com.nyx.ai.development.DevelopmentOrchestratorTest
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Optional Linux host fixtures execute only their deterministic trusted Makefile, never Gradle. */
class TerminalAgentToolsSecurityTest {
    private class HostExecutor : TerminalToolExecutor {
        val commands = mutableListOf<String>()
        var beforeRun: ((String) -> Unit)? = null
        override suspend fun run(command: String, environment: TerminalEnvironment, workingDirectory: String, timeoutSeconds: Long, useTimeoutBinary: Boolean): TerminalCommandResult {
            commands += command
            beforeRun?.invoke(command)
            val process = ProcessBuilder("/bin/bash", "-c", ShellCommandBuilder.wrap(command, workingDirectory, timeoutSeconds, useTimeoutBinary)).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            return TerminalCommandResult("fixture-${commands.size}", if (code == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT, code, stdout, stderr)
        }
    }
    private suspend fun fixture(block: suspend (Path, HostExecutor, TerminalAgentTools) -> Unit) {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")))
        val root = Files.createTempDirectory("nyx-terminal-fixture-")
        try { val executor = HostExecutor(); block(root, executor, TerminalAgentTools(executor, root.toString(), workspaceApproved = true)) }
        finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) } } }
    }
    @Test fun modelShellAndCommandOverridesAreDeniedWithoutIpc() = runBlocking {
        val executor = HostExecutor()
        val tools = TerminalAgentTools(executor, "/tmp/approved", workspaceApproved = true)
        for (command in listOf("rm -rf /", "git push", "cat /etc/passwd", "python3 -c 'open(\"/tmp/out\",\"w\")'", "echo x; touch /tmp/out")) {
            assertTrue(tools.execute(ToolCall("x", "terminal_run", JSONObject().put("command", command))).startsWith("Tool denied"))
            assertTrue(tools.execute(ToolCall("x", "build_run", JSONObject().put("command", command))).startsWith("Tool denied"))
        }
        assertTrue(executor.commands.isEmpty()); assertTrue(tools.drainEvidence().all { it.exitCode == null })
    }
    @Test fun rejectsUnnormalizedWorkspaceAtConstruction() {
        try { TerminalAgentTools(HostExecutor(), "~/workspace"); fail("accepted relative root") } catch (_: IllegalArgumentException) { }
        try { TerminalAgentTools(HostExecutor(), "/tmp/../outside"); fail("accepted traversal root") } catch (_: IllegalArgumentException) { }
    }
    @Test fun symlinkFilesAndParentsCannotEscapeRemoteCanonicalRoot() = runBlocking {
        fixture { root, _, tools ->
            val outside = Files.createTempDirectory("nyx-outside-")
            try {
                Files.writeString(outside.resolve("answer.py"), "secret outside")
                Files.createSymbolicLink(root.resolve("answer.py"), outside.resolve("answer.py"))
                Files.createSymbolicLink(root.resolve("linked"), outside)
                assertNull(tools.read("answer.py", 100)); assertNull(tools.read("linked/answer.py", 100))
                assertFalse(tools.exists("../answer.py")); assertNull(tools.sha256("/etc/passwd"))
                val result = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("linked/answer.py", developmentSha256("secret outside"), "changed"))))
                assertFalse(result.applied); assertEquals("secret outside", Files.readString(outside.resolve("answer.py")))
            } finally { Files.deleteIfExists(outside.resolve("answer.py")); Files.deleteIfExists(outside) }
        }
    }
    @Test fun symlinkWorkspaceItselfIsRejectedOnRemoteProbe() = runBlocking {
        fixture { root, executor, _ ->
            val alias = root.resolveSibling(root.fileName.toString() + "-alias")
            Files.createSymbolicLink(alias, root)
            try { assertFalse(TerminalAgentTools(executor, alias.toString(), workspaceApproved = true).inspectProject().ready) }
            finally { Files.deleteIfExists(alias) }
        }
    }
    @Test fun stalePatchDoesNotWriteAndChunkedAtomicWritePersistsBackup() = runBlocking {
        fixture { root, executor, tools ->
            val original = "initial source\n"
            Files.writeString(root.resolve("answer.py"), original)
            val stale = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("answer.py", "0".repeat(64), "no"))))
            assertFalse(stale.applied); assertEquals(original, Files.readString(root.resolve("answer.py")))
            val content = "# a bounded source line\n".repeat(1_100)
            val applied = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("answer.py", developmentSha256(original), content))))
            assertTrue(applied.reason, applied.applied); assertEquals(content, Files.readString(root.resolve("answer.py")))
            val backup = requireNotNull(applied.checkpoint).files.single().backupPath
            assertEquals(original, Files.readString(Path.of(requireNotNull(backup))))
            assertEquals(content, tools.read("answer.py", 128_000))
            assertTrue(executor.commands.all { ShellCommandBuilder.wrap(it, root.toString(), 180, true).length <= 8_192 })
            assertTrue(executor.commands.filter { "base64 -d >>" in it }.size > 1)
            assertFalse(executor.commands.any { "cat --" in it })
            assertTrue(tools.drainEvidence().any { it.toolName == "file_commit" && it.exitCode == 0 })
        }
    }
    @Test fun staleHashAtAtomicCommitNeverOverwritesHumanEdit() = runBlocking {
        fixture { root, executor, tools ->
            Files.writeString(root.resolve("answer.py"), "before")
            executor.beforeRun = { command -> if ("mv -f --" in command) Files.writeString(root.resolve("answer.py"), "human changed during preparation") }
            val result = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("answer.py", developmentSha256("before"), "after"))))
            assertFalse(result.applied); assertTrue(result.humanReviewRequired)
            assertEquals("human changed during preparation", Files.readString(root.resolve("answer.py")))
            assertFalse(requireNotNull(result.checkpoint).files.single().committed)
            assertTrue(tools.drainEvidence().any { it.toolName == "file_commit" && it.exitCode == 75 })
        }
    }
    @Test fun multiFilePreflightRejectsStaleSecondFileBeforeAnyWrite() = runBlocking {
        fixture { root, executor, tools ->
            Files.writeString(root.resolve("one.py"), "one")
            Files.writeString(root.resolve("two.py"), "two")
            val result = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("one.py", developmentSha256("one"), "changed"), PatchFile("two.py", "0".repeat(64), "changed"))))
            assertFalse(result.applied); assertEquals("one", Files.readString(root.resolve("one.py")))
            assertFalse(executor.commands.any { "mktemp" in it })
        }
    }
    @Test fun checkpointDirectorySymlinkIsNeverWrittenOutsideRoot() = runBlocking {
        fixture { root, _, tools ->
            val outside = Files.createTempDirectory("nyx-backup-outside-")
            try {
                Files.writeString(root.resolve("answer.py"), "before")
                Files.createSymbolicLink(root.resolve(".nyx-checkpoints"), outside)
                val result = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("answer.py", developmentSha256("before"), "after"))))
                assertFalse(result.applied); assertEquals("before", Files.readString(root.resolve("answer.py")))
                Files.list(outside).use { assertEquals(0, it.count()) }
            } finally { Files.deleteIfExists(outside) }
        }
    }
    @Test fun oversizedReadIsRejectedBeforePayloadIpc() = runBlocking {
        fixture { root, executor, tools ->
            Files.writeString(root.resolve("huge.py"), "x".repeat(200_000))
            assertNull(tools.read("huge.py", 100))
            assertFalse(executor.commands.any { "dd if=" in it || "cat --" in it })
        }
    }
    @Test fun rollbackRefusesConcurrentHumanChanges() = runBlocking {
        fixture { root, _, tools ->
            Files.writeString(root.resolve("answer.py"), "before")
            val applied = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("answer.py", developmentSha256("before"), "after"))))
            assertTrue(applied.applied)
            Files.writeString(root.resolve("answer.py"), "human changed")
            val rollback = tools.rollbackCheckpoint(requireNotNull(applied.checkpoint))
            assertFalse(rollback.applied); assertTrue(rollback.humanReviewRequired); assertEquals("human changed", Files.readString(root.resolve("answer.py")))
        }
    }
    @Test fun rollbackRestoresOnlyMatchingCheckpoint() = runBlocking {
        fixture { root, _, tools ->
            Files.writeString(root.resolve("answer.py"), "before")
            val applied = tools.applyPatch(DevelopmentPatch(listOf(PatchFile("answer.py", developmentSha256("before"), "after"))))
            assertTrue(tools.rollbackCheckpoint(requireNotNull(applied.checkpoint)).applied)
            assertEquals("before", Files.readString(root.resolve("answer.py")))
            assertFalse(tools.rollbackCheckpoint(DevelopmentCheckpoint("invented", emptyList())).applied)
        }
    }
    @Test fun trustedWorkspaceOptInIsRequiredForWritesAndProjectCode() = runBlocking {
        fixture { root, executor, _ ->
            val tools = TerminalAgentTools(executor, root.toString())
            assertFalse(tools.applyPatch(DevelopmentPatch(listOf(PatchFile("new.py", DevelopmentPaths.MISSING, "x")))).applied)
            assertFalse(tools.runProject(ProjectRunKind.BUILD).infrastructureOk)
            assertTrue(executor.commands.isEmpty())
        }
    }
    @Test fun missingJavaDiscoveryStopsBeforeRunningOrInference() = runBlocking {
        val executor = TerminalToolExecutor { command, _, _, _, _ ->
            assertFalse(command.contains("./gradlew --offline"))
            TerminalCommandResult("probe", TerminalResultKind.SUCCEEDED, 0, "MARKER:gradlew\nMARKER:build.gradle.kts\n" +
                listOf("realpath", "sha256sum", "head", "wc", "base64", "mktemp", "mv", "cp", "find", "timeout", "dd").joinToString("\n") { "TOOL:$it=/usr/bin/$it" })
        }
        val tools = TerminalAgentTools(executor, "/tmp/fixture", workspaceApproved = true)
        val provider = DevelopmentOrchestratorTest.Provider { _, _ -> error("must not infer") }
        val report = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(tools)).run("fix compiler", provider, "fixture")
        assertFalse(report.passed); assertEquals(0, provider.calls); assertTrue(report.discovery!!.missingTools.containsAll(listOf("java", "javac")))
    }
    @Test fun realHostFixtureRepairsFailingTestWithoutPaidLlm() = runBlocking {
        fixture { root, _, tools ->
            assumeTrue(Files.isExecutable(Path.of("/usr/bin/make")) && Files.isExecutable(Path.of("/usr/bin/cc")))
            Files.writeString(root.resolve("Makefile"), "all:\n\tpython3 -m py_compile answer.py\ntest:\n\tpython3 -c 'from answer import answer; assert answer() == 42, answer()'\n")
            val original = "def answer():\n    return 41\n"
            val fixed = "def answer():\n    return 42\n"
            Files.writeString(root.resolve("answer.py"), original)
            val provider = DevelopmentOrchestratorTest.Provider { _, _ -> DevelopmentOrchestratorTest.patch("answer.py", developmentSha256(original), fixed) }
            val report = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(tools)).run("repair answer", provider, "fixture", budget = DevelopmentBudget(maxElapsedMillis = 60_000))
            assertTrue(report.reason, report.passed); assertEquals(1, report.inferenceCalls)
            assertEquals(listOf(0, 2, 0, 0), report.commands.map { it.exitCode })
            assertEquals(fixed, Files.readString(root.resolve("answer.py"))); assertTrue(report.diff.contains("-    return 41")); assertTrue(report.diff.contains("+    return 42"))
            assertTrue(report.evidence.any { it.toolName == "project_run_test" && it.exitCode == 2 })
            assertTrue(report.evidence.any { it.toolName == "project_run_test" && it.exitCode == 0 })
            println("HOST_FIXTURE_EVIDENCE: commands=${report.commands.map { it.command to it.exitCode }}; iterations=${report.iterations.size}; files=${report.files}; reason=${report.reason}")
        }
    }
    @Test fun executorCancellationIsRethrown() = runBlocking {
        val tools = TerminalAgentTools(TerminalToolExecutor { _, _, _, _, _ -> throw CancellationException("cancelled IPC") }, "/tmp/fixture", workspaceApproved = true)
        try { tools.inspectProject(); fail("cancellation swallowed") } catch (expected: CancellationException) { assertEquals("cancelled IPC", expected.message) }
    }
}

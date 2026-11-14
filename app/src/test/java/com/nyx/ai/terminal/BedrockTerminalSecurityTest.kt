package com.nyx.ai.terminal

import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.development.DevelopmentPatch
import com.nyx.ai.development.PatchFile
import com.nyx.ai.development.ProjectRunKind
import com.nyx.ai.development.developmentSha256
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Comparator
import java.util.Properties

/** Real Linux processes, fake transport only. No Gradle, paid model, network, Android or real user data. */
class BedrockTerminalSecurityTest {
    private class HostExecutor : TerminalToolExecutor {
        val commands = mutableListOf<String>()
        val identities = mutableListOf<TerminalCommandIdentity>()
        var before: ((String) -> Unit)? = null
        var replace: ((String, TerminalCommandResult) -> TerminalCommandResult)? = null
        override suspend fun run(command: String, environment: TerminalEnvironment, workingDirectory: String, timeoutSeconds: Long, useTimeoutBinary: Boolean): TerminalCommandResult {
            commands += command
            identities += requireNotNull(currentCoroutineContext()[TerminalCommandDispatchScope]).identity
            before?.invoke(command)
            // Guest namespace maps the same env/bash/coreutils paths to this Linux fixture ONLY.
            val request = ControlledShell.request(command, TerminalEnvironment.DEBIAN_PROOT, workingDirectory, timeoutSeconds, useTimeoutBinary)
            val process = ProcessBuilder(listOf(request.commandPath) + request.arguments).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            val result = TerminalCommandResult("fixture", if (code == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT, code, out, err)
            return replace?.invoke(command, result) ?: result
        }
    }
    private fun gate(root: String) = ModelCommandGate { command, directory, timeout ->
        ModelCommandPolicy.assess(ModelCommandRequest(command, root, directory, timeout, TerminalEnvironment.DEBIAN_PROOT))
    }
    private suspend fun fixture(block: suspend (Path, HostExecutor, TerminalAgentTools) -> Unit) {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/env")))
        val root = Files.createTempDirectory("bedrock-terminal-regression-")
        try {
            val executor = HostExecutor()
            block(root, executor, TerminalAgentTools(executor, root.toString(), TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = true, modelCommandGate = gate(root.toString())))
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private suspend fun run(tools: TerminalAgentTools, command: String, extra: JSONObject = JSONObject()): String =
        tools.execute(ToolCall("fixture", "terminal_run", extra.put("command", command)))
    private fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun protocol(path: String, bytes: ByteArray) = "NYX_FILE_BEGIN $path ${digest(bytes)} ${bytes.size}\n${Base64.getEncoder().encodeToString(bytes)}\nNYX_FILE_END $path\n"

    @Test fun arbitraryExecutablesProgramsGitOptionsAndPathsFailBeforeIpc() = runBlocking {
        fixture { root, executor, tools ->
            val marker = root.resolve("payload-ran")
            Files.writeString(root.resolve("cat"), "#!/bin/sh\nprintf marker > '${marker}'\n")
            root.resolve("cat").toFile().setExecutable(true)
            val attacks = listOf("./cat Main.kt", "${root}/cat Main.kt", "PATH=${root} cat Main.kt", "env cat Main.kt", "busybox sh -c id", "sh -c 'printf marker'", "awk 'BEGIN { system(\"printf marker\") }'", "sed -n '1e printf marker'", "find . -exec sh -c id \\;", "git -C . push origin main", "git -C . reset --hard HEAD", "git -c alias.x='!printf marker' x", "git config --global core.fsmonitor marker", "git --git-dir=../foreign status", "cat ../outside.txt", "cat /etc/passwd", "cat .env.production", "cat .git/config", "cat credentials/plain.txt", "cat .netrc", "grep -f ../outside Main.kt", "head --bytes=100 ../outside", "cat Main.kt > output.txt", "printf '%s' '\$(id)'", "echo \"\$HOME\"", "echo unclosed'", "rm --recursive ../foreign", "npm publish", "curl https://example.invalid", "chmod 777 Main.kt", "echo ok; id")
            attacks.forEach { assertTrue(it, run(tools, it).startsWith("Tool denied")) }
            assertTrue(executor.commands.isEmpty()); assertFalse(Files.exists(marker))
        }
    }
    @Test fun permissiveGateCannotWidenLocalAuthority() = runBlocking {
        fixture { root, executor, _ ->
            val tools = TerminalAgentTools(executor, root.toString(), TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = true,
                modelCommandGate = ModelCommandGate { _, _, _ -> ModelCommandDecision(true, "fixture allow", root.toString(), risk = CommandRisk.READ_ONLY) })
            assertTrue(run(tools, "./payload").startsWith("Tool denied")); assertTrue(executor.commands.isEmpty())
        }
    }
    @Test fun validatedReadersUseAbsoluteArgvAndKeepRelativeSubdirectoryMeaning() = runBlocking {
        fixture { root, executor, tools ->
            Files.createDirectory(root.resolve("src")); Files.writeString(root.resolve("src/Main.kt"), "safe source\n")
            Files.writeString(root.resolve("Main.kt"), "wrong root\n")
            val result = JSONObject(run(tools, "cat Main.kt", JSONObject().put("working_directory", "src")))
            assertEquals("safe source\n", result.getString("stdout"))
            assertTrue(executor.commands.last().contains("'/usr/bin/head'"))
            assertEquals(listOf("-c", "16000", "--", "${root}/src/Main.kt"), executor.identities.last().arguments)
            assertEquals("\$(not executed)", JSONObject(run(tools, "printf '\$(not executed)'" )).getString("stdout"))
            assertEquals("a > b\n", JSONObject(run(tools, "echo 'a > b'")).getString("stdout"))
        }
    }
    @Test fun toolBoundaryRejectsCoercionUnknownFieldsAndIntegerOverflowWithoutIpc() = runBlocking {
        fixture { _, executor, tools ->
            val invalid = listOf(JSONObject().put("command", true), JSONObject().put("command", "ls").put("extra", "ignored"), JSONObject().put("command", "ls").put("timeout_seconds", "2"), JSONObject().put("command", "ls").put("timeout_seconds", 2.5), JSONObject().put("command", "ls").put("timeout_seconds", Long.MAX_VALUE), JSONObject().put("command", "ls").put("working_directory", JSONObject.NULL))
            invalid.forEach { assertTrue(tools.execute(ToolCall("x", "terminal_run", it)).startsWith("Tool denied")) }
            assertTrue(tools.execute(ToolCall("x", "file_read", JSONObject().put("path", 1))).startsWith("Tool denied"))
            assertTrue(tools.execute(ToolCall("x", "build_run", JSONObject().put("kind", "test"))).startsWith("Tool denied"))
            assertTrue(executor.commands.isEmpty())
        }
    }
    @Test fun symlinksHardlinksAndCredentialAncestorsCannotSupplySourceBytes() = runBlocking {
        fixture { root, _, tools ->
            val outside = Files.createTempDirectory("bedrock-terminal-outside-")
            try {
                Files.writeString(outside.resolve("private.txt"), "outside sentinel")
                Files.createSymbolicLink(root.resolve("linked.txt"), outside.resolve("private.txt"))
                Files.createSymbolicLink(root.resolve("linked"), outside)
                Files.createLink(root.resolve("alias.txt"), outside.resolve("private.txt"))
                for (path in listOf("linked.txt", "linked/private.txt", "alias.txt", "../private.txt", "credentials/plain.txt")) {
                    assertNull(path, tools.read(path, 100)); assertTrue(run(tools, "cat $path").let { it.startsWith("Tool denied") || JSONObject(it).getInt("exitCode") != 0 })
                }
                assertFalse(tools.applyPatch(DevelopmentPatch(listOf(PatchFile("alias.txt", developmentSha256("outside sentinel"), "wrong")))).applied)
                assertEquals("outside sentinel", Files.readString(outside.resolve("private.txt")))
            } finally { Files.deleteIfExists(outside.resolve("private.txt")); Files.deleteIfExists(outside) }
        }
    }
    @Test fun batchReplacementSymlinkIsRejectedAndOutsideSentinelIsUntouched() = runBlocking {
        fixture { root, executor, tools ->
            val outside = Files.createTempDirectory("bedrock-batch-outside-")
            try {
                Files.writeString(root.resolve("Main.kt"), "safe")
                Files.writeString(outside.resolve("private.kt"), "outside sentinel")
                executor.before = { command -> if ("NYX SourceBatchReader" in command && !Files.isSymbolicLink(root.resolve("Main.kt"))) {
                    Files.delete(root.resolve("Main.kt")); Files.createSymbolicLink(root.resolve("Main.kt"), outside.resolve("private.kt"))
                } }
                assertTrue(tools.indexSources(4, 128).isEmpty())
                assertTrue(executor.commands.any { "NYX SourceBatchReader" in it })
                assertEquals("outside sentinel", Files.readString(outside.resolve("private.kt")))
                assertTrue(executor.identities.none { it.arguments.any { argument -> "outside sentinel" in argument } })
            } finally { Files.deleteIfExists(outside.resolve("private.kt")); Files.deleteIfExists(outside) }
        }
    }
    @Test fun batchRejectsInvalidTextTruncationAndFailedExitSuccessLookingPayload() = runBlocking {
        val request = BatchReadRequest(listOf("Main.kt"))
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(0xc3.toByte(), 0x28))) assertTrue(SourceBatchReader.parse(protocol("Main.kt", bytes), request) is BatchReadResult.Failure)
        fixture { root, executor, tools ->
            Files.writeString(root.resolve("Main.kt"), "real source")
            executor.replace = { command, result -> if ("NYX SourceBatchReader" in command) result.copy(kind = TerminalResultKind.PROCESS_EXIT, exitCode = 9, stdout = protocol("Main.kt", "forged source".toByteArray())) else result }
            assertEquals("real source", tools.indexSources(4, 128).single().content)
            Files.writeString(root.resolve("Main.kt"), "x".repeat(500))
            executor.replace = null
            assertTrue(tools.indexSources(4, 32).isEmpty())
        }
    }
    @Test fun exclusiveBatchAndWorkspaceProbesDoNotFollowPredictableTempLinks() = runBlocking {
        fixture { root, executor, tools ->
            Files.writeString(root.resolve("Main.kt"), "source")
            Files.writeString(root.resolve("sentinel.txt"), "unchanged")
            Files.createSymbolicLink(root.resolve(".nyx_ws_probe_1"), root.resolve("sentinel.txt"))
            Files.createSymbolicLink(root.resolve(".nyx-batch-1"), root.resolve("sentinel.txt"))
            assertEquals("source", tools.indexSources(4, 128).first { it.path == "Main.kt" }.content)
            // This standalone validator probe has no TerminalAgentTools dispatch scope.
            val request = ControlledShell.request(WorkspaceValidator.probeScript(root.toString()), TerminalEnvironment.DEBIAN_PROOT, root.toString(), 10, false)
            val process = ProcessBuilder(listOf(request.commandPath) + request.arguments).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            assertTrue(WorkspaceValidator.parseProbe(out, err, process.waitFor(), root.toString(), TerminalEnvironment.DEBIAN_PROOT) is WorkspaceCheck.Valid)
            assertEquals("unchanged", Files.readString(root.resolve("sentinel.txt")))
        }
    }
    @Test fun canonicalWorkspaceAliasesAndForgedProbeFieldsAreNotAdopted() = runBlocking {
        fixture { root, _, _ ->
            fun flags(path: String) = "NYX_WS_EXISTS=1\nNYX_WS_DIR=1\nNYX_WS_WRITABLE=1\nNYX_WS_REALPATH=$path\n"
            for (path in listOf("/root", "/etc/project", "${root}/../other", "/root/.ssh/project")) assertTrue(WorkspaceValidator.parseProbe(flags(path), "", 0, root.toString()) is WorkspaceCheck.Invalid)
            assertTrue(WorkspaceValidator.parseProbe(flags(root.toString()) + "NYX_WS_REALPATH=${root}\n", "", 0, root.toString()) is WorkspaceCheck.Invalid)
            assertTrue(WorkspaceValidator.parseProbe(flags(root.toString()), "", 9, root.toString()) is WorkspaceCheck.Invalid)
            val alias = root.resolve("alias")
            Files.createSymbolicLink(alias, root)
            val process = ProcessBuilder("/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "/bin/bash", "--noprofile", "--norc", "-c", WorkspaceValidator.probeScript(alias.toString())).start()
            val out = process.inputStream.bufferedReader().readText(); val code = process.waitFor()
            assertTrue(WorkspaceValidator.parseProbe(out, "", code, alias.toString()) is WorkspaceCheck.Invalid)
        }
    }
    @Test fun workspaceCreationRejectsSymlinkParentsBeforeAnyOutsideCreation() = runBlocking {
        fixture { root, _, _ ->
            val outside = Files.createTempDirectory("bedrock-provision-outside-")
            try {
                Files.createSymbolicLink(root.resolve("parent"), outside)
                val process = ProcessBuilder("/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "/bin/bash", "--noprofile", "--norc", "-c", WorkspaceProvisioner.createScript("${root}/parent/new/project")).start()
                assertTrue(process.waitFor() != 0)
                Files.list(outside).use { assertEquals(0L, it.count()) }
            } finally { Files.deleteIfExists(outside) }
            val target = root.resolve("normal/new/project")
            val request = ControlledShell.request(WorkspaceProvisioner.createScript(target.toString()), TerminalEnvironment.DEBIAN_PROOT, root.toString(), 10, false)
            val process = ProcessBuilder(listOf(request.commandPath) + request.arguments).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            assertEquals(err, 0, process.waitFor())
            assertTrue(Files.isDirectory(target))
            assertTrue(out.contains("NYX_WS_CREATED=1"))
            assertTrue(out.contains("NYX_WS_REALPATH=$target"))
        }
    }
    @Test fun cleanControlledRequestSuppressesInheritedStartupPathAndFunctions() {
        val root = Files.createTempDirectory("bedrock-shell-environment-")
        try {
            val marker = root.resolve("startup-ran")
            Files.writeString(root.resolve("startup"), "printf marker > '${marker}'\n")
            Files.writeString(root.resolve("pwd"), "#!/bin/sh\nprintf hijacked\n")
            root.resolve("pwd").toFile().setExecutable(true)
            val request = ControlledShell.request("pwd -P", TerminalEnvironment.DEBIAN_PROOT, root.toString(), 10, true)
            val builder = ProcessBuilder(listOf(request.commandPath) + request.arguments)
            builder.environment()["BASH_ENV"] = root.resolve("startup").toString()
            builder.environment()["ENV"] = root.resolve("startup").toString()
            builder.environment()["PATH"] = root.toString()
            builder.environment()["BASH_FUNC_pwd%%"] = "() { printf hijacked; }"
            val process = builder.start(); val out = process.inputStream.bufferedReader().readText(); val code = process.waitFor()
            assertEquals(0, code); assertEquals(root.toString(), out.trim()); assertFalse(Files.exists(marker))
            assertEquals("/usr/bin/env", request.commandPath); assertEquals("-i", request.arguments.first())
            assertTrue(request.arguments.contains("--noprofile")); assertFalse(request.arguments.contains("-lc"))
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun fixedGitInspectionDoesNotExecuteConfiguredFsmonitorPagerOrAliases() = runBlocking {
        fixture { root, executor, tools ->
            assumeTrue(Files.isExecutable(Path.of("/usr/bin/git")))
            val init = ProcessBuilder("/usr/bin/git", "init", "--quiet", root.toString()).start(); assertEquals(0, init.waitFor())
            val marker = root.resolve("git-helper-ran")
            val hook = root.resolve("helper.sh"); Files.writeString(hook, "#!/bin/sh\nprintf marker > '${marker}'\n"); hook.toFile().setExecutable(true)
            Files.writeString(root.resolve(".git/config"), "[core]\n repositoryformatversion = 0\n bare = false\n fsmonitor = ${hook}\n pager = ${hook}\n[alias]\n x = !${hook}\n")
            tools.execute(ToolCall("x", "repo_status", JSONObject()))
            assertFalse(Files.exists(marker)); assertTrue(executor.commands.last().contains("core.fsmonitor=false"))
            assertTrue(run(tools, "git x").startsWith("Tool denied"))
        }
    }
    @Test fun selectedOutputRequiresApprovalTaskBindingAndStableSingleLinkBytes() = runBlocking {
        fixture { root, executor, _ ->
            Files.createDirectory(root.resolve("build")); Files.writeString(root.resolve("build/result.json"), "{\"passed\":true}\n")
            val approval = TerminalOutputApproval("objective", TerminalEnvironment.DEBIAN_PROOT, "debian", root.toString(), listOf(TerminalOutputSelection("result", "build/result.json")))
            val base = TerminalAgentTools(executor, root.toString(), TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = true, outputApproval = approval)
            val context = AgentToolTaskContext("objective", "task", ExecutionTimeoutPolicy.forProfile())
            assertNull(base.snapshotOutput("result"))
            val tools = base.forTask(context)
            val snapshot = requireNotNull(tools.snapshotOutput("result"))
            assertEquals("objective", snapshot.objectiveId); assertEquals("task", snapshot.taskId)
            assertEquals("{\"passed\":true}\n", snapshot.copyBytes().toString(Charsets.UTF_8)); assertEquals(digest(snapshot.copyBytes()), snapshot.sha256)
            val copy = snapshot.copyBytes(); copy[0] = 0; assertEquals('{'.code.toByte(), snapshot.copyBytes()[0])
            assertNull(tools.snapshotOutput("unselected"))
            assertNull(base.forTask(context.copy(objectiveId = "foreign")).snapshotOutput("result"))
            Files.writeString(root.resolve("build/result.json"), "x".repeat(128_001)); assertNull(tools.snapshotOutput("result"))
            Files.delete(root.resolve("build/result.json")); Files.createSymbolicLink(root.resolve("build/result.json"), root.resolve("outside.json")); assertNull(tools.snapshotOutput("result"))
            Files.delete(root.resolve("build/result.json"))
            Files.writeString(root.resolve("outside.json"), "{\"private\":true}")
            Files.createLink(root.resolve("build/result.json"), root.resolve("outside.json"))
            assertNull(tools.snapshotOutput("result"))
            assertEquals("{\"private\":true}", Files.readString(root.resolve("outside.json")))
            for (path in listOf("../outside.txt", "/tmp/out.txt", "Main.kt", "build/.env.json", "build/credentials/result.json", "build/app.jar")) assertFalse(path, TerminalOutputPaths.isSelectedOutput(path))
        }
    }
    @Test fun selectedOutputRejectsMidReadChangeAndMalformedTransport() = runBlocking {
        fixture { root, executor, _ ->
            Files.createDirectory(root.resolve("build")); val output = root.resolve("build/result.txt"); Files.writeString(output, "original")
            val approval = TerminalOutputApproval("o", TerminalEnvironment.DEBIAN_PROOT, "debian", root.toString(), listOf(TerminalOutputSelection("r", "build/result.txt")))
            val tools = TerminalAgentTools(executor, root.toString(), TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = true, outputApproval = approval)
                .forTask(AgentToolTaskContext("o", "t", ExecutionTimeoutPolicy.forProfile()))
            executor.before = { command -> if ("dd if=" in command) Files.writeString(output, "changed!") }
            assertNull(tools.snapshotOutput("r")); executor.before = null; Files.writeString(output, "original")
            executor.replace = { command, result -> if ("dd if=" in command) result.copy(stdout = result.stdout + "!garbage!") else result }
            assertNull(tools.snapshotOutput("r"))
        }
    }
    @Test fun commandIdentityRoundTripsAndInterruptedJournalKeepsExactValidatedArgv() {
        val root = Files.createTempDirectory("bedrock-command-journal-")
        try {
            val identity = TerminalCommandIdentity("terminal_run", "/usr/bin/head", listOf("-c", "16000", "--", "/approved/Main.kt"), "/approved", "structured_readonly", "a".repeat(64))
            val properties = Properties(); identity.writeTo(properties); assertEquals(identity, TerminalCommandIdentity.readFrom(properties))
            val hashIdentity = identity.copy(operationKind = "file_sha256")
            val hashProperties = Properties(); hashIdentity.writeTo(hashProperties)
            assertEquals(hashIdentity, TerminalCommandIdentity.readFrom(hashProperties))
            for (kind in listOf("", "9invalid", "UPPERCASE", "file/read", "file;read", "x".repeat(65))) {
                try { identity.copy(operationKind = kind); fail("accepted malformed operation kind: $kind") }
                catch (_: IllegalArgumentException) { }
            }
            // Persistence hook itself is lifecycle-owned; this test covers the supplied codec exactly.
            properties.setProperty("command.argc", "999"); try { TerminalCommandIdentity.readFrom(properties); fail("accepted corrupt argv count") } catch (_: IllegalArgumentException) { }
            assertNull(TerminalCommandIdentity.readFrom(Properties()))
            assertFalse(FixedProjectExecution("o", "t", TerminalEnvironment.DEBIAN_PROOT, "debian", "/approved", ProjectRunKind.TEST, "op", "fixed test", TerminalResultKind.UNKNOWN_RESULT, 0, 1, 2).completedSuccessfully)
            assertFalse(FixedProjectExecution("o", "t", TerminalEnvironment.DEBIAN_PROOT, "debian", "/approved", ProjectRunKind.TEST, "op", "fixed test", TerminalResultKind.PROCESS_EXIT, 124, 1, 2).infrastructureOk)
        } finally { Files.delete(root) }
    }
    @Test fun failedDeletionFlagsCannotConfirmCompletion() {
        val flags = "NYX_WS_DELETED=1\nNYX_WS_PATH=/managed/project\n"
        assertTrue(WorkspaceLifecycle.parseDelete(flags, "", 0, "/managed/project") is WorkspaceDeleteResult.Deleted)
        for (code in listOf(null, 1, 124)) assertTrue(WorkspaceLifecycle.parseDelete(flags, "", code, "/managed/project") is WorkspaceDeleteResult.Refused)
        assertTrue(WorkspaceLifecycle.parseDelete(flags + "NYX_WS_DELETED=1\n", "", 0, "/managed/project") is WorkspaceDeleteResult.Refused)
    }
}

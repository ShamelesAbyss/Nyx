package com.nyx.ai.terminal

import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.development.DevelopmentPatch
import com.nyx.ai.development.PatchFile
import com.nyx.ai.development.ProjectRunKind
import com.nyx.ai.development.developmentSha256
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Actual fixture compiler and Java assertions, NOT an Ollama or Android end-to-end test. */
class BedrockFixedDevelopmentTest {
    @Test fun existingCompilerOutputsRejectHardlinksSymlinksAndNonFilesBeforeOverwrite() = runBlocking {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/env")))
        val root = Files.createTempDirectory("bedrock-output-guard-")
        val outside = Files.createTempDirectory("bedrock-output-sentinel-")
        try {
            Files.writeString(root.resolve("Main.kt"), "fun answer() = 42\n")
            Files.writeString(root.resolve("TestMain.kt"), "fun main() { check(answer() == 42) }\n")
            Files.createDirectory(root.resolve("build"))
            val sentinel = outside.resolve("sentinel.jar")
            Files.writeString(sentinel, "outside bytes must not be overwritten")
            val marker = outside.resolve("compiler-ran")
            val compiler = outside.resolve("kotlinc")
            Files.writeString(compiler, "#!/bin/sh\nprintf compiled >> '${marker}'\n")
            assertTrue(compiler.toFile().setExecutable(true))
            val executor = TerminalToolExecutor { command, _, directory, timeout, hard ->
                val request = ControlledShell.request(command, TerminalEnvironment.DEBIAN_PROOT, directory, timeout, hard)
                val process = ProcessBuilder(listOf(request.commandPath) + request.arguments).start()
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                val code = process.waitFor()
                TerminalCommandResult("guard-fixture", if (code == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT, code, out, err)
            }
            // The compiler is a caller-trusted marker fixture, not a model override or compile proof.
            val tools = TerminalAgentTools(executor, root.toString(), TerminalEnvironment.DEBIAN_PROOT,
                workspaceApproved = true, trustedToolchainExecutables = mapOf("kotlinc" to compiler.toRealPath().toString()))
            for ((kind, filename) in listOf(ProjectRunKind.BUILD to "nyx-main.jar", ProjectRunKind.TEST to "nyx-test.jar")) {
                val output = root.resolve("build/$filename")
                Files.createLink(output, sentinel)
                assertEquals("Hardlinked $filename", 73, tools.runProject(kind).exitCode)
                assertFalse(Files.exists(marker))
                assertEquals("outside bytes must not be overwritten", Files.readString(sentinel))
                Files.delete(output)
                Files.createSymbolicLink(output, sentinel)
                assertEquals("Symlinked $filename", 73, tools.runProject(kind).exitCode)
                assertFalse(Files.exists(marker))
                Files.delete(output)
                Files.createSymbolicLink(output, outside.resolve("missing.jar"))
                assertEquals("Dangling symlink $filename", 73, tools.runProject(kind).exitCode)
                assertFalse(Files.exists(marker))
                Files.delete(output)
                Files.createDirectory(output)
                assertEquals("Non-file $filename", 73, tools.runProject(kind).exitCode)
                assertFalse(Files.exists(marker))
                Files.delete(output)
            }
            Files.writeString(root.resolve("build/nyx-main.jar"), "ordinary previous output")
            assertTrue(tools.runProject(ProjectRunKind.BUILD).passed)
            assertEquals("compiled", Files.readString(marker))
            Files.delete(marker)
            Files.delete(root.resolve("build/nyx-main.jar")); Files.delete(root.resolve("build"))
            Files.createSymbolicLink(root.resolve("build"), outside)
            assertEquals(73, tools.runProject(ProjectRunKind.BUILD).exitCode)
            assertFalse(Files.exists(marker))
            assertEquals("outside bytes must not be overwritten", Files.readString(sentinel))
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            Files.walk(outside).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun realKotlinCompilationBehavioralRepairAndApprovedSnapshotUseProductionTools() = runBlocking {
        val compiler = Path.of((System.getenv("NYX_KOTLINC") ?: System.getProperty("bedrock.kotlin.compiler") ?: "/tmp/nyx-toolchain-tests/kotlin-home/bin/kotlinc"))
        val java = Path.of("/usr/lib/jvm/java-21-openjdk-amd64/bin/java")
        assumeTrue(Files.isExecutable(compiler) && Files.isExecutable(java))
        val root = Files.createTempDirectory("bedrock-fixed-kotlin-")
        try {
            val original = "fun answer(): Int = 41\n"
            val fixed = "fun answer(): Int = 42\n"
            Files.writeString(root.resolve("Main.kt"), original)
            Files.writeString(root.resolve("TestMain.kt"), "fun main() { check(answer() == 42); check(answer() + 1 == 43); java.nio.file.Files.writeString(java.nio.file.Path.of(\"build/result.json\"), \"{\\\"passed\\\":true,\\\"assertions\\\":2,\\\"answer\\\":42}\\n\"); println(\"BEHAVIORAL_ASSERTIONS=2\") }\n")
            val executed = mutableListOf<Pair<String, Int>>()
            val executor = TerminalToolExecutor { command, _, directory, timeout, hard ->
                val request = ControlledShell.request(command, TerminalEnvironment.DEBIAN_PROOT, directory, timeout, hard)
                val process = ProcessBuilder(listOf(request.commandPath) + request.arguments).start()
                val out = process.inputStream.bufferedReader().readText(); val err = process.errorStream.bufferedReader().readText(); val code = process.waitFor()
                executed += command to code
                TerminalCommandResult("host-fixture", if (code == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT, code, out, err)
            }
            val approval = TerminalOutputApproval("fixed-objective", TerminalEnvironment.DEBIAN_PROOT, "debian", root.toString(), listOf(TerminalOutputSelection("test-result", "build/result.json")))
            val tools = TerminalAgentTools(executor, root.toString(), TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = true,
                outputApproval = approval, trustedToolchainExecutables = mapOf("kotlinc" to compiler.toRealPath().toString(), "java" to java.toRealPath().toString()))
                .forTask(AgentToolTaskContext("fixed-objective", "fixed-task", ExecutionTimeoutPolicy.forProfile()))
            assertFalse("Fixed development must not implicitly expose general model commands", "terminal_run" in tools.names)
            val discovery = tools.inspectProject(); assertTrue(discovery.reason + discovery.missingTools, discovery.ready)
            assertEquals("kotlin-cli", discovery.projectType)
            val baseline = tools.runProject(ProjectRunKind.TEST); assertFalse(baseline.passed); assertNotNull(baseline.exitCode)
            assertTrue(tools.applyPatch(DevelopmentPatch(listOf(PatchFile("Main.kt", developmentSha256(original), fixed)))).applied)
            val build = tools.runProject(ProjectRunKind.BUILD); assertTrue(build.stderr, build.passed)
            val test = tools.runProject(ProjectRunKind.TEST); assertTrue(test.stderr, test.passed)
            assertTrue(test.stdout.contains("BEHAVIORAL_ASSERTIONS=2"))
            val observations = tools.drainProjectExecutions()
            assertEquals(listOf(ProjectRunKind.TEST, ProjectRunKind.BUILD, ProjectRunKind.TEST), observations.map { it.kind })
            assertFalse(observations.first().completedSuccessfully)
            assertTrue(observations.takeLast(2).all { it.completedSuccessfully && it.objectiveId == "fixed-objective" && it.taskId == "fixed-task" })
            assertEquals(observations.size, observations.map { it.operationId }.distinct().size)
            val snapshot = requireNotNull(tools.snapshotOutput("test-result"))
            assertArrayEquals(Files.readAllBytes(root.resolve("build/result.json")), snapshot.copyBytes())
            assertEquals(developmentSha256(snapshot.copyBytes().toString(Charsets.UTF_8)), snapshot.sha256)
            assertNull(tools.read("build/result.json", 100)) // Do not relax source paths to export outputs.
            val evidence = JSONObject().put("test", "real_host_kotlin_fixed_development").put("model", "none; deterministic source patch")
                .put("android", "UNVERIFIED").put("objectiveId", snapshot.objectiveId).put("taskId", snapshot.taskId)
                .put("workspace", root.toString()).put("selectionId", snapshot.selectionId).put("outputSha256", snapshot.sha256).put("outputBytes", snapshot.sizeBytes)
                .put("behavioralResult", JSONObject(snapshot.copyBytes().toString(Charsets.UTF_8)))
                .put("observations", JSONArray().apply { observations.forEach { item -> put(JSONObject().put("operationId", item.operationId).put("kind", item.kind.name)
                    .put("resultKind", item.resultKind.name).put("exitCode", item.exitCode).put("command", item.command).put("startedAt", item.startedAt).put("finishedAt", item.finishedAt)) } })
                .put("terminalCalls", executed.size)
            System.getProperty("bedrock.terminal.evidence")?.let { destination -> Files.writeString(Path.of(destination), evidence.toString(2) + "\n") }
            println("BEDROCK_REAL_KOTLIN: $evidence")
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

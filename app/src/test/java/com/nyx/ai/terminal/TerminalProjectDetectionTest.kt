package com.nyx.ai.terminal

import com.nyx.ai.development.*
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Linux fixtures execute only standalone javac/sh programs. Gradle is never invoked. */
class TerminalProjectDetectionTest {
    private val fileTools = listOf("realpath", "sha256sum", "head", "wc", "base64", "mktemp", "mv", "cp", "mkdir", "cut", "chmod", "find", "timeout", "dd")

    private fun observed(vararg extra: String): String = (fileTools + extra).distinct().joinToString("\n") { "TOOL:$it=/usr/bin/$it" }

    private class HostExecutor : TerminalToolExecutor {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, environment: TerminalEnvironment, workingDirectory: String, timeoutSeconds: Long, useTimeoutBinary: Boolean): TerminalCommandResult {
            commands += command
            val process = ProcessBuilder("/bin/bash", "-c", ShellCommandBuilder.wrap(command, workingDirectory, timeoutSeconds, useTimeoutBinary)).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            return TerminalCommandResult("fixture", if (exit == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT, exit, out, err)
        }
    }

    private suspend fun fixture(block: suspend (Path, HostExecutor, TerminalAgentTools) -> Unit) {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")))
        val root = Files.createTempDirectory("nyx-project-detection-")
        try {
            val executor = HostExecutor()
            block(root, executor, TerminalAgentTools(executor, root.toString(), workspaceApproved = true))
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun kotlinCliDiscoveryNeedsCompilerAndRuntimeNotGradleOrSdk() = runBlocking {
        val executor = TerminalToolExecutor { _, _, _, _, _ ->
            TerminalCommandResult("probe", TerminalResultKind.SUCCEEDED, 0, "SOURCE:Main.kt\nSOURCE:TestMain.kt\nEMPTY:0\n" + observed("java", "kotlinc"))
        }
        val tools = TerminalAgentTools(executor, "/tmp/calculator", workspaceApproved = true)
        val discovery = tools.inspectProject()
        assertEquals("kotlin-cli", discovery.projectType)
        assertTrue(discovery.reason, discovery.ready)
        assertTrue(discovery.missingTools.isEmpty())
        assertTrue(discovery.commands[ProjectRunKind.BUILD]!!.contains("kotlinc' './Main.kt' -include-runtime"))
        assertTrue(discovery.commands[ProjectRunKind.TEST]!!.contains("TestMainKt"))
        assertTrue(discovery.commands.values.none { "gradle" in it || "ANDROID_HOME" in it || "sdkmanager" in it })
        assertTrue(tools.execute(ToolCall("x", "build_run", JSONObject().put("command", "anything"))).startsWith("Tool denied"))
    }

    @Test fun guestDiscoveryDoesNotAcceptInheritedTermuxCompiler() = runBlocking {
        val executor = TerminalToolExecutor { _, _, _, _, _ ->
            TerminalCommandResult("probe", TerminalResultKind.SUCCEEDED, 0, "SOURCE:Main.kt\nSOURCE:TestMain.kt\n" + observed("java") +
                "\nTOOL:kotlinc=${ExecutableClassifier.TERMUX_USR}/bin/kotlinc")
        }
        val discovery = TerminalAgentTools(executor, "/tmp/calculator", TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = true).inspectProject()
        assertFalse(discovery.ready)
        assertEquals(listOf("kotlinc"), discovery.missingTools)
    }

    @Test fun missingCliTestsAreNotInventedFromACompilationPass() = runBlocking {
        val executor = TerminalToolExecutor { _, _, _, _, _ ->
            TerminalCommandResult("probe", TerminalResultKind.SUCCEEDED, 0, "SOURCE:Main.kt\n" + observed("java", "kotlinc"))
        }
        val discovery = TerminalAgentTools(executor, "/tmp/calculator", workspaceApproved = true).inspectProject()
        assertFalse(discovery.ready)
        assertTrue(discovery.missingTools.any { "TestMain.kt" in it })
    }

    @Test fun androidRuntimeAndCommentMentionsDoNotRequireSdkForJvmGradle() = runBlocking {
        fixture { root, executor, tools ->
            Files.writeString(root.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") }\n// id(\"com.android.application\")\nval host = \"NYX Android app runtime com.android.example\"\n")
            assertEquals("jvm-gradle", tools.inspectProject().projectType)
            assertFalse(executor.commands.any { "sdk=\${ANDROID_HOME" in it })
        }
    }

    @Test fun actualAndroidPluginRequiresSdkEvidence() = runBlocking {
        fixture { root, executor, tools ->
            Files.writeString(root.resolve("build.gradle.kts"), "plugins { id(\"com.android.application\") }\n")
            assertEquals("android-gradle", tools.inspectProject().projectType)
            assertTrue(executor.commands.any { "sdk=\${ANDROID_HOME" in it })
            assertFalse(executor.commands.any { " --offline --no-daemon assemble" in it })
        }
    }

    @Test fun javaCliCompilesAndRunsBehavioralTestsWithoutAndroidSdk() = runBlocking {
        fixture { root, _, tools ->
            assumeTrue(Files.isExecutable(Path.of("/usr/bin/javac")))
            Files.writeString(root.resolve("Main.java"), "public class Main { public static int add(int a, int b) { return a + b; } }\n")
            Files.writeString(root.resolve("TestMain.java"), "public class TestMain { public static void main(String[] args) { if (Main.add(2, 3) != 5) throw new AssertionError(); } }\n")
            assertEquals("java-cli", tools.inspectProject().projectType)
            assertTrue(tools.runProject(ProjectRunKind.BUILD).passed)
            assertTrue(tools.runProject(ProjectRunKind.TEST).passed)
            Files.writeString(root.resolve("TestMain.java"), "public class TestMain { public static void main(String[] args) { throw new AssertionError(\"deliberate\"); } }\n")
            assertFalse(tools.runProject(ProjectRunKind.TEST).passed)
        }
    }

    @Test fun generatedOutputSymlinkIsRejectedBeforeCompilation() = runBlocking {
        fixture { root, _, tools ->
            assumeTrue(Files.isExecutable(Path.of("/usr/bin/javac")))
            val outside = Files.createTempDirectory("nyx-project-outside-")
            try {
                Files.writeString(root.resolve("Main.java"), "public class Main {}\n")
                Files.writeString(root.resolve("TestMain.java"), "public class TestMain { public static void main(String[] args) {} }\n")
                Files.createSymbolicLink(root.resolve("build"), outside)
                val build = tools.runProject(ProjectRunKind.BUILD)
                assertEquals(73, build.exitCode)
                Files.list(outside).use { assertEquals(0L, it.count()) }
            } finally { Files.deleteIfExists(outside) }
        }
    }

    private fun creationReply(vararg files: Pair<String, String>): ChatResult = ChatResult(JSONObject().put("files", JSONArray().apply {
        files.forEach { (path, content) -> put(JSONObject().put("path", path).put("expectedSha256", DevelopmentPaths.MISSING).put("content", content)) }
    }).toString())

    @Test fun approvedEmptyWorkspaceCreatesCheckpointedShellProjectBeforeAnyBuild() = runBlocking {
        fixture { root, _, tools ->
            val initial = tools.inspectProject()
            assertEquals("empty", initial.projectType)
            assertFalse(initial.ready)
            assertTrue(initial.canCreateProject)
            assertTrue(initial.commands.isEmpty())
            val provider = DevelopmentOrchestratorTest.Provider { _, messages ->
                assertTrue(messages.last().content.contains("No existing build system"))
                creationReply("main.sh" to "answer() { printf '5'; }\n", "test.sh" to ". ./main.sh\n[ \"\$(answer)\" = 5 ]\n")
            }
            val report = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(tools)).run("Create a POSIX shell calculator", provider, "fixture", budget = DevelopmentBudget(maxElapsedMillis = 60_000))
            assertTrue(report.reason, report.passed)
            assertEquals(DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED, report.outcome)
            assertEquals("project_creation_no_baseline", report.iterations.first().reason)
            assertTrue(report.iterations.first().commands.isEmpty())
            assertEquals(listOf(0, 0), report.commands.map { it.exitCode })
            assertEquals(1, report.checkpoints.size)
            assertTrue(report.checkpoints.single().files.all { it.before == null && it.committed })
            assertEquals("shell", report.discovery!!.projectType)
            assertTrue(Files.exists(root.resolve("main.sh")))
        }
    }

    @Test fun approvedEmptyWorkspaceCreatesAndVerifiesKotlinCalculatorWhenCompilerIsAvailable() = runBlocking {
        assumeTrue(ProcessBuilder("/bin/sh", "-c", "command -v kotlinc >/dev/null 2>&1").start().waitFor() == 0)
        fixture { root, executor, tools ->
            val provider = DevelopmentOrchestratorTest.Provider { _, _ -> creationReply(
                "Main.kt" to "fun add(a: Int, b: Int): Int = a + b\nfun main() { println(add(2, 3)) }\n",
                "TestMain.kt" to "fun main() { check(add(2, 3) == 5); check(add(-2, 2) == 0) }\n",
            ) }
            val report = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(tools)).run(
                "Create a Kotlin command-line calculator in NYX Android app; do not use Gradle or Android SDK.", provider, "fixture",
                budget = DevelopmentBudget(maxElapsedMillis = 120_000),
            )
            assertTrue(report.reason + report.commands.joinToString("\n") { "${it.kind}: ${it.exitCode}\n${it.stdout}\n${it.stderr}" }, report.passed)
            assertEquals(listOf(0, 0), report.commands.map { it.exitCode })
            assertEquals("kotlin-cli", report.discovery!!.projectType)
            assertTrue(Files.isRegularFile(root.resolve("build/nyx-main.jar")))
            assertTrue(report.checkpoints.single().files.all { it.before == null && it.committed })
            assertFalse(executor.commands.any { "sdk=\${ANDROID_HOME" in it || " --offline --no-daemon assemble" in it })
            Files.writeString(root.resolve("TestMain.kt"), "fun main() { check(add(2, 3) == 999) }\n")
            assertFalse(tools.runProject(ProjectRunKind.TEST).passed)
        }
    }

    @Test fun creationDoesNotReportSuccessWhenTheNewToolchainIsMissing() = runBlocking {
        fixture { _, _, tools ->
            val provider = DevelopmentOrchestratorTest.Provider { _, _ -> creationReply("build.gradle.kts" to "plugins { id(\"not-installed-plugin\") }\n") }
            val report = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(tools)).run("Create a new Kotlin feature", provider, "fixture", budget = DevelopmentBudget(maxElapsedMillis = 60_000))
            assertFalse(report.passed)
            assertTrue(report.humanReviewRequired)
            assertTrue(report.reason.startsWith("environment_unavailable_after_creation"))
            assertTrue(report.commands.isEmpty())
            assertEquals(1, report.checkpoints.size)
        }
    }

    @Test fun emptyWorkspaceRepairAndUnapprovedCreationDoNotReachInference() = runBlocking {
        fixture { root, executor, tools ->
            val provider = DevelopmentOrchestratorTest.Provider { _, _ -> error("must not infer") }
            val repair = DevelopmentOrchestrator(TerminalDevelopmentEnvironment(tools)).run("Fix the compiler error", provider, "fixture")
            assertFalse(repair.passed)
            assertEquals(0, provider.calls)
            assertFalse(TerminalAgentTools(executor, root.toString()).inspectProject().canCreateProject)
        }
    }

    @Test fun nonemptyUnknownWorkspaceIsNotSilentlyReinitialized() = runBlocking {
        fixture { root, _, tools ->
            Files.writeString(root.resolve("notes.txt"), "existing user work\n")
            val discovery = tools.inspectProject()
            assertEquals("unknown", discovery.projectType)
            assertFalse(discovery.canCreateProject)
        }
    }
}

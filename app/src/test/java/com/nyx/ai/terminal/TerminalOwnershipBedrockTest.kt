package com.nyx.ai.terminal

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Linux process evidence only. No Android IPC, real Ollama, models, or user workspaces. */
class TerminalOwnershipBedrockTest {
    private fun context(root: File, task: String = UUID.randomUUID().toString()) =
        TerminalOperationContext(objectiveId = "bedrock-host", taskId = task, workspace = root.absolutePath, readOnly = false)
    private fun awaitFile(file: File) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!file.isFile && System.nanoTime() < limit) Thread.sleep(10)
        assertTrue("Expected fixture marker ${file.name}", file.isFile)
    }

    @Test fun timeoutLikeCallbacksRetainExactOutputsAndBlockConflictingAdmission() {
        val root = Files.createTempDirectory("bedrock-timeout-callback").toFile()
        try {
            for (code in listOf(124, 137)) {
                val registry = TerminalOperationRegistry(TerminalOperationJournal(File(root, "journal-$code")))
                val a = context(root); registry.enqueue(a); assertTrue(registry.tryStart(a.operationId, 3))
                registry.finish(a.operationId, "PROCESS_EXIT", code, "owned-output", "owned-error", "wrapper exit", false)
                assertEquals(TerminalOperationState.UNCERTAIN, registry.snapshot().single().state)
                assertEquals(code, registry.snapshot().single().exitCode)
                assertEquals("owned-output", registry.snapshot().single().stdout)
                val conflict = context(root); registry.enqueue(conflict)
                assertFalse(registry.tryStart(conflict.operationId, 3))
                assertFalse(registry.acknowledgeReviewed(a.operationId, false))
            }
            assertTrue(TerminalOwnershipPolicy.requiresReservation("UNKNOWN_RESULT", 0))
            assertTrue(TerminalOwnershipPolicy.requiresReservation("TERMUX_INFRASTRUCTURE_ERROR", 0))
            assertFalse(TerminalOwnershipPolicy.requiresReservation("PROCESS_EXIT", 7))
        } finally { root.deleteRecursively() }
    }

    @Test fun threeLiveWorkersHaveIsolatedCancellationOutputExitsAndNestedLocking() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = Files.createTempDirectory("bedrock-three-workers").toFile()
        val journal = TerminalOperationJournal(File(root, "journal")); val registry = TerminalOperationRegistry(journal)
        val processes = mutableListOf<Process>()
        try {
            val workers = List(3) { File(root, "worker-$it").apply { mkdir() } }
            val contexts = workers.mapIndexed { i, dir -> context(dir, "task-$i") }
            contexts.forEachIndexed { i, operation ->
                registry.enqueue(operation); assertTrue(registry.tryStart(operation.operationId, 3))
                val code = if (i == 1) 7 else 0
                processes += ProcessBuilder("/bin/sh", "-c",
                    "printf ready > ready; while [ ! -f go ]; do sleep 0.02; done; printf 'stdout-$i'; printf 'stderr-$i' >&2; exit $code")
                    .directory(workers[i]).start()
            }
            workers.forEach { awaitFile(File(it, "ready")) }
            assertEquals(3, processes.count { it.isAlive })
            registry.finish(contexts[0].operationId, "CANCELLED", null, "", "", "Cancelled result wait only", true)
            assertTrue("Cancellation never signals this or sibling processes", processes.all { it.isAlive })
            val nested = context(File(workers[0], "nested")); registry.enqueue(nested)
            assertFalse(registry.tryStart(nested.operationId, 8))
            val independent = context(File(root, "independent")); registry.enqueue(independent)
            assertTrue(registry.tryStart(independent.operationId, 8))
            registry.finish(independent.operationId, "SUCCEEDED", 0, "other", "", null, false)
            workers.forEach { File(it, "go").writeText("go") }
            processes.forEachIndexed { i, process ->
                assertTrue(process.waitFor(8, TimeUnit.SECONDS))
                assertEquals(if (i == 1) 7 else 0, process.exitValue())
                val stdout = process.inputStream.bufferedReader().readText(); val stderr = process.errorStream.bufferedReader().readText()
                assertEquals("stdout-$i", stdout); assertEquals("stderr-$i", stderr)
                // A cancelled callback may never return. Keep op0 uncertain rather than replacing it.
                if (i != 0) registry.finish(contexts[i].operationId, if (i == 1) "PROCESS_EXIT" else "SUCCEEDED",
                    process.exitValue(), stdout, stderr, null, false)
            }
            val evidence = journal.load().associateBy { it.context.operationId }
            assertEquals("stdout-1", evidence.getValue(contexts[1].operationId).stdout)
            assertEquals("stderr-2", evidence.getValue(contexts[2].operationId).stderr)
            assertEquals("task-1", evidence.getValue(contexts[1].operationId).context.taskId)
            val restarted = TerminalOperationRegistry(journal)
            assertEquals(TerminalOperationState.UNCERTAIN, restarted.snapshot().single().state)
            assertFalse(restarted.tryStart(contexts[0].operationId, 8))
            println("BEDROCK three live host workers; isolated exits [0,7,0]; op0 remains UNCERTAIN after cancelled wait")
        } finally { processes.forEach { if (it.isAlive) it.destroyForcibly() }; root.deleteRecursively() }
    }

    @Test fun realTimeoutWrapperCanExitWhileSessionEscapingChildSurvives() {
        assumeTrue(File("/usr/bin/timeout").canExecute() && File("/usr/bin/setsid").canExecute())
        val root = Files.createTempDirectory("bedrock-escaped-child").toFile()
        val registry = TerminalOperationRegistry(TerminalOperationJournal(File(root, "journal")))
        var wrapper: Process? = null; var child: ProcessHandle? = null
        try {
            val operation = context(root); registry.enqueue(operation); assertTrue(registry.tryStart(operation.operationId, 3))
            wrapper = ProcessBuilder("/usr/bin/timeout", "-k", "1", "2", "/bin/sh", "-c",
                "/usr/bin/setsid /bin/sh -c 'printf \"%s\" \"$$\" > child.pid; while [ ! -f child.go ]; do sleep 0.02; done; printf survived > child.survived' </dev/null >child.stdout 2>child.stderr & while :; do sleep 0.05; done")
                .directory(root).start()
            awaitFile(File(root, "child.pid"))
            child = ProcessHandle.of(File(root, "child.pid").readText().toLong()).orElseThrow()
            assertTrue(wrapper.waitFor(8, TimeUnit.SECONDS)); assertEquals(124, wrapper.exitValue())
            assertTrue("Session-escaping test-owned child survives wrapper timeout", child.isAlive)
            registry.finish(operation.operationId, "PROCESS_EXIT", wrapper.exitValue(), "", "", "Observed timeout wrapper only", false)
            val conflict = context(root); registry.enqueue(conflict); assertFalse(registry.tryStart(conflict.operationId, 3))
            File(root, "child.go").writeText("go"); awaitFile(File(root, "child.survived"))
            assertEquals("survived", File(root, "child.survived").readText())
            println("BEDROCK timeout wrapper exit=124; escaped host child independently wrote survivor marker; conflict blocked")
        } finally {
            if (wrapper?.isAlive == true) wrapper.destroyForcibly()
            if (child?.isAlive == true) child.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test fun forciblyKilledHostOwnerReloadsRunningJournalAsUncertainWithoutReplay() {
        assumeTrue(File("/usr/bin/setsid").canExecute())
        val root = Files.createTempDirectory("bedrock-owner-death").toFile()
        val operation = context(root)
        var owner: Process? = null; var child: ProcessHandle? = null
        try {
            owner = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath, "-cp",
                System.getProperty("java.class.path"), "com.nyx.ai.terminal.BedrockJournalWriter", root.absolutePath, operation.operationId)
                .redirectOutput(File(root, "owner.stdout")).redirectError(File(root, "owner.stderr")).start()
            awaitFile(File(root, "owner.ready")); awaitFile(File(root, "child.pid"))
            child = ProcessHandle.of(File(root, "child.pid").readText().toLong()).orElseThrow()
            owner.destroyForcibly(); assertTrue(owner.waitFor(8, TimeUnit.SECONDS)); assertTrue(child.isAlive)
            val registry = TerminalOperationRegistry(TerminalOperationJournal(File(root, "journal")))
            val recovered = registry.snapshot().single()
            assertEquals(operation.operationId, recovered.context.operationId)
            assertEquals(TerminalOperationState.UNCERTAIN, recovered.state)
            val conflict = context(root); registry.enqueue(conflict); assertFalse(registry.tryStart(conflict.operationId, 3))
            File(root, "child.go").writeText("go"); awaitFile(File(root, "child.survived"))
            println("BEDROCK forcibly killed owner JVM; external host child survived; persisted RUNNING -> UNCERTAIN, no replay")
        } finally {
            if (owner?.isAlive == true) owner.destroyForcibly()
            if (child?.isAlive == true) child.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test fun corruptJournalAndAmbiguousPathCannotReleaseOwnership() {
        val root = Files.createTempDirectory("bedrock-journal-corrupt").toFile()
        try {
            File(root, "${UUID.randomUUID()}.properties").writeText("state=RUNNING\n")
            try { TerminalOperationRegistry(TerminalOperationJournal(root)); fail("Corruption must fail closed") } catch (_: Exception) { }
            val a = context(root).copy(workspace = "/work//project")
            val b = context(root).copy(workspace = "/work/project")
            assertTrue(TerminalOperationRegistry.conflicts(a, b))
            val notDirectory = File(root, "file").apply { writeText("not directory") }
            try { TerminalOperationJournal(notDirectory).load(); fail("Non-directory journal must fail closed") } catch (_: IllegalStateException) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun predispatchCommandDigestSurvivesOwnerRestartWithoutPayloadPersistence() {
        val root = Files.createTempDirectory("bedrock-digest-journal").toFile()
        try {
            val journal = TerminalOperationJournal(root); val registry = TerminalOperationRegistry(journal)
            val operation = context(root).copy(commandPath = "/usr/bin/env", argumentDigest = "a".repeat(64))
            registry.enqueue(operation); assertTrue(registry.tryStart(operation.operationId, 3))
            val recovered = TerminalOperationRegistry(journal).snapshot().single()
            assertEquals(operation.commandPath, recovered.context.commandPath)
            assertEquals(operation.argumentDigest, recovered.context.argumentDigest)
            assertEquals(operation.taskId, recovered.context.taskId)
        } finally { root.deleteRecursively() }
    }
}

/** Test-owned JVM writer killed by the fixture after write-ahead RUNNING checkpoint. */
object BedrockJournalWriter {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]); val registry = TerminalOperationRegistry(TerminalOperationJournal(File(root, "journal")))
        val operation = TerminalOperationContext(operationId = args[1], objectiveId = "bedrock-host", taskId = "killed-owner", workspace = root.absolutePath)
        registry.enqueue(operation); check(registry.tryStart(operation.operationId, 3))
        ProcessBuilder("/usr/bin/setsid", "/bin/sh", "-c",
            "printf '%s' \"$$\" > child.pid; while [ ! -f child.go ]; do sleep 0.02; done; printf survived > child.survived")
            .directory(root).redirectInput(File("/dev/null")).redirectOutput(File(root, "child.stdout")).redirectError(File(root, "child.stderr")).start()
        File(root, "owner.ready").writeText("dispatched")
        while (true) Thread.sleep(1000)
    }
}

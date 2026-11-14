package com.nyx.ai.terminal

import com.nyx.ai.autonomy.DeviceResources
import com.nyx.ai.autonomy.ResourcePolicy
import com.nyx.ai.autonomy.TaskGraphScheduler
import com.nyx.ai.autonomy.TaskSnapshot
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TerminalOperationRegistryTest {
    private fun context(workspace: String = "/work", readOnly: Boolean = true) =
        TerminalOperationContext(objectiveId = "objective", taskId = UUID.randomUUID().toString(), workspace = workspace, readOnly = readOnly)
    private fun complete(registry: TerminalOperationRegistry, id: String, code: Int = 0) =
        registry.finish(id, if (code == 0) "SUCCEEDED" else "PROCESS_EXIT", code, "", "", null, false)

    @Test fun composedOrProgrammableCommandsNeverGetAutomaticReadSharing() {
        assertTrue(TerminalConcurrencyPolicy.canShareWorkspace("printf 'hello'"))
        assertTrue(TerminalConcurrencyPolicy.canShareWorkspace("cat source.kt"))
        for (command in listOf("echo ok; touch file", "echo \$(touch file)", "echo x > file", "sed 'e touch file' source", "awk 'BEGIN {system(\"touch file\")}'", "/workspace/ls")) {
            assertFalse(command, TerminalConcurrencyPolicy.canShareWorkspace(command))
        }
        assertTrue(TerminalOperationRegistry.conflicts(context("/a", false), context("/b").copy(namespace = "proot:debian")))
    }

    @Test fun completedDurableIdsCannotBeReusedAndRunningCrashRecoversUncertain() {
        val directory = Files.createTempDirectory("nyx-operation-identity").toFile()
        try {
            val journal = TerminalOperationJournal(directory)
            val registry = TerminalOperationRegistry(journal)
            val done = context()
            registry.enqueue(done); assertTrue(registry.tryStart(done.operationId, 3)); complete(registry, done.operationId)
            try { registry.enqueue(done); fail("Duplicate durable operation ID must be rejected") } catch (_: IllegalArgumentException) { }
            val crash = context("/other", false)
            registry.enqueue(crash); assertTrue(registry.tryStart(crash.operationId, 3))
            val recovered = TerminalOperationRegistry(journal)
            assertEquals(TerminalOperationState.UNCERTAIN, recovered.snapshot().single().state)
            assertEquals(2, recovered.history().size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun realLinuxCancelledWaitNeverKillsItsProcessOrUnrelatedJobAndVerifiedExitRecovers() {
        assumeTrue(java.io.File("/bin/sh").canExecute())
        val directory = Files.createTempDirectory("nyx-process-cancellation").toFile()
        val registry = TerminalOperationRegistry(TerminalOperationJournal(directory))
        val operation = context(readOnly = false)
        val owned = mutableListOf<Process>()
        try {
            registry.enqueue(operation); assertTrue(registry.tryStart(operation.operationId, 3))
            repeat(2) { owned += ProcessBuilder("/bin/sh", "-c", "while [ ! -f gate ]; do sleep 0.02; done; printf 'finished'").directory(directory).start() }
            registry.finish(operation.operationId, "CANCELLED", null, "", "", "Waiting cancelled, process ownership uncertain", true)
            assertTrue(owned.all { it.isAlive })
            val conflicting = context()
            registry.enqueue(conflicting)
            assertFalse(registry.tryStart(conflicting.operationId, 3))
            java.io.File(directory, "gate").writeText("go")
            owned.forEach { assertTrue(it.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, it.exitValue()) }
            registry.finish(operation.operationId, "SUCCEEDED", owned[0].exitValue(), owned[0].inputStream.bufferedReader().readText(), "", null, false)
            assertTrue(registry.tryStart(conflicting.operationId, 3))
        } finally {
            owned.forEach { if (it.isAlive) it.destroyForcibly() }
            directory.deleteRecursively()
        }
    }

    @Test fun boundedCapacityAndWorkspaceConflictsAreShared() {
        val registry = TerminalOperationRegistry()
        val reads = List(3) { context() }
        reads.forEach { registry.enqueue(it); assertTrue(registry.tryStart(it.operationId, 3)) }
        val fourth = context("/other")
        registry.enqueue(fourth)
        assertFalse(registry.tryStart(fourth.operationId, 3))
        complete(registry, reads[0].operationId)
        assertTrue(registry.tryStart(fourth.operationId, 3))
        val write = context("/work/sub", false)
        registry.enqueue(write)
        assertFalse(registry.tryStart(write.operationId, 8))
        reads.drop(1).forEach { complete(registry, it.operationId) }
        assertTrue(registry.tryStart(write.operationId, 8))
        assertFalse(TerminalOperationRegistry.conflicts(context("/work"), context("/work2", false)))
        assertTrue(TerminalOperationRegistry.conflicts(context("/work", false), context("/work/sub")))
        assertTrue(TerminalOperationRegistry.conflicts(context().copy(workspace = null), context("/other")))
    }

    @Test fun conflictingQueuedWriterDoesNotStarveAndDependenciesRemainOrdered() {
        val registry = TerminalOperationRegistry()
        val first = context()
        val writer = context(readOnly = false)
        val laterRead = context()
        listOf(first, writer, laterRead).forEach(registry::enqueue)
        assertTrue(registry.tryStart(first.operationId, 3))
        assertFalse(registry.tryStart(writer.operationId, 3))
        assertFalse(registry.tryStart(laterRead.operationId, 3))
        complete(registry, first.operationId)
        assertTrue(registry.tryStart(writer.operationId, 3))
        val tasks = listOf(TaskSnapshot("child", 3, setOf("parent"), "pending"),
            TaskSnapshot("parent", 0, emptySet(), "pending"), TaskSnapshot("b", 1, emptySet(), "pending"),
            TaskSnapshot("a", 1, emptySet(), "pending"))
        assertEquals(listOf("parent", "a", "b"), TaskGraphScheduler.ready(tasks, 3).map { it.id })
        assertEquals(listOf("a", "b", "child"), TaskGraphScheduler.ready(tasks.map {
            if (it.id == "parent") it.copy(status = "completed") else it
        }, 3).map { it.id })
    }

    @Test fun uncertainCancellationAndRestartDoNotReleaseOrReplayRunningWork() {
        val directory = Files.createTempDirectory("nyx-terminal-evidence").toFile()
        try {
            val journal = TerminalOperationJournal(directory)
            val registry = TerminalOperationRegistry(journal)
            val running = context(readOnly = false)
            val neverStarted = context("/different")
            registry.enqueue(running); registry.enqueue(neverStarted)
            assertTrue(registry.tryStart(running.operationId, 3))
            registry.finish(running.operationId, "CANCELLED", null, "out", "err", "May still run", true)
            val recovered = TerminalOperationRegistry(journal)
            assertEquals(1, recovered.snapshot().size)
            assertEquals(TerminalOperationState.UNCERTAIN, recovered.snapshot().single().state)
            val next = context()
            recovered.enqueue(next)
            assertFalse(recovered.tryStart(next.operationId, 3))
            assertFalse(recovered.acknowledgeReviewed(running.operationId, false))
            assertTrue(recovered.acknowledgeReviewed(running.operationId, true))
            assertTrue(recovered.tryStart(next.operationId, 3))
            assertEquals("out", journal.load().first { it.context.operationId == running.operationId }.stdout)
            assertEquals(TerminalOperationState.FINISHED, journal.load().first { it.context.operationId == neverStarted.operationId }.state)
        } finally { directory.deleteRecursively() }
    }

    @Test fun pressurePreventsNewAdmissionAndRecoveryAllowsProgressWithoutKillingExistingWork() {
        val resources = DeviceResources(8192, 4096, false, 0, 80, false, 1000)
        assertEquals(3, ResourcePolicy.terminalCapacity(resources))
        assertEquals(8, ResourcePolicy.terminalCapacity(resources, 8))
        assertEquals(2, ResourcePolicy.terminalCapacity(resources, 8, 2))
        assertEquals(1, ResourcePolicy.terminalCapacity(resources.copy(lowMemory = true), 8))
        assertEquals(1, ResourcePolicy.terminalCapacity(resources.copy(thermalStatus = 3), 8))
        assertEquals(1, ResourcePolicy.terminalCapacity(resources.copy(batteryPercent = 10), 8))
        assertEquals(0, ResourcePolicy.terminalCapacity(resources.copy(availableStorageMb = 63)))
        val registry = TerminalOperationRegistry()
        val jobs = List(3) { context("/job$it") }
        jobs.forEach(registry::enqueue)
        assertTrue(registry.tryStart(jobs[0].operationId, 3))
        assertFalse(registry.tryStart(jobs[1].operationId, 1))
        assertFalse(registry.tryStart(jobs[2].operationId, 0))
        assertEquals(TerminalOperationState.RUNNING, registry.snapshot().first().state)
        assertTrue(registry.tryStart(jobs[1].operationId, 3))
        assertTrue(registry.tryStart(jobs[2].operationId, 3))
    }

    /** Real Linux processes, not Android RUN_COMMAND instrumentation. Own handles only in cleanup. */
    @Test fun threeRealLinuxCommandsHaveIndependentOutputExitAndFailure() {
        assumeTrue(java.io.File("/bin/sh").canExecute())
        val directory = Files.createTempDirectory("nyx-three-commands").toFile()
        val registry = TerminalOperationRegistry(TerminalOperationJournal(directory))
        val contexts = List(3) { context("/job$it") }
        val started = CountDownLatch(3)
        val pool = Executors.newFixedThreadPool(3)
        val processes = java.util.Collections.synchronizedList(mutableListOf<Process>())
        try {
            contexts.forEach(registry::enqueue)
            val futures = contexts.mapIndexed { index, operation -> pool.submit<Pair<String, Int>> {
                check(registry.tryStart(operation.operationId, 3))
                val exit = if (index == 1) 7 else 0
                val process = ProcessBuilder("/bin/sh", "-c", "while [ ! -f gate ]; do sleep 0.02; done; printf 'stdout-$index'; printf 'stderr-$index' >&2; exit $exit")
                    .directory(directory).start()
                processes += process
                started.countDown()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                check(process.waitFor(5, TimeUnit.SECONDS))
                val code = process.exitValue()
                registry.finish(operation.operationId, if (code == 0) "SUCCEEDED" else "PROCESS_EXIT", code, stdout, stderr, null, false)
                stdout to code
            } }
            assertTrue("All three processes must be live concurrently", started.await(5, TimeUnit.SECONDS))
            assertEquals(3, processes.count { it.isAlive })
            assertEquals(3, registry.snapshot().count { it.state == TerminalOperationState.RUNNING })
            java.io.File(directory, "gate").writeText("go")
            assertEquals(listOf(0, 7, 0), futures.map { it.get(10, TimeUnit.SECONDS).second })
            val evidence = TerminalOperationJournal(directory).load().associateBy { it.context.operationId }
            contexts.forEachIndexed { index, context ->
                assertEquals("stdout-$index", evidence.getValue(context.operationId).stdout)
                assertEquals("stderr-$index", evidence.getValue(context.operationId).stderr)
                assertEquals(context.taskId, evidence.getValue(context.operationId).context.taskId)
            }
            assertTrue(registry.snapshot().isEmpty())
        } finally {
            processes.forEach { if (it.isAlive) it.destroyForcibly() }
            pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }
}

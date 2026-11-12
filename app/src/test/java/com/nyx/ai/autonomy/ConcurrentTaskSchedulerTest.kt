package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentTaskSchedulerTest {
    @Test fun dependenciesGateTasksUntilEveryPrerequisiteCompletes() {
        val tasks = listOf(
            ScheduledTask("child", 2, dependsOn = setOf("done", "working")),
            ScheduledTask("working", 1, status = "running"),
            ScheduledTask("done", 0, status = "completed"),
        )
        val waiting = ConcurrentTaskScheduler.decide(tasks, SchedulingPolicy())
        assertTrue(waiting.start.isEmpty())
        assertTrue(waiting.blocked.isEmpty())
        assertFalse(waiting.deadlocked)

        val ready = ConcurrentTaskScheduler.decide(
            tasks.map { if (it.id == "working") it.copy(status = "completed") else it },
            SchedulingPolicy(),
        )
        assertEquals(listOf("child"), ready.start.map { it.id })
        assertFalse(ready.deadlocked)
    }

    @Test fun readyTasksAreOrderedByPriorityThenOrdinal() {
        val tasks = listOf(
            ScheduledTask("later", 0, priority = 4, workerKind = WorkerKind.TERMINAL),
            ScheduledTask("low", 1, priority = -1, workerKind = WorkerKind.FILESYSTEM),
            ScheduledTask("high-early", 8, priority = 9, workerKind = WorkerKind.VERIFICATION),
            ScheduledTask("high-first", 3, priority = 9, workerKind = WorkerKind.TERMINAL),
        )
        val result = ConcurrentTaskScheduler.decide(
            tasks,
            SchedulingPolicy(maxInference = 3, maxTerminal = 3, maxFilesystem = 3, maxVerification = 3, maxDevelopment = 3, maxTotal = 4),
        )
        assertEquals(listOf("high-first", "high-early", "later", "low"), result.start.map { it.id })
    }

    @Test fun eachWorkerKindHonorsItsOwnCapacityAndCountsActiveKinds() {
        val tasks = listOf(
            ScheduledTask("running-inference", 0, workerKind = WorkerKind.INFERENCE, status = "running"),
            ScheduledTask("verifying-terminal", 1, workerKind = WorkerKind.TERMINAL, status = "verifying"),
            ScheduledTask("inference-waiter", 2, workerKind = WorkerKind.INFERENCE),
            ScheduledTask("terminal-one", 3, workerKind = WorkerKind.TERMINAL),
            ScheduledTask("terminal-two", 4, workerKind = WorkerKind.TERMINAL),
            ScheduledTask("filesystem", 5, workerKind = WorkerKind.FILESYSTEM),
        )
        val result = ConcurrentTaskScheduler.decide(
            tasks,
            SchedulingPolicy(maxInference = 1, maxTerminal = 2, maxFilesystem = 1, maxVerification = 1, maxDevelopment = 1, maxTotal = 5),
        )
        assertEquals(listOf("terminal-one", "filesystem"), result.start.map { it.id })
        assertEquals(2, result.activeTotal)
        assertEquals(1, result.activeByKind[WorkerKind.INFERENCE])
        assertEquals(1, result.activeByKind[WorkerKind.TERMINAL])
        assertEquals(0, result.activeByKind[WorkerKind.FILESYSTEM])
        assertEquals(0, result.activeByKind[WorkerKind.DEVELOPMENT])
    }

    @Test fun globalMaximumIsSharedAcrossWorkerKinds() {
        val tasks = listOf(
            ScheduledTask("terminal", 0, workerKind = WorkerKind.TERMINAL),
            ScheduledTask("filesystem", 1, workerKind = WorkerKind.FILESYSTEM),
            ScheduledTask("verification", 2, workerKind = WorkerKind.VERIFICATION),
        )
        val result = ConcurrentTaskScheduler.decide(
            tasks,
            SchedulingPolicy(maxInference = 2, maxTerminal = 2, maxFilesystem = 2, maxVerification = 2, maxDevelopment = 2, maxTotal = 2),
        )
        assertEquals(listOf("terminal", "filesystem"), result.start.map { it.id })
        assertEquals(2, result.start.size)
        assertEquals(0, result.activeTotal)
    }

    @Test fun independentTasksCanRunConcurrentlyWithinPolicy() {
        val tasks = listOf(
            ScheduledTask("i", 0, workerKind = WorkerKind.INFERENCE),
            ScheduledTask("t", 1, workerKind = WorkerKind.TERMINAL),
            ScheduledTask("f", 2, workerKind = WorkerKind.FILESYSTEM),
            ScheduledTask("v", 3, workerKind = WorkerKind.VERIFICATION),
            ScheduledTask("d", 4, workerKind = WorkerKind.DEVELOPMENT),
        )
        val policy = SchedulingPolicy(maxInference = 1, maxTerminal = 1, maxFilesystem = 1, maxVerification = 1, maxDevelopment = 1, maxTotal = 5)
        val result = ConcurrentTaskScheduler.decide(tasks, policy)
        assertEquals(listOf("i", "t", "f", "v", "d"), result.start.map { it.id })
        assertEquals(5, result.start.size)
        assertTrue(result.blocked.isEmpty())
    }

    @Test fun missingAndFailedPrerequisitesAreReportedAsBlocked() {
        val tasks = listOf(
            ScheduledTask("failed", 0, status = "failed"),
            ScheduledTask("one", 1, dependsOn = setOf("failed")),
            ScheduledTask("two", 2, dependsOn = setOf("absent")),
            ScheduledTask("three", 3, dependsOn = setOf("one")),
        )
        val result = ConcurrentTaskScheduler.decide(tasks, SchedulingPolicy())
        assertEquals(listOf("one", "two", "three"), result.blocked.map { it.id })
        assertTrue(result.start.isEmpty())
        assertFalse(result.deadlocked)
    }

    @Test fun pendingDependencyCycleIsADeadlock() {
        val tasks = listOf(
            ScheduledTask("a", 0, dependsOn = setOf("b")),
            ScheduledTask("b", 1, dependsOn = setOf("c")),
            ScheduledTask("c", 2, dependsOn = setOf("a")),
            ScheduledTask("tail", 3, dependsOn = setOf("a")),
        )
        val result = ConcurrentTaskScheduler.decide(tasks, SchedulingPolicy())
        assertTrue(result.start.isEmpty())
        assertTrue(result.blocked.isEmpty())
        assertTrue(result.deadlocked)
    }

    @Test fun activeWorkSaturatingCapacityIsNotADeadlock() {
        val tasks = listOf(
            ScheduledTask("active", 0, workerKind = WorkerKind.TERMINAL, status = "running"),
            ScheduledTask("ready", 1, workerKind = WorkerKind.TERMINAL),
        )
        val result = ConcurrentTaskScheduler.decide(
            tasks,
            SchedulingPolicy(maxTerminal = 1, maxTotal = 1),
        )
        assertTrue(result.start.isEmpty())
        assertFalse(result.deadlocked)
        assertEquals(1, result.activeTotal)
    }

    @Test fun repeatedAndReorderedInputsProduceTheSameDecision() {
        val tasks = listOf(
            ScheduledTask("third", 3, priority = 1, workerKind = WorkerKind.FILESYSTEM),
            ScheduledTask("first", 1, priority = 5, workerKind = WorkerKind.TERMINAL),
            ScheduledTask("second", 2, priority = 5, workerKind = WorkerKind.FILESYSTEM),
        )
        val policy = SchedulingPolicy(maxInference = 1, maxTerminal = 1, maxFilesystem = 1, maxVerification = 1, maxDevelopment = 1, maxTotal = 2)
        val expected = ConcurrentTaskScheduler.decide(tasks, policy)
        assertEquals(listOf("first", "second"), expected.start.map { it.id })
        assertEquals(expected, ConcurrentTaskScheduler.decide(tasks, policy))
        assertEquals(expected, ConcurrentTaskScheduler.decide(tasks.reversed(), policy))
    }
}

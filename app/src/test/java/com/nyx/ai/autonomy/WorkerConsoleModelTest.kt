package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerConsoleModelTest {
    private fun event(
        id: String,
        at: Long,
        objective: String,
        run: String?,
        task: String? = null,
        kind: WorkerActivityKind = WorkerActivityKind.TASK,
        title: String = "event",
        detail: String = "",
        status: String? = null,
    ) = WorkerActivity(
        id = id,
        at = at,
        objectiveId = objective,
        taskId = task,
        kind = kind,
        title = title,
        detail = detail,
        status = status,
        runId = run,
    )

    @Test
    fun `runs separate objectives and execution identities`() {
        val activities = listOf(
            event("a1", 1_000, "objective-a", "run-a1", status = "running"),
            event("a2", 2_000, "objective-a", "run-a1", status = "completed"),
            event("b1", 3_000, "objective-b", "run-b1", status = "completed"),
            event("a-old", 500, "objective-a", "run-a0", status = "completed"),
        )
        val runs = WorkerConsoleModel.runs(activities)
        assertEquals(3, runs.size)
        assertEquals("run-b1", runs.first().runId)
        assertEquals(listOf("run-a1", "run-a0"), WorkerConsoleModel.runsForObjective(activities, "objective-a").map { it.runId })
        assertEquals(listOf("a1", "a2"), WorkerConsoleModel.forRun(activities, "objective-a", "run-a1").map { it.id })
    }

    @Test
    fun `operation grouping uses task identity and measured terminal duration`() {
        val activities = listOf(
            event("start", 1_000, "objective", "run", task = "task-1", title = "task_started", detail = "Build the app · attempt 1/2", status = "running"),
            event("tool", 1_300, "objective", "run", task = "task-1", kind = WorkerActivityKind.TOOL, title = "tool_observation", status = null),
            event("done", 9_000, "objective", "run", task = "task-1", title = "task_completed", status = "completed"),
        )
        val operations = WorkerConsoleModel.operations(activities)
        assertEquals(1, operations.size)
        assertEquals("Build the app", operations.single().title)
        assertEquals("task-1", operations.single().taskId)
        assertEquals("completed", operations.single().status)
        assertEquals(8_000L, WorkerConsoleModel.elapsedMillis(operations.single(), 20_000))
    }

    @Test
    fun `failed operations remain failed and do not invent duration`() {
        val activities = listOf(
            event("start", 1_000, "objective", "run", task = "task-2", title = "task_started", status = "running"),
            event("fail", 2_000, "objective", "run", task = "task-2", title = "task_error", detail = "tool failed", status = "failed"),
        )
        val operation = WorkerConsoleModel.operations(activities).single()
        assertEquals("failed", operation.status)
        assertEquals(1_000L, WorkerConsoleModel.elapsedMillis(operation, 99_000))
    }

    @Test
    fun `ANSI CSI and OSC sequences are removed before display`() {
        val raw = "ok\u001B[31m red\u001B[0m \u001B]8;;https://example.test\u0007link\u001B]8;;\u0007"
        assertEquals("ok red link", WorkerActivityBus.sanitizeTerminalText(raw))
    }

    @Test
    fun `legacy events without run ids remain visible as a legacy run`() {
        val activities = listOf(event("old", 1_000, "objective", null, status = "completed"))
        val runs = WorkerConsoleModel.runs(activities)
        assertEquals(null, runs.single().runId)
        assertEquals(1, runs.single().events.size)
    }

    @Test
    fun `current run is the newest run even when history has stale running state`() {
        val activities = listOf(
            event("old", 1_000, "objective", "run-old", status = "running"),
            event("new", 2_000, "objective", "run-new", status = "completed"),
        )
        assertEquals("run-new", WorkerConsoleModel.currentRun(activities, "objective")?.runId)
    }
}

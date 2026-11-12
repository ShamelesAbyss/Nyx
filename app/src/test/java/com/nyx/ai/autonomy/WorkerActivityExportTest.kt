package com.nyx.ai.autonomy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerActivityExportTest {
    @Test
    fun `export keeps observable details and redacts credential shapes`() {
        val activity = WorkerActivity(
            id = "event-1",
            at = 1_700_000_000_000,
            objectiveId = "objective-1",
            taskId = "task-1",
            kind = WorkerActivityKind.TOOL,
            title = "terminal_run",
            detail = "api_key=super-secret-value",
            toolName = "terminal_run",
            toolInput = "authorization: Bearer abcdefghijklmnop",
            toolOutput = "token=hidden-token-value",
        )
        val text = WorkerActivityExport.toText(
            activities = listOf(activity),
            objectiveTitle = "Build the app",
            phase = "running",
            status = "running",
            activeTask = "Compile",
        )
        assertTrue(text.contains("NYX LIVE ACTIVITY"))
        assertTrue(text.contains("Build the app"))
        assertTrue(text.contains("terminal_run"))
        assertTrue(text.contains("input:"))
        assertTrue(text.contains("output:"))
        assertTrue(text.contains("[redacted]"))
        assertFalse(text.contains("super-secret-value"))
        assertFalse(text.contains("hidden-token-value"))
        assertFalse(text.contains("abcdefghijklmnop"))
    }

    @Test
    fun `export is bounded even with large tool payloads`() {
        val huge = "x".repeat(20_000)
        val activities = (1..40).map { index ->
            WorkerActivity(
                id = "event-$index",
                at = 1_700_000_000_000 + index,
                objectiveId = "objective-1",
                taskId = null,
                kind = WorkerActivityKind.TOOL,
                title = "tool-$index",
                detail = huge,
                toolInput = huge,
                toolOutput = huge,
            )
        }
        val text = WorkerActivityExport.toText(activities, limit = 2_000)
        assertTrue(text.length <= 2_000)
        assertTrue(text.contains("NYX LIVE ACTIVITY"))
    }
}

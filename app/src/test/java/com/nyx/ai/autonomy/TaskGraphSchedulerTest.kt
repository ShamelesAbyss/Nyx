package com.nyx.ai.autonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGraphSchedulerTest {
    @Test fun dependentsWaitAndThenBecomeReady() {
        val a = TaskSnapshot("a", 0, emptySet(), "completed")
        val b = TaskSnapshot("b", 1, setOf("a"), "pending")
        val c = TaskSnapshot("c", 2, setOf("b"), "pending")
        assertEquals("b", TaskGraphScheduler.nextReady(listOf(c, b, a))?.id)
        assertTrue(TaskGraphScheduler.blocked(listOf(a, b, c)).isEmpty())
        assertEquals(listOf("c"), TaskGraphScheduler.blocked(listOf(a, b.copy(status = "failed"), c)).map { it.id })
        assertEquals("c", TaskGraphScheduler.nextReady(listOf(a, b.copy(status = "completed"), c))?.id)
    }

    @Test fun independentTasksAreChosenOneAtATimeInStableOrder() {
        val second = TaskSnapshot("second", 2, emptySet(), "pending")
        val first = TaskSnapshot("first", 1, emptySet(), "pending")
        assertEquals("first", TaskGraphScheduler.nextReady(listOf(second, first))?.id)
        assertEquals("second", TaskGraphScheduler.nextReady(listOf(first.copy(status = "running"), second))?.id)
    }

    @Test fun failedOrMissingPrerequisitesAreContained() {
        val failed = TaskSnapshot("failed", 0, emptySet(), "failed")
        val child = TaskSnapshot("child", 1, setOf("failed"), "pending")
        val missing = TaskSnapshot("missing-child", 2, setOf("not-in-plan"), "pending")
        assertEquals(listOf("child", "missing-child"), TaskGraphScheduler.blocked(listOf(failed, child, missing)).map { it.id })
        assertTrue(TaskGraphScheduler.nextReady(listOf(failed, child, missing)) == null)
    }

    @Test fun reportedToolFailuresAndEmptyOutputFailLocalVerification() {
        assertTrue(TaskGraphScheduler.hasVerifiedLocalOutcome("done", listOf("workspace_read: found")))
        assertFalse(TaskGraphScheduler.hasVerifiedLocalOutcome("done", listOf("Tool error: disk full")))
        assertFalse(TaskGraphScheduler.hasVerifiedLocalOutcome("done", listOf("Tool denied: not enabled")))
        assertFalse(TaskGraphScheduler.hasVerifiedLocalOutcome("  ", emptyList()))
    }
}

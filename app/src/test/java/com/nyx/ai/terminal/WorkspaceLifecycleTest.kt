package com.nyx.ai.terminal

import com.nyx.ai.autonomy.policy.CapabilityGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLifecycleTest {
    private val base = "/root/nyx-workspaces"

    @Test
    fun `managed paths are exactly direct children`() {
        assertTrue(WorkspaceLifecycle.isManaged(base, "$base/nyx-app"))
        assertTrue(WorkspaceLifecycle.isManaged(base, "$base/nyx-app-2"))
        assertFalse(WorkspaceLifecycle.isManaged(base, base))
        assertFalse(WorkspaceLifecycle.isManaged(base, "$base/nyx-app/nested"))
        assertFalse(WorkspaceLifecycle.isManaged(base, "/root/nyx-workspaces-other/nyx-app"))
        assertFalse(WorkspaceLifecycle.isManaged(base, "$base/../escape"))
        assertFalse(WorkspaceLifecycle.isManaged(base, "$base//escape"))
        assertFalse(WorkspaceLifecycle.isManaged("/", "/anything"))
    }

    @Test
    fun `saved workspace is reused only when the probe matches it`() {
        val path = "$base/nyx-app"
        assertTrue(WorkspaceLifecycle.startupDecision("", null) is WorkspaceStartupDecision.None)
        assertTrue(WorkspaceLifecycle.startupDecision(path, null) is WorkspaceStartupDecision.Unavailable)
        assertTrue(WorkspaceLifecycle.startupDecision(path, WorkspaceCheck.Invalid("missing", "missing", "create")) is WorkspaceStartupDecision.Unavailable)
        val decision = WorkspaceLifecycle.startupDecision(path, WorkspaceCheck.Valid(path))
        assertTrue(decision is WorkspaceStartupDecision.Reuse)
        assertEquals(path, (decision as WorkspaceStartupDecision.Reuse).path)
        assertTrue(WorkspaceLifecycle.startupDecision(path, WorkspaceCheck.Valid("$base/other")) is WorkspaceStartupDecision.Unavailable)
    }

    @Test
    fun `active or running objectives protect workspaces from deletion`() {
        assertFalse(WorkspaceLifecycle.hasActiveObjective(false, listOf("completed", "cancelled")))
        assertTrue(WorkspaceLifecycle.hasActiveObjective(true, emptyList()))
        assertTrue(WorkspaceLifecycle.hasActiveObjective(false, listOf("paused")))
        assertTrue(WorkspaceLifecycle.hasActiveObjective(false, listOf("needs_attention")))
        assertTrue(WorkspaceLifecycle.hasActiveObjective(false, listOf("planning")))
    }

    @Test
    fun `deletion script refuses roots symlinks and path escapes`() {
        val script = WorkspaceLifecycle.deleteScript(base, "$base/nyx-app")
        assertTrue(script.contains("[ ! -L \"${'$'}base\" ]"))
        assertTrue(script.contains("[ ! -L \"${'$'}ws\" ]"))
        assertTrue(script.contains("realpath -e"))
        assertTrue(script.contains("rm -rf -- \"${'$'}ws\""))
        assertTrue(script.contains("refusing to delete the managed workspace root"))
        assertTrue(script.contains("path is outside the managed workspace root"))
        assertTrue(script.contains("direct child"))
        assertFalse(script.contains("rm -rf /"))
        assertFalse(CommandPolicy.assess(script).blocked)
    }

    @Test
    fun `deletion parser only accepts the exact confirmed path`() {
        val path = "$base/nyx-app"
        val success = WorkspaceLifecycle.parseDelete("NYX_WS_DELETED=1\nNYX_WS_PATH=$path\n", "", 0, path)
        assertTrue(success is WorkspaceDeleteResult.Deleted)
        assertEquals(path, (success as WorkspaceDeleteResult.Deleted).path)
        val wrong = WorkspaceLifecycle.parseDelete("NYX_WS_DELETED=1\nNYX_WS_PATH=$base/other\n", "", 0, path)
        assertTrue(wrong is WorkspaceDeleteResult.Refused)
        val refused = WorkspaceLifecycle.parseDelete("NYX_WS_DELETED=0\nNYX_WS_ERROR=not canonical\n", "", 4, path)
        assertTrue(refused is WorkspaceDeleteResult.Refused)
        assertEquals("not canonical", (refused as WorkspaceDeleteResult.Refused).message)
    }

    @Test
    fun `scope mismatch is detected only when a conflicting grant exists`() {
        val stale = grant("$base/nyx-app")
        val correct = grant("$base/nyx-app-2")
        assertFalse(WorkspaceLifecycle.hasScopeMismatch(emptyList(), "proot:debian", "$base/nyx-app-2", "command.mutating"))
        assertTrue(WorkspaceLifecycle.hasScopeMismatch(listOf(stale), "proot:debian", "$base/nyx-app-2", "command.mutating"))
        assertFalse(WorkspaceLifecycle.hasScopeMismatch(listOf(correct), "proot:debian", "$base/nyx-app-2", "command.mutating"))
        assertFalse(WorkspaceLifecycle.hasScopeMismatch(listOf(stale), "termux_host", "$base/nyx-app-2", "command.mutating"))
        assertFalse(WorkspaceLifecycle.hasScopeMismatch(listOf(stale), "proot:debian", "$base/nyx-app-2", "command.readonly"))
    }

    private fun grant(scope: String) = CapabilityGrant(
        id = "grant-1",
        capability = "command.mutating",
        environmentId = "proot:debian",
        scope = scope,
        createdAt = 1L,
    )
}

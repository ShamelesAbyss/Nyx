package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProvisionerTest {
    @Test
    fun `project names are reduced to a safe segment`() {
        assertEquals("my-project", WorkspaceProvisioner.sanitizeName("My Project!"))
        assertEquals("project", WorkspaceProvisioner.sanitizeName("///"))
        assertEquals("a-b", WorkspaceProvisioner.sanitizeName("  A__B  "))
    }

    @Test
    fun `names are bounded and never contain traversal`() {
        val long = WorkspaceProvisioner.sanitizeName("x".repeat(200))
        assertTrue(long.length <= WorkspaceProvisioner.MAX_NAME_CHARS)
        assertTrue(!long.contains(".."))
        assertTrue(!long.startsWith('-'))
    }

    @Test
    fun `colliding paths receive a numeric suffix`() {
        val base = "/root/nyx-workspaces"
        val first = WorkspaceProvisioner.planPath(base, "app")
        assertEquals("/root/nyx-workspaces/app", first)
        val second = WorkspaceProvisioner.planPath(base, "app", setOf(first))
        assertEquals("/root/nyx-workspaces/app-2", second)
        assertNotEquals(first, second)
    }

    @Test
    fun `provision candidates are deterministic and never reuse attempt one`() {
        val base = "/root/nyx-workspaces"
        assertEquals("$base/app", WorkspaceProvisioner.candidatePath(base, "app", 1))
        assertEquals("$base/app-2", WorkspaceProvisioner.candidatePath(base, "app", 2))
        assertEquals("$base/app-20", WorkspaceProvisioner.candidatePath(base, "app", 20))
    }

    @Test
    fun `default bases are environment specific`() {
        assertEquals("/root/nyx-workspaces", WorkspaceProvisioner.defaultBase(TerminalEnvironment.DEBIAN_PROOT, null))
        assertEquals("/data/data/com.termux/files/home/nyx-workspaces", WorkspaceProvisioner.defaultBase(TerminalEnvironment.TERMUX_HOST, "/data/data/com.termux/files/home"))
        assertEquals(null, WorkspaceProvisioner.defaultBase(TerminalEnvironment.TERMUX_HOST, null))
    }

    @Test
    fun `creation script uses atomic leaf mkdir and reports collisions`() {
        val script = WorkspaceProvisioner.createScript("/root/it's a workspace")
        assertTrue(script.contains("mkdir -- \"${'$'}ws\""))
        assertTrue(script.contains("NYX_WS_EXISTS=1"))
        assertTrue(script.contains("[ -e \"${'$'}ws\" ]"))
        assertTrue(script.contains("'\\''"))
        assertTrue(script.contains("[ ! -w "))
        assertTrue(script.contains("NYX_WS_CREATED=1"))
        assertFalse(script.contains("mkdir -p -- \"${'$'}ws\""))
    }

    @Test
    fun `creation script never interpolates an unquoted path`() {
        val script = WorkspaceProvisioner.createScript("/tmp/x; rm -rf /")
        assertTrue(script.contains("'/tmp/x; rm -rf /'"))
        assertFalse(script.contains("mkdir -- /tmp/x;"))
        assertFalse(script.contains("mkdir -p -- /tmp/x;"))
    }
}

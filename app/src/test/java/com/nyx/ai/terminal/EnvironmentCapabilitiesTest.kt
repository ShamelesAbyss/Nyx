package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentCapabilitiesTest {

    private val now = 1_700_000_000_000L

    private fun parse(stdout: String): EnvironmentCapabilities =
        CapabilityProbe.parse(stdout, environmentId = "termux_host", kind = "termux_host", nowMillis = now)

    @Test
    fun `probe output is parsed into tool evidence`() {
        val caps = parse(
            """
            NYX_CAP:git:VERIFIED:/usr/bin/git:git version 2.43.0
            NYX_CAP:java:VERIFIED:/usr/bin/java:openjdk version "21.0.1" 2023-10-17
            NYX_CAP:kotlinc:MISSING::
            NYX_STORAGE_FREE=1048576
            NYX_WORKSPACE=1
            """.trimIndent(),
        )
        assertEquals("termux_host", caps.environmentId)
        assertEquals("termux_host", caps.kind)
        assertEquals(now, caps.checkedAt)
        assertEquals("probe", caps.source)
        assertEquals(CapabilityState.VERIFIED, caps.stateOf("git"))
        assertEquals("/usr/bin/git", caps.tools["git"]?.path)
        assertEquals("git version 2.43.0", caps.tools["git"]?.version)
        assertEquals(CapabilityState.MISSING, caps.stateOf("kotlinc"))
        assertNull(caps.tools["kotlinc"]?.path)
        assertNull(caps.tools["kotlinc"]?.version)
        assertEquals(1_048_576L, caps.storageFreeBytes)
        assertEquals(true, caps.workspaceAccessible)
    }

    @Test
    fun `carriage returns and padding are tolerated`() {
        val caps = parse("  NYX_CAP:git:VERIFIED:/usr/bin/git:git version 2.43.0\r\nNYX_WORKSPACE=0\r\n")
        assertEquals(CapabilityState.VERIFIED, caps.stateOf("git"))
        assertEquals("git version 2.43.0", caps.tools["git"]?.version)
        assertEquals(false, caps.workspaceAccessible)
    }

    @Test
    fun `malformed lines are ignored`() {
        val caps = parse(
            """
            not a probe line
            NYX_CAP:git
            NYX_CAP::VERIFIED:/usr/bin/git:1.0
            NYX_CAP:git:UNKNOWN:/usr/bin/git:1.0
            NYX_CAP:git:VERIFIED::1.0
            NYX_CAP:bad name:VERIFIED:/usr/bin/git:1.0
            NYX_CAP:git:VERIFIED:relative/git:1.0
            NYX_CAP:git:MISSING:/usr/bin/git:
            NYX_STORAGE_FREE=abc
            NYX_STORAGE_FREE=-5
            NYX_WORKSPACE=yes
            """.trimIndent(),
        )
        assertTrue(caps.tools.isEmpty())
        assertNull(caps.storageFreeBytes)
        assertNull(caps.workspaceAccessible)
        assertEquals(CapabilityState.NOT_CHECKED, caps.stateOf("git"))
    }

    @Test
    fun `the last observation wins for duplicate tools`() {
        val caps = parse(
            """
            NYX_CAP:git:VERIFIED:/usr/bin/git:git version 2.43.0
            NYX_CAP:git:MISSING::
            """.trimIndent(),
        )
        assertEquals(1, caps.tools.size)
        assertEquals(CapabilityState.MISSING, caps.stateOf("git"))
    }

    @Test
    fun `permission denied keeps the path and the diagnostic`() {
        val caps = parse("NYX_CAP:python3:PERMISSION_DENIED:/usr/bin/python3:python3: Permission denied")
        val tool = caps.tools.getValue("python3")
        assertEquals(CapabilityState.PERMISSION_DENIED, tool.state)
        assertEquals("/usr/bin/python3", tool.path)
        assertNull(tool.version)
        assertEquals("python3: Permission denied", tool.detail)
    }

    @Test
    fun `incompatible tools keep their diagnostic`() {
        val caps = parse("NYX_CAP:node:INCOMPATIBLE:/usr/local/bin/node:present but not executable")
        val tool = caps.tools.getValue("node")
        assertEquals(CapabilityState.INCOMPATIBLE, tool.state)
        assertEquals("present but not executable", tool.detail)
    }

    @Test
    fun `version text is sanitized to one printable line`() {
        val control = parse("NYX_CAP:node:VERIFIED:/usr/bin/node:\tv20.11.0\u0007  extra")
        assertEquals("v20.11.0 extra", control.tools["node"]?.version)

        val nonAscii = parse("NYX_CAP:gcc:VERIFIED:/usr/bin/gcc:gcc \u00e9 13.2")
        assertEquals("gcc 13.2", nonAscii.tools["gcc"]?.version)
    }

    @Test
    fun `versions are capped at eighty characters`() {
        val caps = parse("NYX_CAP:npm:VERIFIED:/usr/bin/npm:" + "v".repeat(200))
        assertEquals(CapabilityProbe.MAX_VERSION_CHARS, caps.tools["npm"]?.version?.length ?: 0)
    }

    @Test
    fun `colons inside a version survive parsing`() {
        val caps = parse("NYX_CAP:clang:VERIFIED:/usr/bin/clang:clang version 17.0.0 (target: aarch64)")
        assertEquals("clang version 17.0.0 (target: aarch64)", caps.tools["clang"]?.version)
    }

    @Test
    fun `unknown tools default to not checked`() {
        val caps = parse("NYX_CAP:git:VERIFIED:/usr/bin/git:2.43.0")
        assertEquals(CapabilityState.NOT_CHECKED, caps.stateOf("cmake"))
        assertEquals(CapabilityState.NOT_CHECKED, caps.stateOf(""))
        assertEquals(CapabilityState.NOT_CHECKED, caps.stateOf("GIT"))
    }

    @Test
    fun `tool entries are capped`() {
        val stdout = (1..100).joinToString("\n") { "NYX_CAP:t$it:VERIFIED:/usr/bin/t$it:1.0" }
        val caps = parse(stdout)
        assertEquals(CapabilityProbe.MAX_TOOL_ENTRIES, caps.tools.size)
    }

    @Test
    fun `staleness follows the ttl and rejects future timestamps`() {
        val caps = EnvironmentCapabilities("e", "termux_host", checkedAt = now)
        assertFalse(caps.isStale(now))
        assertFalse(caps.isStale(now + EnvironmentCapabilities.DEFAULT_TTL_MILLIS))
        assertTrue(caps.isStale(now + EnvironmentCapabilities.DEFAULT_TTL_MILLIS + 1))
        assertTrue(caps.isStale(now - 1))
        assertTrue(caps.isStale(now, ttlMillis = -1L))
    }

    @Test
    fun `clock skew far in the past is stale`() {
        val caps = EnvironmentCapabilities("e", "proot:debian", checkedAt = Long.MIN_VALUE)
        assertTrue(caps.isStale(now))
    }

    @Test
    fun `workspace access is updated without losing tool evidence`() {
        val caps = parse("NYX_CAP:git:VERIFIED:/usr/bin/git:2.43.0")
        val updated = caps.withWorkspaceAccess(true)
        assertEquals(true, updated.workspaceAccessible)
        assertEquals(CapabilityState.VERIFIED, updated.stateOf("git"))
        assertEquals(caps.checkedAt, updated.checkedAt)
        assertNull(caps.workspaceAccessible)
    }

    @Test
    fun `tool list is the documented set`() {
        assertEquals(
            listOf(
                "sh", "git", "java", "javac", "kotlinc", "gradle", "sdkmanager", "adb",
                "clang", "gcc", "make", "cmake", "python3", "node", "npm", "pkg", "apt", "dpkg",
            ),
            CapabilityProbe.TOOL_NAMES,
        )
    }

    @Test
    fun `probe script covers every tool and every marker`() {
        val script = CapabilityProbe.script()
        val loopLine = script.lineSequence().first { it.trimStart().startsWith("for _tool in") }
        val loopTools = loopLine.split(" ", ";")
        for (tool in CapabilityProbe.TOOL_NAMES) {
            assertTrue("probe script is missing $tool", loopTools.contains(tool))
        }
        assertTrue(script.contains("NYX_CAP:%s:%s:%s:%s:%s"))
        assertTrue(script.contains("NYX_STORAGE_FREE=%s"))
        assertTrue(script.contains("NYX_WORKSPACE=%s"))
        assertTrue(script.contains("PERMISSION_DENIED"))
        assertTrue(script.contains("INCOMPATIBLE"))
        assertTrue(script.contains("MISSING"))
        assertFalse(script.contains("curl"))
        assertFalse(script.contains("apt-get"))
        assertFalse(script.contains("pkg install"))
    }
}

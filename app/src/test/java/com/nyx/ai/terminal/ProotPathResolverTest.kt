package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotPathResolverTest {

    private val verifiedPath = "/data/data/com.termux/files/usr/bin/proot-distro"

    @Test
    fun `discovered path is recovered by a fresh bridge view`() {
        val discovered = ProotPathResolver.choose(verifiedPath, null)
        assertEquals(verifiedPath, discovered)
        // A bridge created later receives no fresh hint; the persisted path must still resolve.
        val freshView = ProotPathResolver.choose(null, discovered)
        assertEquals(verifiedPath, freshView)
    }

    @Test
    fun `fresh discovery wins over a stale persisted path`() {
        assertEquals(
            "/data/data/com.termux/files/usr/bin/proot-distro",
            ProotPathResolver.choose(
                "/data/data/com.termux/files/usr/bin/proot-distro",
                "/data/data/com.termux/files/usr/bin/old-proot-distro",
            ),
        )
    }

    @Test
    fun `relative nul and oversized paths are rejected`() {
        assertNull(ProotPathResolver.choose("relative/proot-distro", null))
        assertNull(ProotPathResolver.choose("/data/data/\u0000/proot-distro", null))
        assertNull(ProotPathResolver.choose("/" + "a".repeat(4_096), null))
        assertNull(ProotPathResolver.choose(null, null))
    }

    @Test
    fun `verified path produces a debian dispatch descriptor`() {
        val request = ShellCommandBuilder.prootRequest(
            "ollama --version",
            workingDirectory = null,
            timeoutSeconds = 30,
            useTimeoutBinary = false,
            authorized = true,
            distroAlias = "debian",
        )
        val descriptor = TerminalCommandDescriptors.create(request, verifiedPath)
        assertNotNull(descriptor)
        assertEquals(verifiedPath, descriptor!!.executablePath)
        assertEquals(listOf("login", "debian", "--", "/bin/bash", "-lc", "ollama --version"), descriptor.arguments)
    }

    @Test
    fun `missing path is refused instead of dispatching a guessed executable`() {
        val request = ShellCommandBuilder.prootRequest(
            "ollama --version",
            workingDirectory = null,
            timeoutSeconds = 30,
            useTimeoutBinary = false,
            authorized = true,
        )
        assertNull(TerminalCommandDescriptors.create(request, null))
    }

    @Test
    fun `termux host execution remains a direct bash dispatch`() {
        val request = ShellCommandBuilder.hostRequest(
            "uname -a",
            workingDirectory = null,
            timeoutSeconds = 30,
            useTimeoutBinary = false,
        )
        val descriptor = TerminalCommandDescriptors.create(request, verifiedPath)
        assertNotNull(descriptor)
        assertEquals(ShellCommandBuilder.TERMUX_SHELL, descriptor!!.executablePath)
        assertEquals(listOf("-lc", "uname -a"), descriptor.arguments)
        assertTrue(descriptor.arguments.none { it.contains("proot-distro") })
    }
}

package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentProbeParserTest {

    @Test
    fun `host probe parses paths and tools`() {
        val stdout = """
            NYX_HOME=/data/data/com.termux/files/home
            NYX_PREFIX=/data/data/com.termux/files/usr
            NYX_UNAME=Linux localhost 5.10 aarch64 Android
            NYX_TOOL:bash=/data/data/com.termux/files/usr/bin/bash
            NYX_TOOL:proot-distro=/data/data/com.termux/files/usr/bin/proot-distro
            NYX_TOOL:git=
        """.trimIndent()
        val parsed = EnvironmentProbeParser.parseHost(stdout)
        assertEquals("/data/data/com.termux/files/home", parsed.home)
        assertEquals("/data/data/com.termux/files/usr", parsed.prefix)
        assertEquals("aarch64", parsed.uname?.split(" ")?.get(3))
        assertEquals(2, parsed.tools.size)
        assertNull(parsed.tools["git"])
    }

    @Test
    fun `installed distributions are read from the installed section only`() {
        val stdout = """
            Supported distributions:
              * alpine (3.19)
              * archlinux (latest)
              * debian (12)
            Installed distributions:
              * debian (12)
        """.trimIndent()
        assertEquals(listOf("debian"), EnvironmentProbeParser.parseInstalledDistros(stdout))
    }

    @Test
    fun `no installed section means no distros`() {
        val stdout = """
            Supported distributions:
              * debian (12)
        """.trimIndent()
        assertTrue(EnvironmentProbeParser.parseInstalledDistros(stdout).isEmpty())
    }

    @Test
    fun `single path parser ignores noise and relative paths`() {
        assertEquals("/data/data/com.termux/files/usr/bin/bash", EnvironmentProbeParser.parseSinglePath("warning\nrelative\n/data/data/com.termux/files/usr/bin/bash\n"))
        assertNull(EnvironmentProbeParser.parseSinglePath("not-a-path"))
    }

    @Test
    fun `profile reports debian availability`() {
        val profile = EnvironmentProfile(hostAvailable = true, distros = listOf("debian", "alpine"))
        assertTrue(profile.debianAvailable)
    }
}

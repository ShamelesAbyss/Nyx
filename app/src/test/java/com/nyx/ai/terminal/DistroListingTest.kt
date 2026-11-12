package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroListingTest {

    @Test
    fun `quiet output yields installed alias`() {
        val result = ProotDistroListParser.parse("debian\n", "", 0)
        assertTrue(result is DistroListResult.Installed)
        assertEquals(listOf("debian"), (result as DistroListResult.Installed).aliases)
        assertEquals("quiet", result.format)
    }

    @Test
    fun `sectioned output ignores supported distributions`() {
        val stdout = """
            Supported distributions:
              * alpine
              * archlinux
              * debian

            Installed distributions:
              * debian
        """.trimIndent()
        val result = ProotDistroListParser.parse(stdout, "", 0) as DistroListResult.Installed
        assertEquals(listOf("debian"), result.aliases)
        assertEquals("sectioned", result.format)
    }

    @Test
    fun `older annotated output is parsed`() {
        val stdout = """
            Installed distributions:
              * debian (installed)
              * ubuntu (installed)
        """.trimIndent()
        val result = ProotDistroListParser.parse(stdout, "", 0) as DistroListResult.Installed
        assertEquals(listOf("debian", "ubuntu"), result.aliases)
    }

    @Test
    fun `multiple quiet aliases are all returned once`() {
        val result = ProotDistroListParser.parse("debian\nubuntu\ndebian\n", "", 0) as DistroListResult.Installed
        assertEquals(listOf("debian", "ubuntu"), result.aliases)
    }

    @Test
    fun `nested proot error is classified and never an empty list`() {
        val stderr = "Error: attempted to run proot-distro in a proot session.\nPlease check your system configuration"
        val result = ProotDistroListParser.parse("", stderr, 1)
        assertTrue(result is DistroListResult.NestedProot)
        assertTrue((result as DistroListResult.NestedProot).detail.contains("inside an existing PRoot guest"))
    }

    @Test
    fun `failed command is not converted into an empty installation list`() {
        val result = ProotDistroListParser.parse("", "proot-distro: command not found", 127)
        assertTrue(result is DistroListResult.Failed)
        assertEquals(127, (result as DistroListResult.Failed).exitCode)
        assertTrue(result.detail.contains("not found"))
    }

    @Test
    fun `missing exit code is a failure`() {
        val result = ProotDistroListParser.parse("debian\n", "", null)
        assertTrue(result is DistroListResult.Failed)
    }

    @Test
    fun `genuinely empty successful list is allowed`() {
        val result = ProotDistroListParser.parse("", "", 0)
        assertTrue(result is DistroListResult.Installed)
        assertTrue((result as DistroListResult.Installed).aliases.isEmpty())
    }

    @Test
    fun `guest probe success reports facts and tools`() {
        val stdout = """
            NYX_GUEST_OK
            NYX_GUEST_HOME=/root
            NYX_GUEST_PWD=/root
            NYX_GUEST_SHELL=/bin/bash
            NYX_GUEST_OS=Linux
            NYX_GUEST_UID=0
            NYX_GUEST_TOOL:gcc=/usr/bin/gcc
        """.trimIndent()
        val probe = GuestProbeParser.parse(stdout, "", 0)
        assertTrue(probe.verified)
        assertEquals("/root", probe.home)
        assertEquals("0", probe.uid)
        assertEquals("/usr/bin/gcc", probe.tools["gcc"])
    }

    @Test
    fun `guest probe without marker is unverified`() {
        val probe = GuestProbeParser.parse("NYX_GUEST_HOME=/root\n", "", 0)
        assertFalse(probe.verified)
        assertTrue(probe.detail!!.contains("marker"))
    }

    @Test
    fun `guest probe failure keeps the real exit code`() {
        val probe = GuestProbeParser.parse("", "proot-distro: distribution is not installed", 1)
        assertFalse(probe.verified)
        assertTrue(probe.detail!!.contains("not installed"))
    }

    @Test
    fun `host identity distinguishes host from guest`() {
        val host = HostShell.parseIdentity("NYX_PREFIX=/data/data/com.termux/files/usr\nNYX_ANDROID_HOST=1\n")
        assertTrue(host.androidHost)
        assertEquals(HostShell.TERMUX_PREFIX, host.prefix)

        val guest = HostShell.parseIdentity("NYX_PREFIX=\nNYX_HOME=/root\nNYX_ANDROID_HOST=0\n")
        assertFalse(guest.androidHost)
        assertEquals("/root", guest.home)
    }

    @Test
    fun `isolated request bypasses profile scripts`() {
        val request = HostShell.isolatedRequest("printf ok", 30)
        assertEquals(TerminalEnvironment.TERMUX_HOST, request.environment)
        assertEquals(HostShell.SHELL_PATH, request.commandPath)
        assertTrue(request.arguments.contains("--noprofile"))
        assertTrue(request.arguments.contains("--norc"))
        assertTrue(request.arguments.last().startsWith("unset BASH_ENV ENV;"))
    }

    @Test
    fun `guest verification script quotes the guest probe and rejects unsafe aliases`() {
        val script = HostShell.guestVerificationScript("debian")
        assertTrue(script.startsWith("proot-distro login debian -- /bin/sh -c '"))
        assertTrue(script.contains(GuestProbeParser.MARKER))

        var rejected = false
        try {
            HostShell.guestVerificationScript("debian; rm -rf /")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}

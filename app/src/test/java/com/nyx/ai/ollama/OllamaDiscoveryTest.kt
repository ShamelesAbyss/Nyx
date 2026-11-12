package com.nyx.ai.ollama

import com.nyx.ai.terminal.TerminalEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaDiscoveryTest {
    private fun installation(
        target: OllamaTarget,
        installed: Boolean = true,
        compatible: Boolean = true,
        version: String = "0.12.3",
        architecture: String = "aarch64",
    ) = OllamaInstallation(
        target,
        OllamaDetection(
            installed = installed,
            path = if (installed) "/usr/local/bin/ollama" else null,
            version = version,
            target = target,
            architecture = architecture,
            compatible = compatible,
        ),
    )

    @Test
    fun `guest-only installation is selected instead of reporting the host missing`() {
        val guest = OllamaTarget.guest("debian")!!
        val plan = OllamaDiscovery.choose(
            listOf(
                installation(OllamaTarget.HOST, installed = false),
                installation(guest),
            ),
            preferredEnvironment = TerminalEnvironment.DEBIAN_PROOT,
            preferredGuest = "debian",
        )

        assertEquals(guest, plan.selected?.target)
        assertTrue(plan.selected?.detection?.compatible == true)
        assertEquals("aarch64", plan.selected?.detection?.architecture)
    }

    @Test
    fun `preferred guest wins when both host and guest are compatible`() {
        val guest = OllamaTarget.guest("debian")!!
        val plan = OllamaDiscovery.choose(
            listOf(installation(OllamaTarget.HOST), installation(guest)),
            preferredEnvironment = TerminalEnvironment.DEBIAN_PROOT,
            preferredGuest = "debian",
        )

        assertEquals(guest, plan.selected?.target)
    }

    @Test
    fun `host remains the fallback when no verified guest is configured`() {
        val plan = OllamaDiscovery.choose(
            listOf(installation(OllamaTarget.HOST)),
            preferredEnvironment = TerminalEnvironment.DEBIAN_PROOT,
            preferredGuest = null,
        )

        assertEquals(OllamaTarget.HOST, plan.selected?.target)
    }

    @Test
    fun `an incompatible installation is retained for diagnostics and not selected as runnable`() {
        val guest = OllamaTarget.guest("debian")!!
        val plan = OllamaDiscovery.choose(
            listOf(installation(guest, compatible = false, architecture = "armv7l")),
            preferredEnvironment = TerminalEnvironment.DEBIAN_PROOT,
            preferredGuest = "debian",
        )

        assertNotNull(plan.selected)
        assertFalse(plan.selected!!.detection.compatible)
        assertTrue(plan.detail.contains("armv7l"))
    }

    @Test
    fun `no installation produces a clear missing result`() {
        val plan = OllamaDiscovery.choose(emptyList())

        assertNull(plan.selected)
        assertTrue(plan.detail.contains("not installed"))
    }

    @Test
    fun `guest target validates aliases and keeps environment identity separate`() {
        assertNull(OllamaTarget.guest("bad alias"))
        val guest = OllamaTarget.guest("debian")!!
        assertEquals("proot:debian", guest.environmentId)
        assertEquals("Termux host", OllamaTarget.HOST.label)
        assertTrue(guest.label.contains("debian"))
    }
}

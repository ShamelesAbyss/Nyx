package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestJdkResolverTest {

    private fun probe(
        version: String = "13",
        codename: String = "trixie",
        sources: Int = 3,
        packages: Map<String, String> = mapOf(
            "default-jdk-headless" to "2:1.21-76",
            "openjdk-21-jdk-headless" to "21.0.13+9-1~deb13u2",
            "openjdk-17-jdk-headless" to "(none)",
        ),
        gradle: String? = "8.14",
    ): String = buildString {
        appendLine("NYX_OS_ID=debian")
        appendLine("NYX_VERSION_ID=$version")
        appendLine("NYX_VERSION_CODENAME=$codename")
        appendLine("NYX_ARCH=arm64")
        appendLine("NYX_APT_SOURCES=$sources")
        appendLine("NYX_APT_CACHE=1")
        packages.forEach { (name, candidate) -> appendLine("NYX_PKG:$name=$candidate") }
        gradle?.let { appendLine("NYX_GRADLE_VERSION=$it") }
    }

    @Test
    fun `trixie resolves openjdk 21 from a real apt candidate`() {
        val resolution = GuestJdkResolver.parse(probe(), "", 0)
        assertEquals("openjdk-21-jdk-headless", resolution.packageName)
        assertEquals("debian", resolution.osId)
        assertEquals("13", resolution.versionId)
        assertEquals("trixie", resolution.codename)
        assertEquals("arm64", resolution.architecture)
        assertEquals(3, resolution.aptSourceCount)
        assertEquals("8.14", resolution.gradleVersion)
        assertTrue(resolution.diagnostic.contains("Resolved openjdk-21-jdk-headless"))
        assertTrue(resolution.diagnostic.contains("APT candidate"))
    }

    @Test
    fun `valid deb822 source count resolves openjdk 21 on trixie`() {
        val resolution = GuestJdkResolver.parse(probe(sources = 2), "", 0)
        assertEquals("openjdk-21-jdk-headless", resolution.packageName)
        assertEquals(2, resolution.aptSourceCount)
        assertTrue(resolution.diagnostic.contains("21.0.13+9-1~deb13u2"))
    }

    @Test
    fun `disabled deb822 sources do not trust a cached candidate`() {
        val resolution = GuestJdkResolver.parse(probe(sources = 0), "", 0)
        assertNull(resolution.packageName)
        assertTrue(resolution.diagnostic.contains("No APT deb sources"))
    }

    @Test
    fun `missing source marker cannot trust a cached candidate`() {
        val output = probe().lineSequence()
            .filterNot { it.startsWith("NYX_APT_SOURCES=") }
            .joinToString("\n")
        val resolution = GuestJdkResolver.parse(output, "", 0)
        assertNull(resolution.packageName)
        assertNull(resolution.aptSourceCount)
    }

    @Test
    fun `trixie falls back to the default package when 21 is unavailable`() {
        val resolution = GuestJdkResolver.parse(
            probe(
                packages = mapOf(
                    "default-jdk-headless" to "21.0.8+9-Debian-1",
                    "openjdk-21-jdk-headless" to "(none)",
                    "openjdk-17-jdk-headless" to "(none)",
                ),
            ),
            "",
            0,
        )
        assertEquals("default-jdk-headless", resolution.packageName)
    }

    @Test
    fun `bookworm prefers openjdk 17`() {
        val resolution = GuestJdkResolver.parse(
            probe(
                version = "12",
                codename = "bookworm",
                packages = mapOf(
                    "default-jdk-headless" to "17.0.15+6",
                    "openjdk-21-jdk-headless" to "(none)",
                    "openjdk-17-jdk-headless" to "17.0.15+6",
                ),
            ),
            "",
            0,
        )
        assertEquals("openjdk-17-jdk-headless", resolution.packageName)
    }

    @Test
    fun `no available candidate is a stale repository diagnostic not a blind package`() {
        val resolution = GuestJdkResolver.parse(
            probe(
                packages = mapOf(
                    "default-jdk-headless" to "(none)",
                    "openjdk-21-jdk-headless" to "(none)",
                    "openjdk-17-jdk-headless" to "(none)",
                ),
            ),
            "",
            0,
        )
        assertNull(resolution.packageName)
        assertFalse(resolution.resolved)
        assertTrue(resolution.diagnostic.contains("may be stale"))
        assertTrue(resolution.diagnostic.contains("apt-get update"))
        assertTrue(resolution.diagnostic.contains("openjdk-21-jdk-headless=(none)"))
    }

    @Test
    fun `missing apt sources are reported explicitly`() {
        val resolution = GuestJdkResolver.parse(probe(sources = 0), "", 0)
        assertNull(resolution.packageName)
        assertTrue(resolution.diagnostic.contains("No APT deb sources"))
        assertTrue(resolution.diagnostic.contains("apt-get update"))
    }

    @Test
    fun `candidate order is distro aware`() {
        assertEquals(
            listOf("openjdk-21-jdk-headless", "default-jdk-headless", "openjdk-17-jdk-headless"),
            GuestJdkResolver.candidateOrder("debian", "13", "trixie"),
        )
        assertEquals(
            listOf("openjdk-17-jdk-headless", "default-jdk-headless", "openjdk-21-jdk-headless"),
            GuestJdkResolver.candidateOrder("debian", "12", "bookworm"),
        )
    }
}

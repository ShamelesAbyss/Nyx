package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Exercises the exact shell fragment used by the guest probe against temporary APT layouts.
 * The fragment is read-only and is the part that previously missed Debian's Deb822 format.
 */
class GuestJdkResolverAptSourcesTest {
    private fun writeSources(root: Path, name: String, content: String) {
        val directory = root.resolve("sources.list.d")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(name), content)
    }

    private fun count(root: Path): Int {
        val script = GuestJdkResolver.aptSourceCountScript(root.toString()) +
            "\nprintf 'NYX_SOURCE_COUNT=%s\\n' \"${'$'}sources\"\n"
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(10, TimeUnit.SECONDS)) { "APT source scanner timed out" }
        return Regex("NYX_SOURCE_COUNT=(\\d+)").find(output)?.groupValues?.get(1)?.toInt()
            ?: error("APT source scanner produced no count: $output")
    }

    @Test
    fun `valid deb822 sources count enabled binary repositories`() {
        val root = Files.createTempDirectory("nyx-apt-deb822-")
        try {
            writeSources(
                root,
                "debian.sources",
                """
                Types: deb
                URIs: http://deb.debian.org/debian
                Suites: trixie trixie-updates
                Components: main
                Enabled: yes

                Types: deb
                URIs: http://security.debian.org/debian-security
                Suites: trixie-security
                Components: main
                """.trimIndent(),
            )
            assertEquals(2, count(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disabled deb822 stanza is not counted`() {
        val root = Files.createTempDirectory("nyx-apt-disabled-")
        try {
            writeSources(
                root,
                "debian.sources",
                """
                Types: deb
                URIs: http://deb.debian.org/debian
                Suites: trixie
                Components: main
                Enabled: no
                """.trimIndent(),
            )
            assertEquals(0, count(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deb822 source stanza without deb type is not counted`() {
        val root = Files.createTempDirectory("nyx-apt-source-only-")
        try {
            writeSources(
                root,
                "debian.sources",
                """
                Types: deb-src
                URIs: http://deb.debian.org/debian
                Suites: trixie
                Components: main
                """.trimIndent(),
            )
            assertEquals(0, count(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `traditional list counts only uncommented deb lines`() {
        val root = Files.createTempDirectory("nyx-apt-list-")
        try {
            Files.writeString(
                root.resolve("sources.list"),
                """
                # deb http://disabled.example/debian trixie main
                deb http://deb.debian.org/debian trixie main
                deb-src http://deb.debian.org/debian trixie main
                """.trimIndent(),
            )
            assertEquals(1, count(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing apt files produce a zero source count`() {
        val root = Files.createTempDirectory("nyx-apt-missing-")
        try {
            assertEquals(0, count(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

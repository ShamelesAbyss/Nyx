package com.nyx.ai.ollama

import com.nyx.ai.terminal.CommandPolicy
import com.nyx.ai.terminal.HostShell
import com.nyx.ai.terminal.TerminalEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaProcessTest {

    private val saved = SavedOllamaProcess(pid = 4321, startTimeTicks = 987_654_321L, startedAtMillis = 1_700_000_000_000L)

    @Test
    fun `guest detection keeps origin architecture and compatibility evidence`() {
        val guest = OllamaTarget.guest("debian")!!
        val detection = OllamaProcess.parseDetection(
            """
                NYX_OLLAMA_INSTALLED=1
                NYX_OLLAMA_PATH=/usr/local/bin/ollama
                NYX_OLLAMA_ARCH=aarch64
                NYX_OLLAMA_VERSION=0.12.3
                NYX_OLLAMA_COMPATIBLE=1
            """.trimIndent(),
            guest,
        )

        assertTrue(detection.installed)
        assertTrue(detection.compatible)
        assertEquals(guest, detection.target)
        assertEquals("aarch64", detection.architecture)
        assertEquals("0.12.3", detection.version)
    }

    @Test
    fun `portable start path uses nohup and still refuses duplicate processes`() {
        val script = OllamaProcess.startScript("/usr/local/bin/ollama", useSetsid = false)

        assertTrue(script.contains("nohup sh -c"))
        assertTrue(script.contains("if false; then"))
        assertTrue(script.contains("pid_file"))
        assertTrue(script.contains("NYX_OLLAMA_START=0"))
        assertTrue(script.contains("the detached Ollama process did not report a usable PID"))
    }

    @Test
    fun `detection script reports architecture and compatibility explicitly`() {
        val script = OllamaProcess.detectScript()

        assertTrue(script.contains("NYX_OLLAMA_ARCH"))
        assertTrue(script.contains("NYX_OLLAMA_COMPATIBLE"))
        assertTrue(script.contains("command -v ollama"))
        assertFalse(script.contains("proot-distro login"))
    }

    /** Builds a `/proc/<pid>/stat` line whose field 22 is [startTicks]. */
    private fun statWithStart(startTicks: Long, comm: String = "ollama"): String {
        // Field 3 onwards; the twentieth token after `)` is field 22 (starttime).
        val tail = listOf(
            "S", "1", "4321", "4321", "0", "-1", "4194560",
            "1000", "0", "0", "0", "1", "2", "0", "0", "20", "0", "1", "0",
        ).joinToString(" ")
        return "4321 ($comm) $tail $startTicks 1000000 200 0"
    }

    // ------------------------------------------------------------------ host isolation

    @Test
    fun `every generated script is dispatched through the isolated host profile`() {
        val scripts = mapOf(
            "detect" to OllamaProcess.detectScript(),
            "start" to OllamaProcess.startScript("/data/data/com.termux/files/usr/bin/ollama"),
            "stop" to OllamaProcess.stopScript(saved),
            "state" to OllamaProcess.stateScript(),
        )
        for ((name, script) in scripts) {
            val request = HostShell.isolatedRequest(script)
            assertEquals("$name must target the Termux host", TerminalEnvironment.TERMUX_HOST, request.environment)
            assertEquals("$name must sanitize before starting bash", "${HostShell.TERMUX_PREFIX}/bin/env", request.commandPath)
            assertEquals("-i", request.arguments.first())
            assertTrue(request.arguments.contains("${HostShell.TERMUX_PREFIX}/bin/bash"))
            assertEquals("$name must pass the script as one argv element", "-c", request.arguments[request.arguments.lastIndex - 1])
            assertTrue("$name must disable profile loading", request.arguments.contains("--noprofile"))
            assertTrue("$name must disable rc files", request.arguments.contains("--norc"))
            assertFalse("$name must never use a login shell", request.arguments.contains("-l"))
            assertFalse("$name must never use -lc", request.arguments.contains("-lc"))
            assertTrue("$name must not inherit startup environment", request.arguments.none { it.startsWith("BASH_ENV=") || it.startsWith("ENV=") })
            assertFalse("$name must never enter a PRoot guest", script.contains("proot-distro login"))
            assertTrue("$name must stay under the policy limit", script.length < CommandPolicy.MAX_COMMAND_CHARS)
        }
    }

    @Test
    fun `every generated script is allowed by the terminal command policy`() {
        val scripts = listOf(
            OllamaProcess.detectScript(),
            OllamaProcess.startScript("/data/data/com.termux/files/usr/bin/ollama"),
            OllamaProcess.stopScript(saved),
            OllamaProcess.stateScript(),
        )
        for (script in scripts) {
            val assessment = CommandPolicy.assess(script)
            assertFalse(
                "Script was blocked: ${assessment.reasons.joinToString()}\n$script",
                assessment.blocked,
            )
        }
    }

    @Test
    fun `no script performs a broad or privileged kill`() {
        val scripts = listOf(
            OllamaProcess.startScript("/data/data/com.termux/files/usr/bin/ollama"),
            OllamaProcess.stopScript(saved),
            OllamaProcess.stateScript(),
        )
        for (script in scripts) {
            assertFalse(script.contains("pkill"))
            assertFalse(script.contains("killall"))
            assertFalse(script.contains("kill -9 -1"))
            assertFalse(script.contains("sudo"))
            assertFalse(Regex("""kill\s+-(TERM|KILL)\s+(-\d+|0|1)(\s|$)""").containsMatchIn(script))
        }
    }

    // ------------------------------------------------------------------ quoting

    @Test
    fun `shell quoting survives embedded single quotes`() {
        assertEquals("'plain'", OllamaProcess.quote("plain"))
        assertEquals("'it'\\''s'", OllamaProcess.quote("it's"))
        assertEquals("''", OllamaProcess.quote(""))
        val binary = "/data/data/com.termux/files/usr/bin/ollama'; touch /oops; '"
        val script = OllamaProcess.startScript(binary)
        assertTrue(script.contains("ollama_bin='"))
        // The injected single quotes are escaped, so no unquoted `touch` command exists.
        assertTrue(script.contains("'\\''"))
        assertFalse(script.lines().any { it.trim().startsWith("touch") })
    }

    // ------------------------------------------------------------------ PID parsing

    @Test
    fun `pid parsing accepts only plausible positive process ids`() {
        assertEquals(4321, OllamaProcess.parsePid("NYX_OLLAMA_PID=4321\n"))
        assertEquals(4321, OllamaProcess.parsePid("noise\nNYX_OLLAMA_PID= 4321 \nmore\n"))
        assertEquals(4321, OllamaProcess.parsePid("4321\n"))
        assertEquals(4194303, OllamaProcess.parsePid("NYX_OLLAMA_PID=4194303"))
        assertNull(OllamaProcess.parsePid(""))
        assertNull(OllamaProcess.parsePid("NYX_OLLAMA_PID=0"))
        assertNull(OllamaProcess.parsePid("NYX_OLLAMA_PID=1"))
        assertNull(OllamaProcess.parsePid("NYX_OLLAMA_PID=-5"))
        assertNull(OllamaProcess.parsePid("NYX_OLLAMA_PID=abc"))
        assertNull(OllamaProcess.parsePid("NYX_OLLAMA_PID=99999999"))
        assertNull(OllamaProcess.parsePid("no pid here"))
    }

    @Test
    fun `pid record round trips and rejects foreign or corrupt records`() {
        val encoded = OllamaProcess.encodeRecord(saved)
        assertEquals("NYX1 4321 987654321 1700000000000", encoded)
        assertEquals(saved, OllamaProcess.decodeRecord(encoded))
        assertEquals(SavedOllamaProcess(4321, 987_654_321L, 0L), OllamaProcess.decodeRecord("NYX1 4321 987654321"))
        assertEquals(saved, OllamaProcess.decodeRecord("  nyx1   4321   987654321   1700000000000  "))

        assertNull(OllamaProcess.decodeRecord(null))
        assertNull(OllamaProcess.decodeRecord(""))
        assertNull(OllamaProcess.decodeRecord("NYX2 4321 987654321 1"))
        assertNull(OllamaProcess.decodeRecord("4321 987654321"))
        assertNull(OllamaProcess.decodeRecord("NYX1 0 987654321 1"))
        assertNull(OllamaProcess.decodeRecord("NYX1 1 987654321 1"))
        assertNull(OllamaProcess.decodeRecord("NYX1 4321 -1 1"))
        assertNull(OllamaProcess.decodeRecord("NYX1 4321 abc 1"))
        assertNull(OllamaProcess.decodeRecord("NYX1 4321 987654321 nope"))
    }

    // ------------------------------------------------------------------ /proc parsing

    @Test
    fun `start token parsing handles parentheses and spaces in comm`() {
        assertEquals(987_654_321L, OllamaProcess.parseStartTimeFromStat(statWithStart(987_654_321L)))
        assertEquals(987_654_321L, OllamaProcess.parseStartTimeFromStat(statWithStart(987_654_321L, "ollama serve")))
        assertEquals(987_654_321L, OllamaProcess.parseStartTimeFromStat(statWithStart(987_654_321L, "odd)name")))
        assertNull(OllamaProcess.parseStartTimeFromStat(""))
        assertNull(OllamaProcess.parseStartTimeFromStat("4321 ollama S 1 2 3"))
        assertNull(OllamaProcess.parseStartTimeFromStat("4321 (ollama) S 1 2"))
        assertNull(OllamaProcess.parseStartTimeFromStat("4321 (ollama) " + "S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 nope"))
    }

    @Test
    fun `cmdline ownership requires the ollama binary and the serve subcommand`() {
        assertEquals(
            listOf("/data/data/com.termux/files/usr/bin/ollama", "serve"),
            OllamaProcess.parseCmdline("/data/data/com.termux/files/usr/bin/ollama\u0000serve\u0000"),
        )
        assertTrue(OllamaProcess.isOllamaServeCmdline("/data/data/com.termux/files/usr/bin/ollama\u0000serve\u0000"))
        assertTrue(OllamaProcess.isOllamaServeCmdline("ollama\u0000serve\u0000--port\u000011434\u0000"))
        assertFalse(OllamaProcess.isOllamaServeCmdline("ollama\u0000run\u0000llama3\u0000"))
        assertFalse(OllamaProcess.isOllamaServeCmdline("bash\u0000-c\u0000ollama serve\u0000"))
        assertFalse(OllamaProcess.isOllamaServeCmdline("/bin/sh\u0000serve\u0000"))
        assertFalse(OllamaProcess.isOllamaServeCmdline("ollama\u0000"))
        assertFalse(OllamaProcess.isOllamaServeCmdline(""))
    }

    @Test
    fun `ownership distinguishes owned from a recycled pid`() {
        val cmdline = "/data/data/com.termux/files/usr/bin/ollama\u0000serve\u0000"
        assertEquals(
            OllamaOwnership.OWNED,
            OllamaProcess.ownership(saved, cmdline, statWithStart(987_654_321L)),
        )
        assertEquals(
            OllamaOwnership.PID_REUSED,
            OllamaProcess.ownership(saved, cmdline, statWithStart(111_111_111L)),
        )
        assertEquals(
            OllamaOwnership.NOT_OLLAMA,
            OllamaProcess.ownership(saved, "/system/bin/sh\u0000serve\u0000", statWithStart(987_654_321L)),
        )
        assertEquals(OllamaOwnership.MISSING, OllamaProcess.ownership(saved, "", ""))
        assertEquals(OllamaOwnership.UNKNOWN, OllamaProcess.ownership(null, cmdline, statWithStart(987_654_321L)))
        assertEquals(OllamaOwnership.UNKNOWN, OllamaProcess.ownership(saved, cmdline, "garbage"))
    }

    // ------------------------------------------------------------------ start script

    @Test
    fun `start script detaches with closed stdin and a real pid record`() {
        val script = OllamaProcess.startScript("/data/data/com.termux/files/usr/bin/ollama")
        assertTrue(script.contains("command -v setsid"))
        assertTrue(script.contains("setsid sh -c"))
        assertTrue(script.contains("nohup sh -c"))
        assertTrue(script.contains("< /dev/null"))
        assertTrue(script.contains("2>&1"))
        assertTrue(script.contains("mkdir -p"))
        assertTrue(script.contains("ollama.pid.tmp"))
        assertTrue(script.contains("printf 'NYX1 %s %s %s\\n'"))
        assertTrue(script.contains("NYX_OLLAMA_START=1"))
        assertTrue(script.contains("NYX_OLLAMA_START_TICKS=%s"))
        assertFalse(script.contains("--now"))
        assertFalse(script.contains("systemctl"))
        assertTrue("stdin must be closed for the detached child", script.contains("< /dev/null"))
        assertTrue("the child must exec ollama under the recorded pid", script.contains("exec \"\$0\" serve"))
    }

    @Test
    fun `start script refuses a verified live duplicate before launching`() {
        val script = OllamaProcess.startScript("/data/data/com.termux/files/usr/bin/ollama")
        assertTrue(script.contains("NYX_OLLAMA_DUPLICATE=1"))
        assertTrue(script.contains("saved_now"))
        assertTrue(script.contains("saved_start"))
        // The duplicate check must precede the launch.
        assertTrue(script.indexOf("NYX_OLLAMA_DUPLICATE=1") < script.indexOf("setsid sh -c"))
        assertTrue(script.contains("a verified NYX-owned Ollama process is already running"))
    }

    @Test
    fun `start outcome parsing distinguishes success, duplicate, and failure`() {
        val success = OllamaProcess.parseStartOutcome(
            "NYX_OLLAMA_START=1\nNYX_OLLAMA_PID=777\nNYX_OLLAMA_START_TICKS=123456\n",
        )
        assertTrue(success.started)
        assertEquals(777, success.pid)
        assertEquals(123_456L, success.startTimeTicks)
        assertFalse(success.duplicate)

        val duplicate = OllamaProcess.parseStartOutcome(
            "NYX_OLLAMA_START=0\nNYX_OLLAMA_DUPLICATE=1\nNYX_OLLAMA_PID=777\nNYX_OLLAMA_START_TICKS=123456\nNYX_OLLAMA_ERROR=busy\n",
        )
        assertFalse(duplicate.started)
        assertTrue(duplicate.duplicate)
        assertEquals(777, duplicate.pid)
        assertEquals("busy", duplicate.error)

        val failure = OllamaProcess.parseStartOutcome("NYX_OLLAMA_START=0\nNYX_OLLAMA_ERROR=no binary\n")
        assertFalse(failure.started)
        assertNull(failure.pid)
        assertEquals("no binary", failure.error)
    }

    // ------------------------------------------------------------------ stop script

    @Test
    fun `stop script signals only the verified literal pid`() {
        val script = OllamaProcess.stopScript(saved)
        assertTrue(script.contains("expected_pid=4321"))
        assertTrue(script.contains("expected_start=987654321"))
        assertTrue(script.contains("kill -TERM \"\$expected_pid\""))
        assertTrue(script.contains("kill -KILL \"\$expected_pid\""))
        assertTrue(script.contains("/proc/\$expected_pid/cmdline"))
        assertTrue(script.contains("/proc/\$expected_pid/stat"))
        assertTrue(script.contains("the PID was reused by another process"))
        assertTrue(script.contains(": > \"\$pid_file\""))
        assertTrue(script.contains("NYX_OLLAMA_STOPPED=1"))
        assertFalse(script.contains("\$!"))
        assertFalse(script.contains("pgrep"))
        assertFalse(script.contains("pidof"))
        assertFalse(script.contains("-TERM -"))
        assertFalse(script.contains("-KILL -"))
        assertFalse(Regex("""kill\s+-[A-Z]+\s+\$""").containsMatchIn(script))
    }

    @Test
    fun `stop script refuses implausible process ids`() {
        assertThrows(IllegalArgumentException::class.java) { OllamaProcess.stopScript(SavedOllamaProcess(0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { OllamaProcess.stopScript(SavedOllamaProcess(1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { OllamaProcess.stopScript(SavedOllamaProcess(-4, 1)) }
        assertThrows(IllegalArgumentException::class.java) { OllamaProcess.stopScript(SavedOllamaProcess(4_200_000, 1)) }
        assertThrows(IllegalArgumentException::class.java) { OllamaProcess.stopScript(SavedOllamaProcess(4321, -1)) }
    }

    @Test
    fun `stop outcome parsing reports refusal and force kill`() {
        val refused = OllamaProcess.parseStopOutcome("NYX_OLLAMA_STOP_REFUSED=1\nNYX_OLLAMA_STOP_REASON=pid reused\n")
        assertTrue(refused.refused)
        assertFalse(refused.stopped)
        assertEquals("pid reused", refused.reason)

        val killed = OllamaProcess.parseStopOutcome(
            "NYX_OLLAMA_STOPPED=1\nNYX_OLLAMA_KILLED=1\nNYX_OLLAMA_STOP_REASON=ignored TERM\n",
        )
        assertTrue(killed.stopped)
        assertTrue(killed.killed)
        assertEquals("ignored TERM", killed.reason)
    }

    // ------------------------------------------------------------------ state inspection

    @Test
    fun `state snapshot parses ownership, resources, and the log tail`() {
        val record = SavedOllamaProcess(pid = 4321, startTimeTicks = 50_000L, startedAtMillis = 1_700_000_000_000L)
        val stdout = """
            NYX_OLLAMA_RECORD_BEGIN
            NYX1 4321 50000 1700000000000
            NYX_OLLAMA_RECORD_END
            NYX_OLLAMA_ALIVE=1
            NYX_OLLAMA_CMDLINE=/data/data/com.termux/files/usr/bin/ollama serve
            NYX_OLLAMA_START=50000
            NYX_OLLAMA_OWNED=1
            NYX_OLLAMA_RSS_KB=524288
            NYX_OLLAMA_UPTIME_SECONDS=1000.0
            NYX_OLLAMA_LOG_BEGIN
            listening on 127.0.0.1:11434
            NYX_OLLAMA_LOG_END
        """.trimIndent()
        val snapshot = OllamaProcess.parseHostSnapshot(stdout)
        assertEquals(record, snapshot.record)
        assertEquals(OllamaOwnership.OWNED, snapshot.ownership)
        assertEquals(524_288L, snapshot.rssKb)
        // 1000 s of host uptime minus a process started at 500 s of uptime.
        assertEquals(500_000L, snapshot.uptimeMillis)
        assertEquals("listening on 127.0.0.1:11434", snapshot.logTail)
    }

    @Test
    fun `state snapshot rejects a reused pid and reports a missing process`() {
        val reused = """
            NYX_OLLAMA_RECORD_BEGIN
            NYX1 4321 987654321 1
            NYX_OLLAMA_RECORD_END
            NYX_OLLAMA_ALIVE=1
            NYX_OLLAMA_CMDLINE=/usr/bin/ollama serve
            NYX_OLLAMA_START=111111111
            NYX_OLLAMA_LOG_BEGIN
            NYX_OLLAMA_LOG_END
        """.trimIndent()
        assertEquals(OllamaOwnership.PID_REUSED, OllamaProcess.parseHostSnapshot(reused).ownership)

        val missing = """
            NYX_OLLAMA_RECORD_BEGIN
            NYX1 4321 987654321 1
            NYX_OLLAMA_RECORD_END
            NYX_OLLAMA_ALIVE=0
            NYX_OLLAMA_LOG_BEGIN
            old line
            NYX_OLLAMA_LOG_END
        """.trimIndent()
        val snapshot = OllamaProcess.parseHostSnapshot(missing)
        assertEquals(OllamaOwnership.MISSING, snapshot.ownership)
        assertEquals("old line", snapshot.logTail)

        val empty = OllamaProcess.parseHostSnapshot("nothing useful")
        assertNull(empty.record)
        assertEquals(OllamaOwnership.UNKNOWN, empty.ownership)
        assertEquals("", empty.logTail)
    }

    @Test
    fun `uptime is derived from system uptime and the start token`() {
        assertEquals(500_000L, OllamaProcess.computeUptimeMillis(1_000.0, 50_000L))
        assertEquals(0L, OllamaProcess.computeUptimeMillis(500.0, 50_000L))
        assertNull(OllamaProcess.computeUptimeMillis(null, 50_000L))
        assertNull(OllamaProcess.computeUptimeMillis(1_000.0, null))
        assertNull(OllamaProcess.computeUptimeMillis(Double.NaN, 50_000L))
        assertNull(OllamaProcess.computeUptimeMillis(1_000.0, 50_000L, 0L))
    }

    // ------------------------------------------------------------------ misc parsing

    @Test
    fun `log tails are bounded and stripped of control characters`() {
        val noisy = (1..100).joinToString("\n") { "line $it\u0007" }
        val tail = OllamaProcess.sanitizeLogTail(noisy)
        assertEquals(40, tail.lines().size)
        assertTrue(tail.startsWith("line 61"))
        assertTrue(tail.endsWith("line 100"))
        assertFalse(tail.contains('\u0007'))

        val long = "x".repeat(5_000)
        val capped = OllamaProcess.sanitizeLogTail(long, maxChars = 500)
        assertEquals(500, capped.length)
        assertTrue(capped.startsWith("…"))

        assertEquals("", OllamaProcess.sanitizeLogTail("   \n\t\n"))
        assertEquals("a", OllamaProcess.sanitizeLogTail("a", maxLines = 0))
    }

    @Test
    fun `elapsed ps time is parsed in every documented shape`() {
        assertEquals(184_000L, OllamaProcess.parseElapsedMillis("03:04"))
        assertEquals(3_723_000L, OllamaProcess.parseElapsedMillis("01:02:03"))
        assertEquals(183_845_000L, OllamaProcess.parseElapsedMillis("2-03:04:05"))
        assertNull(OllamaProcess.parseElapsedMillis(""))
        assertNull(OllamaProcess.parseElapsedMillis("nope"))
        assertNull(OllamaProcess.parseElapsedMillis("01:99"))
    }

    @Test
    fun `detection parsing requires an absolute binary path`() {
        val installed = OllamaProcess.parseDetection(
            "NYX_OLLAMA_INSTALLED=1\nNYX_OLLAMA_PATH=/data/data/com.termux/files/usr/bin/ollama\nNYX_OLLAMA_VERSION=ollama version 0.5.7\n",
        )
        assertTrue(installed.installed)
        assertEquals("/data/data/com.termux/files/usr/bin/ollama", installed.path)
        assertEquals("ollama version 0.5.7", installed.version)

        val missing = OllamaProcess.parseDetection("NYX_OLLAMA_INSTALLED=0\n")
        assertFalse(missing.installed)
        assertNull(missing.path)

        val relative = OllamaProcess.parseDetection("NYX_OLLAMA_INSTALLED=1\nNYX_OLLAMA_PATH=ollama\n")
        assertNull(relative.path)
    }

    @Test
    fun `endpoints are normalized to a validated http url`() {
        assertEquals(OllamaProcess.DEFAULT_ENDPOINT, OllamaProcess.normalizeEndpoint(null))
        assertEquals(OllamaProcess.DEFAULT_ENDPOINT, OllamaProcess.normalizeEndpoint("   "))
        assertEquals(OllamaProcess.DEFAULT_ENDPOINT, OllamaProcess.normalizeEndpoint("file:///etc/passwd"))
        assertEquals(OllamaProcess.DEFAULT_ENDPOINT, OllamaProcess.normalizeEndpoint("not a url"))
        assertEquals("http://127.0.0.1:11434", OllamaProcess.normalizeEndpoint("http://127.0.0.1:11434/"))
        assertEquals("https://gpu.example.test", OllamaProcess.normalizeEndpoint(" https://gpu.example.test/ "))

        assertTrue(OllamaProcess.isLoopbackEndpoint("http://127.0.0.1:11434"))
        assertTrue(OllamaProcess.isLoopbackEndpoint("http://localhost:11434"))
        assertTrue(OllamaProcess.isLoopbackEndpoint("http://[::1]:11434"))
        assertFalse(OllamaProcess.isLoopbackEndpoint("http://192.168.1.20:11434"))
        assertFalse(OllamaProcess.isLoopbackEndpoint("https://ollama.example.com"))
        assertFalse(OllamaProcess.isLoopbackEndpoint(""))
    }

    @Test
    fun `marker and block helpers do not leak surrounding text`() {
        val text = "a\nKEY=value\nKEY=second\nBEGIN\nx\ny\nEND\nz"
        assertEquals("value", OllamaProcess.markerValue(text, "KEY"))
        assertNull(OllamaProcess.markerValue(text, "MISSING"))
        assertEquals("x\ny", OllamaProcess.block(text, "BEGIN", "END"))
        assertEquals("", OllamaProcess.block(text, "BEGIN", "ABSENT"))
        assertEquals("", OllamaProcess.block(text, "ABSENT", "END"))
    }
}

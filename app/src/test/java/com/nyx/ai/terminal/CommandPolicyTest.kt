package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {

    @Test
    fun `read only commands run without confirmation`() {
        val assessment = CommandPolicy.assess("ls -la ~/project")
        assertEquals(CommandRisk.READ_ONLY, assessment.risk)
        assertFalse(assessment.requiresConfirmation)
        assertFalse(assessment.blocked)
    }

    @Test
    fun `unknown executable requires confirmation`() {
        val assessment = CommandPolicy.assess("mytool --flag")
        assertTrue(assessment.requiresConfirmation)
        assertFalse(assessment.blocked)
    }

    @Test
    fun `root escalation is blocked`() {
        val assessment = CommandPolicy.assess("sudo rm -rf /data/local/tmp/x")
        assertTrue(assessment.blocked)
        assertEquals(CommandRisk.BLOCKED, assessment.risk)
        assertTrue(assessment.reasons.any { it.contains("privilege") })
    }

    @Test
    fun `su is blocked even without arguments`() {
        assertTrue(CommandPolicy.assess("su").blocked)
    }

    @Test
    fun `recursive delete of home is destructive`() {
        val assessment = CommandPolicy.assess("rm -rf ~/")
        assertTrue(assessment.requiresConfirmation)
        assertEquals(CommandRisk.DESTRUCTIVE, assessment.risk)
        assertFalse(assessment.blocked)
    }

    @Test
    fun `git reset hard is destructive`() {
        val assessment = CommandPolicy.assess("git reset --hard HEAD~3")
        assertEquals(CommandRisk.DESTRUCTIVE, assessment.risk)
        assertTrue(assessment.requiresConfirmation)
    }

    @Test
    fun `git status remains read only`() {
        val assessment = CommandPolicy.assess("git status --short")
        assertEquals(CommandRisk.READ_ONLY, assessment.risk)
        assertFalse(assessment.requiresConfirmation)
    }

    @Test
    fun `package installation is network mutating`() {
        val assessment = CommandPolicy.assess("pkg install python")
        assertEquals(CommandRisk.NETWORK_MUTATING, assessment.risk)
        assertTrue(assessment.requiresConfirmation)
    }

    @Test
    fun `android protected settings are blocked`() {
        assertTrue(CommandPolicy.assess("settings put global adb_enabled 1").blocked)
        assertTrue(CommandPolicy.assess("pm grant com.example android.permission.CAMERA").blocked)
    }

    @Test
    fun `chained segments are each assessed`() {
        val assessment = CommandPolicy.assess("ls && rm -rf build")
        assertTrue(assessment.requiresConfirmation)
    }

    @Test
    fun `empty command is rejected`() {
        assertTrue(CommandPolicy.assess("   ").blocked)
    }

    @Test
    fun `overlong command is rejected`() {
        assertTrue(CommandPolicy.assess("a".repeat(CommandPolicy.MAX_COMMAND_CHARS + 1)).blocked)
    }

    @Test
    fun `tokenizer respects quotes`() {
        val tokens = CommandPolicy.tokenize("grep 'hello world' file.txt")
        assertEquals(listOf("grep", "hello world", "file.txt"), tokens)
    }

    @Test
    fun `working directory validation rejects traversal`() {
        assertTrue(ShellCommandBuilder.isValidWorkingDirectory("~/projects/app"))
        assertTrue(ShellCommandBuilder.isValidWorkingDirectory("/data/data/com.termux/files/home/app"))
        assertFalse(ShellCommandBuilder.isValidWorkingDirectory("~/projects/../../etc"))
        assertFalse(ShellCommandBuilder.isValidWorkingDirectory("relative/path"))
        assertFalse(ShellCommandBuilder.isValidWorkingDirectory("~/app; rm -rf /"))
    }

    @Test
    fun `shell quoting escapes single quotes`() {
        assertEquals("'it'\\''s'", ShellCommandBuilder.quote("it's"))
    }

    @Test
    fun `timeout wrapper uses coreutils and preserves the command`() {
        val wrapped = ShellCommandBuilder.wrap("echo hi", null, 30, useTimeoutBinary = true)
        assertTrue(wrapped.startsWith("timeout -k 5 30 bash -lc "))
        assertTrue(wrapped.contains("echo hi"))
    }

    @Test
    fun `working directory is quoted independently of the command`() {
        val wrapped = ShellCommandBuilder.wrap("ls", "~/my project", null, useTimeoutBinary = false)
        assertEquals("cd -- '~/my project' && ls", wrapped)
    }

    @Test
    fun `host request targets the termux shell with a bounded timeout`() {
        val request = ShellCommandBuilder.hostRequest("ls", null, 45, useTimeoutBinary = false)
        assertEquals(TerminalEnvironment.TERMUX_HOST, request.environment)
        assertEquals(ShellCommandBuilder.TERMUX_SHELL, request.commandPath)
        assertEquals(listOf("-lc", "ls"), request.arguments)
        assertTrue(request.authorized)
        assertEquals(45_000L, request.timeoutMillis)
    }
}

package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCommandPolicyTest {
    private val root = "/home/nyx/project"

    private fun assess(
        command: String,
        workingDirectory: String? = null,
        timeout: Long? = null,
    ) = ModelCommandPolicy.assess(
        ModelCommandRequest(
            command = command,
            workspaceRoot = root,
            workingDirectory = workingDirectory,
            timeoutSeconds = timeout,
            environment = TerminalEnvironment.DEBIAN_PROOT,
        ),
    )

    @Test
    fun `read only command in a relative workspace subdirectory is allowed`() {
        val decision = assess("ls -la", "src/main")
        assertTrue(decision.reason, decision.allowed)
        assertEquals("$root/src/main", decision.workingDirectory)
        assertEquals(CommandRisk.READ_ONLY, decision.risk)
        assertFalse(decision.requiresApproval)
    }

    @Test
    fun `working directory cannot escape the approved workspace`() {
        assertFalse(assess("ls", "/tmp").allowed)
        assertFalse(assess("ls", "../outside").allowed)
        assertFalse(assess("ls", "~/other").allowed)
        assertFalse(assess("ls", "$root/../outside").allowed)
    }

    @Test
    fun `ordinary mutating command is denied outside the structured reader catalog`() {
        val decision = assess("mkdir -p build", ".")
        assertFalse(decision.reason, decision.allowed)
        assertEquals(CommandRisk.BLOCKED, decision.risk)
        assertTrue(decision.reason.contains("Unsupported executable"))
    }

    @Test
    fun `destructive command is denied and marked for human approval`() {
        val decision = assess("rm -rf build")
        assertFalse(decision.allowed)
        assertEquals(CommandRisk.DESTRUCTIVE, decision.risk)
        assertTrue(decision.requiresApproval)
        assertTrue(decision.reason.contains("explicit human approval"))
    }

    @Test
    fun `git force push is denied as destructive`() {
        assertFalse(assess("git push --force origin main").allowed)
        assertFalse(assess("git reset --hard HEAD~1").allowed)
    }

    @Test
    fun `publishing commands require explicit human approval`() {
        for (command in listOf("git push origin main", "gh release create v1", "npm publish", "twine upload dist/*")) {
            val decision = assess(command)
            assertFalse(command, decision.allowed)
            assertTrue(command, decision.requiresApproval)
        }
    }

    @Test
    fun `credential and private data paths are denied`() {
        assertFalse(assess("cat .env").allowed)
        assertFalse(assess("cat ~/.ssh/id_rsa").allowed)
        assertFalse(assess("cat /etc/shadow").allowed)
        assertFalse(assess("cat /data/data/com.example/files/token").allowed)
    }

    @Test
    fun `root escalation is blocked outright`() {
        val decision = assess("sudo apt-get install git")
        assertFalse(decision.allowed)
        assertEquals(CommandRisk.BLOCKED, decision.risk)
    }

    @Test
    fun `timeout is bounded to the supported maximum and minimum`() {
        assertEquals(ModelCommandPolicy.MAX_TIMEOUT_SECONDS, assess("ls", timeout = 9_999).timeoutSeconds)
        assertEquals(1L, assess("ls", timeout = -4).timeoutSeconds)
        assertEquals(ModelCommandPolicy.DEFAULT_TIMEOUT_SECONDS, assess("ls").timeoutSeconds)
    }

    @Test
    fun `relative traversal is rejected before command assessment`() {
        assertFalse(assess("ls", "a/../../b").allowed)
        assertFalse(assess("ls", "a//b").allowed)
    }

    @Test
    fun `model output redirection is rejected even when the base command is read only`() {
        assertFalse(assess("echo changed > app/src/main/App.kt").allowed)
        assertFalse(assess("printf x >> build/out.txt").allowed)
        assertFalse(assess("cat < secrets.txt").allowed)
    }

    @Test
    fun `model command substitution and shell chaining are rejected`() {
        assertFalse(assess("cat \$(pwd)/file").allowed)
        assertFalse(assess("echo `id`").allowed)
        assertFalse(assess("ls | sh").allowed)
        assertFalse(assess("ls && rm -rf build").allowed)
        assertFalse(assess("ls; touch changed").allowed)
        assertFalse(assess("echo first\nrm -rf build").allowed)
    }

    @Test
    fun `indirect execution cannot bypass the policy`() {
        assertFalse(assess("sh -c 'rm -rf build'").allowed)
        assertFalse(assess("eval 'rm -rf build'").allowed)
        assertFalse(assess("find . -exec rm -rf {} \\;").allowed)
        assertFalse(assess("xargs rm -rf").allowed)
        assertFalse(assess("python3 -c 'import os'").allowed)
        assertFalse(assess("env rm -rf build").allowed)
        assertFalse(assess("timeout 5 rm -rf build").allowed)
        assertFalse(assess("bash script.sh").allowed)
    }

    @Test
    fun `quoted shell metacharacters remain ordinary arguments`() {
        assertTrue(assess("echo \"a > b\"").allowed)
        assertTrue(assess("echo '\$(not executed)'").allowed)
    }
}

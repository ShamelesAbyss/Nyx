package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceValidatorTest {

    private fun request(
        path: String,
        environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
        authorized: Boolean = true,
    ): WorkspaceRequest = WorkspaceRequest(environment, path, authorized)

    private fun assertInvalid(check: WorkspaceCheck, expectedCode: String) {
        assertTrue("expected Invalid but was $check", check is WorkspaceCheck.Invalid)
        val invalid = check as WorkspaceCheck.Invalid
        assertEquals(expectedCode, invalid.code)
        assertTrue("message must be actionable", invalid.message.isNotBlank())
        assertTrue("action must be present", invalid.action.isNotBlank())
        assertTrue("action must stay short", invalid.action.length <= 80)
    }

    private fun validPath(check: WorkspaceCheck): String {
        assertTrue("expected Valid but was $check", check is WorkspaceCheck.Valid)
        return (check as WorkspaceCheck.Valid).normalizedPath
    }

    @Test
    fun `absolute project paths are accepted on the host and in the guest`() {
        assertEquals("/home/user/project", validPath(WorkspaceValidator.validateSyntax(request("/home/user/project"))))
        assertEquals(
            "/root/projects/app",
            validPath(
                WorkspaceValidator.validateSyntax(
                    request("/root/projects/app", TerminalEnvironment.DEBIAN_PROOT),
                ),
            ),
        )
        assertEquals(
            "/data/data/com.termux/files/home/app",
            validPath(WorkspaceValidator.validateSyntax(request("/data/data/com.termux/files/home/app"))),
        )
    }

    @Test
    fun `spaces and allowed punctuation are accepted`() {
        val path = "/home/user/My Project_v2+beta@night"
        assertEquals(path, validPath(WorkspaceValidator.validateSyntax(request(path))))
    }

    @Test
    fun `relative and expanded paths are rejected`() {
        assertInvalid(WorkspaceValidator.validateSyntax(request("project")), "path_not_absolute")
        assertInvalid(WorkspaceValidator.validateSyntax(request("./project")), "path_not_absolute")
        assertInvalid(WorkspaceValidator.validateSyntax(request("~/project")), "path_not_absolute")
        assertInvalid(WorkspaceValidator.validateSyntax(request("\$HOME/project")), "path_not_absolute")
    }

    @Test
    fun `shell expansion inside an absolute path is rejected`() {
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/\$USER/project")), "path_invalid_characters")
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/user/project*")), "path_invalid_characters")
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/user/pro;rm -rf")), "path_invalid_characters")
    }

    @Test
    fun `parent references and redundant separators are rejected`() {
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/user/../etc")), "path_parent_reference")
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/user/app/..")), "path_parent_reference")
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home//user/app")), "path_redundant_separator")
    }

    @Test
    fun `trailing slashes and lengths are enforced`() {
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/user/app/")), "path_trailing_slash")
        assertInvalid(WorkspaceValidator.validateSyntax(request("/")), "path_too_broad")
        assertInvalid(WorkspaceValidator.validateSyntax(request("")), "path_not_absolute")
        val tooLong = "/" + "a".repeat(WorkspaceValidator.MAX_PATH_CHARS)
        assertInvalid(WorkspaceValidator.validateSyntax(request(tooLong)), "path_too_long")
    }

    @Test
    fun `filesystem roots are not project folders`() {
        for (root in listOf("/", "/root", "/data", "/system", "/sdcard")) {
            assertInvalid(WorkspaceValidator.validateSyntax(request(root)), "path_too_broad")
        }
    }

    @Test
    fun `nul bytes are rejected`() {
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/user/a\u0000b")), "path_contains_nul")
    }

    @Test
    fun `non ascii characters are rejected`() {
        assertInvalid(WorkspaceValidator.validateSyntax(request("/home/user/proj\u00e9ct")), "path_invalid_characters")
    }

    @Test
    fun `android shared storage is rejected inside the debian guest`() {
        val paths = listOf(
            "/sdcard/project",
            "/storage/emulated/0/project",
            "/data/data/com.termux/files/home/app",
            "/data/app/project",
            "/mnt/sdcard/project",
        )
        for (path in paths) {
            assertInvalid(
                WorkspaceValidator.validateSyntax(request(path, TerminalEnvironment.DEBIAN_PROOT)),
                "android_path_in_guest",
            )
        }
    }

    @Test
    fun `the guest rejection explains the separate filesystem`() {
        val check = WorkspaceValidator.validateSyntax(
            request("/sdcard/project", TerminalEnvironment.DEBIAN_PROOT),
        )
        val invalid = check as WorkspaceCheck.Invalid
        assertTrue(invalid.message.contains("guest"))
        assertTrue(invalid.action.contains("/root/projects"))
    }

    @Test
    fun `the host accepts absolute termux storage paths`() {
        assertEquals(
            "/sdcard/project",
            validPath(WorkspaceValidator.validateSyntax(request("/sdcard/project"))),
        )
        assertEquals(
            "/storage/emulated/0/project",
            validPath(WorkspaceValidator.validateSyntax(request("/storage/emulated/0/project"))),
        )
    }

    @Test
    fun `authorization is required before a path is usable`() {
        assertInvalid(
            WorkspaceValidator.validateSyntax(request("/home/user/project", authorized = false)),
            "not_authorized",
        )
    }

    @Test
    fun `probe reports a missing folder`() {
        val check = WorkspaceValidator.parseProbe(
            "NYX_WS_EXISTS=0\nNYX_WS_DIR=0\nNYX_WS_WRITABLE=0\nNYX_WS_REALPATH=\n",
            "",
            0,
        )
        assertInvalid(check, "missing")
    }

    @Test
    fun `probe reports a path that is not a directory`() {
        val check = WorkspaceValidator.parseProbe(
            "NYX_WS_EXISTS=1\nNYX_WS_DIR=0\nNYX_WS_WRITABLE=0\nNYX_WS_REALPATH=\n",
            "",
            0,
        )
        assertInvalid(check, "not_directory")
    }

    @Test
    fun `probe reports an unwritable folder`() {
        val check = WorkspaceValidator.parseProbe(
            "NYX_WS_EXISTS=1\nNYX_WS_DIR=1\nNYX_WS_WRITABLE=0\nNYX_WS_REALPATH=/home/user/project\n",
            "",
            0,
        )
        assertInvalid(check, "not_writable")
    }

    @Test
    fun `probe failure keeps a bounded diagnostic`() {
        val check = WorkspaceValidator.parseProbe(
            "",
            "proot-distro: command not found\nsecond line",
            127,
        )
        assertInvalid(check, "probe_failed")
        val invalid = check as WorkspaceCheck.Invalid
        assertTrue(invalid.message.contains("command not found"))
        assertTrue(invalid.message.lines().size == 1)
    }

    @Test
    fun `unknown exit codes are probe failures`() {
        assertInvalid(WorkspaceValidator.parseProbe("NYX_WS_EXISTS=1\n", "", null), "probe_failed")
        assertInvalid(WorkspaceValidator.parseProbe("", "", 1), "probe_failed")
    }

    @Test
    fun `a missing real path is a probe failure`() {
        val check = WorkspaceValidator.parseProbe(
            "NYX_WS_EXISTS=1\nNYX_WS_DIR=1\nNYX_WS_WRITABLE=1\nNYX_WS_REALPATH=\n",
            "",
            0,
        )
        assertInvalid(check, "probe_failed")
    }

    @Test
    fun `a successful probe returns the canonical real path`() {
        val check = WorkspaceValidator.parseProbe(
            "NYX_WS_EXISTS=1\r\nNYX_WS_DIR=1\r\nNYX_WS_WRITABLE=1\r\nNYX_WS_REALPATH=/data/projects/app\r\n",
            "",
            0,
        )
        assertEquals("/data/projects/app", validPath(check))
    }

    @Test
    fun `probe output without flags is a probe failure`() {
        assertInvalid(WorkspaceValidator.parseProbe("garbage\n", "", 0), "probe_failed")
    }

    @Test
    fun `probe script reports every marker and quotes the path`() {
        val script = WorkspaceValidator.probeScript("/home/user/my project")
        assertTrue(script.contains("NYX_WS_EXISTS="))
        assertTrue(script.contains("NYX_WS_DIR="))
        assertTrue(script.contains("NYX_WS_WRITABLE="))
        assertTrue(script.contains("NYX_WS_REALPATH="))
        assertTrue(script.contains("'/home/user/my project'"))
    }

    @Test
    fun `probe script escapes single quotes`() {
        val script = WorkspaceValidator.probeScript("/tmp/it's here")
        assertTrue(script.contains("'/tmp/it'\\''s here'"))
    }
}

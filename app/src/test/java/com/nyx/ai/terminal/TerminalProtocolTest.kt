package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalProtocolTest {
    @Test
    fun hostDescriptorPreservesArgvAndWorkdir() {
        val request = TerminalCommandRequest(
            environment = TerminalEnvironment.TERMUX_HOST,
            commandPath = "/data/data/com.termux/files/usr/bin/tool",
            arguments = listOf("arg with spaces", "--flag"),
            stdin = "payload",
            workingDirectory = "/data/data/com.termux/files/home/work",
            authorized = true,
        )

        val descriptor = TerminalCommandDescriptors.create(request)

        assertEquals(request.commandPath, descriptor?.executablePath)
        assertEquals(request.arguments, descriptor?.arguments)
        assertEquals(request.stdin, descriptor?.stdin)
        assertEquals(request.workingDirectory, descriptor?.workingDirectory)
        assertTrue(descriptor?.background == true)
    }

    @Test
    fun debianDescriptorUsesOnlyProbedProotPathAndDocumentedLoginStructure() {
        val request = TerminalCommandRequest(
            environment = TerminalEnvironment.DEBIAN_PROOT,
            commandPath = "/usr/bin/printf",
            arguments = listOf("%s", "hello"),
            authorized = true,
        )

        assertNull(TerminalCommandDescriptors.create(request))
        val descriptor = TerminalCommandDescriptors.create(request, "/custom/bin/proot-distro")

        assertEquals("/custom/bin/proot-distro", descriptor?.executablePath)
        assertEquals(listOf("login", "debian", "--", "/usr/bin/printf", "%s", "hello"), descriptor?.arguments)
        assertNull(descriptor?.workingDirectory)
    }

    @Test
    fun validationRejectsLongFieldsNulAndUnsupportedDebianWorkdir() {
        val request = TerminalCommandRequest(
            environment = TerminalEnvironment.DEBIAN_PROOT,
            commandPath = "/bin/echo",
            arguments = listOf("x".repeat(TerminalCommandRequest.MAX_ARGUMENT_CHARS + 1)),
            stdin = "x".repeat(TerminalCommandRequest.MAX_STDIN_CHARS + 1),
            workingDirectory = "/tmp",
            authorized = true,
        )
        val fields = TerminalRequestValidator.validate(request).map { it.field }.toSet()
        assertTrue("arguments[0]" in fields)
        assertTrue("stdin" in fields)
        assertTrue("workingDirectory" in fields)

        val nulPath = TerminalCommandRequest(TerminalEnvironment.TERMUX_HOST, "/bin/echo\u0000bad")
        assertTrue(TerminalRequestValidator.validate(nulPath).any { it.field == "commandPath" })
    }

    @Test
    fun authorizationIsDeniedByDefault() {
        val request = TerminalCommandRequest(TerminalEnvironment.TERMUX_HOST, "/bin/true")
        assertFalse(request.authorized)
    }

    @Test
    fun callbackClassificationSeparatesInfraErrorExitAndUnknown() {
        val success = TermuxCallbackData(
            infrastructureErrorCode = TerminalResultClassifier.TERMUX_INFRASTRUCTURE_OK,
            exitCode = 0,
            stdout = "out",
            stderr = "",
        )
        val failedProcess = TermuxCallbackData(
            infrastructureErrorCode = TerminalResultClassifier.TERMUX_INFRASTRUCTURE_OK,
            exitCode = 17,
            stderr = "err",
        )
        val infra = TermuxCallbackData(
            infrastructureErrorCode = -2,
            exitCode = null,
            errorMessage = "service error",
        )

        assertEquals(TerminalResultKind.SUCCEEDED, TerminalResultClassifier.classify("1", success).kind)
        val processResult = TerminalResultClassifier.classify("2", failedProcess)
        assertEquals(TerminalResultKind.PROCESS_EXIT, processResult.kind)
        assertEquals(17, processResult.exitCode)
        assertEquals("err", processResult.stderr)
        assertEquals(TerminalResultKind.TERMUX_INFRASTRUCTURE_ERROR, TerminalResultClassifier.classify("3", infra).kind)
        assertEquals(TerminalResultKind.UNKNOWN_RESULT, TerminalResultClassifier.classify("4", null).kind)
        assertEquals(TerminalResultKind.UNKNOWN_RESULT, TerminalResultClassifier.classify("5", success.copy(exitCode = null)).kind)
        assertFalse(TerminalResultClassifier.classify("6", failedProcess.copy(infrastructureErrorCode = null)).isSuccess)
    }

    @Test
    fun callbackTextIsCapped() {
        val data = TermuxCallbackData(
            infrastructureErrorCode = TerminalResultClassifier.TERMUX_INFRASTRUCTURE_OK,
            exitCode = 0,
            stdout = "x".repeat(TerminalResultClassifier.MAX_RETURNED_TEXT_CHARS + 50),
        )
        val result = TerminalResultClassifier.classify("cap", data)
        assertEquals(TerminalResultClassifier.MAX_RETURNED_TEXT_CHARS, result.stdout.length)
        assertTrue(result.isSuccess)
    }
}

package com.nyx.ai.autonomy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationTest {

    private fun specs(vararg items: EvidenceSpec) = items.toList()

    @Test
    fun `exit code evidence passes only on the expected code`() = runBlocking {
        val spec = EvidenceSpec(EvidenceKind.EXIT_CODE, expected = "0")
        assertTrue(TaskVerifier.verify(specs(spec), ObservationBundle(exitCode = 0)).passed)
        assertFalse(TaskVerifier.verify(specs(spec), ObservationBundle(exitCode = 1)).passed)
    }

    @Test
    fun `output contains is case insensitive`() = runBlocking {
        val spec = EvidenceSpec(EvidenceKind.OUTPUT_CONTAINS, expected = "BUILD SUCCESSFUL")
        val report = TaskVerifier.verify(specs(spec), ObservationBundle(stdout = "build successful in 12s"))
        assertTrue(report.passed)
    }

    @Test
    fun `missing expected output fails`() = runBlocking {
        val spec = EvidenceSpec(EvidenceKind.OUTPUT_CONTAINS, expected = "BUILD SUCCESSFUL")
        assertFalse(TaskVerifier.verify(specs(spec), ObservationBundle(stdout = "BUILD FAILED")).passed)
    }

    @Test
    fun `file evidence uses the injected probe`() = runBlocking {
        val spec = EvidenceSpec(EvidenceKind.FILE_EXISTS, target = "app/build/outputs/apk/app-debug.apk")
        val present = ObservationBundle(fileExists = { it.endsWith(".apk") })
        val absent = ObservationBundle(fileExists = { false })
        assertTrue(TaskVerifier.verify(specs(spec), present).passed)
        assertFalse(TaskVerifier.verify(specs(spec), absent).passed)
    }

    @Test
    fun `checksum evidence compares exact hex`() = runBlocking {
        val spec = EvidenceSpec(EvidenceKind.CHECKSUM_SHA256, target = "a.apk", expected = "abc123")
        val good = ObservationBundle(checksum = { "ABC123" })
        val bad = ObservationBundle(checksum = { "def456" })
        assertTrue(TaskVerifier.verify(specs(spec), good).passed)
        assertFalse(TaskVerifier.verify(specs(spec), bad).passed)
    }

    @Test
    fun `a failed tool call cannot be treated as completed work`() = runBlocking {
        val spec = EvidenceSpec(EvidenceKind.OUTPUT_CONTAINS, expected = "ok")
        val report = TaskVerifier.verify(specs(spec), ObservationBundle(stdout = "ok"), toolFailureCount = 1)
        assertFalse(report.passed)
        assertTrue(report.reason.orEmpty().isNotBlank())
    }

    @Test
    fun `no declared evidence requires non empty output and says so`() = runBlocking {
        val empty = TaskVerifier.verify(emptyList(), ObservationBundle(agentOutput = ""))
        val nonEmpty = TaskVerifier.verify(emptyList(), ObservationBundle(agentOutput = "done"))
        assertFalse(empty.passed)
        assertTrue(nonEmpty.passed)
        assertTrue(nonEmpty.results.single().detail.orEmpty().contains("only proves"))
    }

    @Test
    fun `evidence parser accepts the persisted object form`() {
        val raw = """{"specs":[{"kind":"exit_code","expected":"0"}],"results":[]}"""
        val parsed = EvidenceSpecParser.parse(raw)
        assertEquals(1, parsed.size)
        assertEquals(EvidenceKind.EXIT_CODE, parsed.single().kind)
    }

    @Test
    fun `evidence parser rejects incomplete specs`() {
        val raw = """[{"kind":"file_contains","target":"a"},{"kind":"exit_code","expected":"0"}]"""
        val parsed = EvidenceSpecParser.parse(raw)
        assertEquals(1, parsed.size)
        assertEquals(EvidenceKind.EXIT_CODE, parsed.single().kind)
    }

    @Test
    fun `failure signature normalizes volatile values`() {
        val first = TaskVerifier.failureSignature("task-1", "Build failed at 0x9f3ac1 after 42 seconds")
        val second = TaskVerifier.failureSignature("task-1", "Build failed at 0x111111 after 7 seconds")
        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `retry is allowed within budget for read only work`() {
        val decision = RecoveryPolicy.decide(1, 3, idempotent = true, failureSignature = "sig", previousFailureSignatures = emptyList())
        assertEquals(RecoveryAction.RETRY, decision.action)
        assertTrue(decision.delayMillis > 0)
    }

    @Test
    fun `exhausted budget fails permanently for idempotent work`() {
        val decision = RecoveryPolicy.decide(3, 3, idempotent = true, failureSignature = "sig", previousFailureSignatures = emptyList())
        assertEquals(RecoveryAction.FAIL_PERMANENTLY, decision.action)
    }

    @Test
    fun `non idempotent work always requires review`() {
        val decision = RecoveryPolicy.decide(1, 3, idempotent = false, failureSignature = "sig", previousFailureSignatures = emptyList())
        assertEquals(RecoveryAction.NEEDS_REVIEW, decision.action)
    }

    @Test
    fun `repeated identical failure is not retried`() {
        val decision = RecoveryPolicy.decide(
            attemptCount = 1,
            maxAttempts = 5,
            idempotent = true,
            failureSignature = "same",
            previousFailureSignatures = listOf("same"),
        )
        assertEquals(RecoveryAction.FAIL_PERMANENTLY, decision.action)
    }
}

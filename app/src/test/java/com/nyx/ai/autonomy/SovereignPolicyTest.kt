package com.nyx.ai.autonomy

import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.performance.PerformanceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignPolicyTest {
    @Test fun constrainedDeviceGetsLightDepth() {
        val depth = SovereignPolicy.depthFor(
            workerKind = "development",
            title = "Build and test the project",
            instructions = "Inspect, repair, build, and test the Android project.",
            performanceLevel = PerformanceLevel.ECO,
            lowMemory = true,
        )
        assertEquals(ReasoningDepth.LIGHT, depth)
    }

    @Test fun complexDevelopmentGetsDeepDepth() {
        val depth = SovereignPolicy.depthFor(
            workerKind = "development",
            title = "Repair the build",
            instructions = "Diagnose the failing build, inspect the source, change the implementation, and verify the tests.",
            performanceLevel = PerformanceLevel.PERFORMANCE,
        )
        assertEquals(ReasoningDepth.DEEP, depth)
    }

    @Test fun ecoBudgetIsLowerAndContextIsBounded() {
        val standard = SovereignPolicy.budgetFor(ReasoningDepth.STANDARD, PerformanceLevel.BALANCED, "development", 8_192)
        val eco = SovereignPolicy.budgetFor(ReasoningDepth.DEEP, PerformanceLevel.ECO, "development", 8_192)
        assertTrue(eco.maxSteps <= standard.maxSteps)
        assertTrue(eco.maxModelCalls <= standard.maxModelCalls)
        assertTrue(eco.maxToolCalls <= standard.maxToolCalls)
        assertTrue(eco.maxContextChars <= 7_000)
        assertTrue(standard.maxContextChars <= 14_000)
    }

    @Test fun observationsClassifyDeniedFailureAndSuccess() {
        assertEquals(ObservationOutcome.DENIED, SovereignPolicy.classifyObservation("terminal_run", "Tool denied: blocked"))
        assertEquals(ObservationOutcome.FAILURE, SovereignPolicy.classifyObservation("terminal_run", "Tool error: missing"))
        assertEquals(ObservationOutcome.FAILURE, SovereignPolicy.classifyObservation("test_run", """{"exitCode":1,"passed":false}"""))
        assertEquals(ObservationOutcome.SUCCESS, SovereignPolicy.classifyObservation("file_read", "file contents", 0))
    }

    @Test fun completionClaimWithoutEvidenceIsFlagged() {
        val assessment = SovereignPolicy.assessFinal(
            text = "I created the file and the build passed.",
            requiresToolEvidence = true,
            evidence = emptyList(),
        )
        assertTrue(assessment.claimsCompletion)
        assertFalse(assessment.hasSuccessfulEvidence)
        assertTrue(assessment.falseSuccessRisk)
    }

    @Test fun honestBlockerIsNotTreatedAsCompletion() {
        val assessment = SovereignPolicy.assessFinal(
            text = "I cannot complete this because the required compiler is missing. No files were changed.",
            requiresToolEvidence = true,
            evidence = emptyList(),
        )
        assertFalse(assessment.falseSuccessRisk)
    }

    @Test fun successfulEvidenceAllowsCompletion() {
        val assessment = SovereignPolicy.assessFinal(
            text = "I created the file and the build passed.",
            requiresToolEvidence = true,
            evidence = listOf(ToolExecutionEvidence("build_run", exitCode = 0, stdout = "BUILD SUCCESSFUL")),
        )
        assertTrue(assessment.claimsCompletion)
        assertTrue(assessment.hasSuccessfulEvidence)
        assertFalse(assessment.falseSuccessRisk)
    }

    @Test fun retryHintRequiresAChangedApproach() {
        val hint = SovereignPolicy.retryHint("file missing", 1, 2)
        assertTrue(hint.contains("file missing"))
        assertTrue(hint.contains("Do not repeat"))
    }

    @Test fun durableObservationsRedactCredentials() {
        val redacted = SovereignPolicy.redact("Authorization: Bearer abc.def.ghi token=supersecret password: hunter2")
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("supersecret"))
        assertFalse(redacted.contains("hunter2"))
        assertTrue(redacted.contains("[redacted]"))
    }
}

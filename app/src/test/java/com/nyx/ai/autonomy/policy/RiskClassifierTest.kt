package com.nyx.ai.autonomy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskClassifierTest {
    private val classifier = RiskClassifier()

    private fun classify(
        kind: ActionKind,
        mode: AutonomyMode = AutonomyMode.MANUAL,
        destructive: Boolean = false,
        privateDataOutbound: Boolean = false,
        trustedSource: Boolean = true,
        delegationDepth: Int = 0,
        insideAuthorizedWorkspace: Boolean = false,
    ): ActionRisk = classifier.classify(
        ActionRequest(
            kind = kind,
            target = "/root/ws",
            insideAuthorizedWorkspace = insideAuthorizedWorkspace,
            destructive = destructive,
            estimatedBytes = 1_024L,
            trustedSource = trustedSource,
            privateDataOutbound = privateDataOutbound,
            delegationDepth = delegationDepth,
        ),
        mode,
    )

    @Test
    fun baselineRiskMatchesThePolicyTable() {
        val expected = mapOf(
            ActionKind.READ_WORKSPACE to RiskLevel.ROUTINE,
            ActionKind.NETWORK_READ to RiskLevel.ROUTINE,
            ActionKind.WRITE_WORKSPACE to RiskLevel.ELEVATED,
            ActionKind.RUN_READONLY_COMMAND to RiskLevel.ELEVATED,
            ActionKind.RUN_MUTATING_COMMAND to RiskLevel.ELEVATED,
            ActionKind.NETWORK_DOWNLOAD to RiskLevel.ELEVATED,
            ActionKind.GIT_COMMIT to RiskLevel.ELEVATED,
            ActionKind.DELEGATE to RiskLevel.ELEVATED,
            ActionKind.DELETE_WORKSPACE to RiskLevel.HIGH,
            ActionKind.INSTALL_PACKAGE to RiskLevel.HIGH,
            ActionKind.NETWORK_UPLOAD to RiskLevel.HIGH,
            ActionKind.GIT_PUSH to RiskLevel.HIGH,
            ActionKind.INSTALL_APK to RiskLevel.HIGH,
            ActionKind.PUBLISH to RiskLevel.CRITICAL,
            ActionKind.SYSTEM_CONFIG to RiskLevel.CRITICAL,
            ActionKind.USE_CREDENTIAL to RiskLevel.CRITICAL,
        )

        assertEquals(ActionKind.values().size, expected.size)
        expected.forEach { (kind, level) ->
            assertEquals("baseline for $kind", level, classify(kind).level)
        }
    }

    @Test
    fun destructiveActionsEscalateOneLevel() {
        assertEquals(RiskLevel.HIGH, classify(ActionKind.WRITE_WORKSPACE, destructive = true).level)
        assertEquals(RiskLevel.ELEVATED, classify(ActionKind.READ_WORKSPACE, destructive = true).level)
        assertEquals(RiskLevel.ROUTINE, classify(ActionKind.READ_WORKSPACE).level)
    }

    @Test
    fun outboundPrivateDataEscalatesOneLevel() {
        assertEquals(RiskLevel.CRITICAL, classify(ActionKind.GIT_PUSH, privateDataOutbound = true).level)
        assertEquals(RiskLevel.HIGH, classify(ActionKind.WRITE_WORKSPACE, privateDataOutbound = true).level)
    }

    @Test
    fun untrustedPackageSourcesEscalateOnlyPackageInstalls() {
        assertEquals(RiskLevel.HIGH, classify(ActionKind.INSTALL_PACKAGE, trustedSource = true).level)
        assertEquals(RiskLevel.CRITICAL, classify(ActionKind.INSTALL_PACKAGE, trustedSource = false).level)
        assertEquals(RiskLevel.ELEVATED, classify(ActionKind.NETWORK_DOWNLOAD, trustedSource = false).level)
    }

    @Test
    fun delegatedExecutionEscalatesOneLevel() {
        assertEquals(RiskLevel.ELEVATED, classify(ActionKind.DELEGATE).level)
        assertEquals(RiskLevel.HIGH, classify(ActionKind.DELEGATE, delegationDepth = 1).level)
        assertEquals(RiskLevel.HIGH, classify(ActionKind.GIT_COMMIT, delegationDepth = 2).level)
    }

    @Test
    fun multipleTriggersEscalateOnlyOnceAndNeverBeyondCritical() {
        val combined = classify(
            ActionKind.GIT_PUSH,
            destructive = true,
            privateDataOutbound = true,
            delegationDepth = 3,
        )

        assertEquals(RiskLevel.CRITICAL, combined.level)
        val critical = classify(ActionKind.PUBLISH, destructive = true, privateDataOutbound = true)
        assertEquals(RiskLevel.CRITICAL, critical.level)
    }

    @Test
    fun reasonsAreReadableAndBounded() {
        val escalated = classify(
            ActionKind.GIT_PUSH,
            destructive = true,
            privateDataOutbound = true,
            delegationDepth = 2,
        )

        assertTrue(escalated.reasons.isNotEmpty())
        assertTrue(escalated.reasons.size <= 4)
        assertTrue(escalated.reasons.none { it.isBlank() })
        assertTrue(escalated.reasons.first().contains("git push"))
        assertTrue(escalated.reasons.first().contains("high risk"))
        assertTrue(escalated.reasons.any { it.contains("escalated") })
        assertTrue(escalated.reasons.any { it.contains("destructive") })
        assertTrue(escalated.reasons.any { it.contains("private data outbound") })
    }

    @Test
    fun untrustedPackageReasonNamesTheSource() {
        val risk = classify(ActionKind.INSTALL_PACKAGE, trustedSource = false)

        assertTrue(risk.reasons.any { it.contains("untrusted package source") })
        assertEquals(RiskLevel.CRITICAL, risk.level)
    }

    @Test
    fun approvalAdviceFollowsTheModePolicy() {
        assertFalse(classify(ActionKind.READ_WORKSPACE, AutonomyMode.MANUAL).requiresApproval)
        assertFalse(classify(ActionKind.RUN_READONLY_COMMAND, AutonomyMode.MANUAL).requiresApproval)
        assertTrue(classify(ActionKind.NETWORK_READ, AutonomyMode.MANUAL).requiresApproval)
        assertTrue(classify(ActionKind.WRITE_WORKSPACE, AutonomyMode.MANUAL).requiresApproval)

        assertFalse(classify(ActionKind.WRITE_WORKSPACE, AutonomyMode.SMART).requiresApproval)
        assertTrue(classify(ActionKind.INSTALL_PACKAGE, AutonomyMode.SMART, trustedSource = true).requiresApproval)
        assertTrue(classify(ActionKind.PUBLISH, AutonomyMode.SMART).requiresApproval)

        assertFalse(classify(ActionKind.GIT_PUSH, AutonomyMode.FULL).requiresApproval)
        assertTrue(classify(ActionKind.INSTALL_APK, AutonomyMode.FULL, trustedSource = true).requiresApproval)
        assertTrue(
            classify(ActionKind.GIT_COMMIT, AutonomyMode.FULL, privateDataOutbound = true).requiresApproval,
        )
        assertTrue(classify(ActionKind.USE_CREDENTIAL, AutonomyMode.FULL).requiresApproval)
    }

    @Test
    fun destructiveWorkOutsideTheWorkspaceNeedsReviewEvenInFullMode() {
        val inside = ActionRequest(
            kind = ActionKind.RUN_MUTATING_COMMAND,
            target = "/root/ws",
            destructive = true,
            insideAuthorizedWorkspace = true,
        )
        val outside = inside.copy(insideAuthorizedWorkspace = false)

        assertEquals(RiskLevel.HIGH, classifier.classify(inside, AutonomyMode.FULL).level)
        assertFalse(highRiskNeedsHumanReview(inside))
        assertTrue(highRiskNeedsHumanReview(outside))
        assertFalse(classifier.classify(inside, AutonomyMode.FULL).requiresApproval)
        assertTrue(classifier.classify(outside, AutonomyMode.FULL).requiresApproval)
    }
}

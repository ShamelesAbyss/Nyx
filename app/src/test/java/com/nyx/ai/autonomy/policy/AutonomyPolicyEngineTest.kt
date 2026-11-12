package com.nyx.ai.autonomy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyPolicyEngineTest {
    private var clock = 10_000L

    private fun grant(
        id: String = "grant-1",
        capability: String,
        environmentId: String = "*",
        scope: String = "*",
        expiresAt: Long? = null,
        revoked: Boolean = false,
    ): CapabilityGrant = CapabilityGrant(
        id = id,
        capability = capability,
        environmentId = environmentId,
        scope = scope,
        createdAt = 1_000L,
        expiresAt = expiresAt,
        revoked = revoked,
    )

    private fun engine(
        mode: AutonomyMode,
        grants: List<CapabilityGrant> = emptyList(),
    ): AutonomyPolicyEngine = AutonomyPolicyEngine(mode, grants) { clock }

    @Test
    fun manualModeAllowsReadsButQueuesEveryWriteForApproval() {
        val policy = engine(AutonomyMode.MANUAL)

        val read = policy.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/ws"))
        assertTrue(read.allowed)
        assertFalse(read.requiresApproval)
        assertEquals("manual_read_only", read.reason)

        val inspection = policy.authorize(ActionRequest(ActionKind.RUN_READONLY_COMMAND, "ls -la"))
        assertTrue(inspection.allowed)

        val networkRead = policy.authorize(ActionRequest(ActionKind.NETWORK_READ, "https://example.test"))
        assertFalse(networkRead.allowed)
        assertTrue(networkRead.requiresApproval)

        val write = policy.authorize(
            ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/ws/file.txt", insideAuthorizedWorkspace = true),
        )
        assertFalse(write.allowed)
        assertTrue(write.requiresApproval)
        assertEquals("manual_requires_approval", write.reason)
        assertEquals(RiskLevel.ELEVATED, write.risk)
    }

    @Test
    fun manualModeDeniesCriticalActionsWithoutGrantAndStillAsksWhenGranted() {
        val policy = engine(AutonomyMode.MANUAL, listOf(grant(capability = "publish")))

        val granted = policy.authorize(ActionRequest(ActionKind.PUBLISH, "/root/ws"))
        assertFalse(granted.allowed)
        assertTrue(granted.requiresApproval)
        assertEquals(RiskLevel.CRITICAL, granted.risk)
        assertEquals("manual_critical_granted_requires_approval", granted.reason)

        val ungranted = policy.authorize(ActionRequest(ActionKind.SYSTEM_CONFIG, "wifi"))
        assertFalse(ungranted.allowed)
        assertFalse(ungranted.requiresApproval)
        assertEquals("manual_critical_denied", ungranted.reason)
    }

    @Test
    fun manualModeTreatsApkInstallationAsCriticalEvenThoughItIsHighRisk() {
        val decision = engine(AutonomyMode.MANUAL).authorize(
            ActionRequest(ActionKind.INSTALL_APK, "/sdcard/app.apk", trustedSource = true),
        )

        assertEquals(RiskLevel.HIGH, decision.risk)
        assertFalse(decision.allowed)
        assertFalse(decision.requiresApproval)
        assertEquals("manual_critical_denied", decision.reason)
    }

    @Test
    fun smartModeAllowsRoutineReadsInsideTheWorkspaceWithoutAGrant() {
        val policy = engine(AutonomyMode.SMART)

        val inside = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/root/ws/notes.md", insideAuthorizedWorkspace = true),
        )
        assertTrue(inside.allowed)
        assertEquals("smart_routine_workspace", inside.reason)

        val outside = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/sdcard/other/notes.md", insideAuthorizedWorkspace = false),
        )
        assertFalse(outside.allowed)
        assertEquals("smart_routine_not_granted", outside.reason)
    }

    @Test
    fun smartModeAllowsRoutineReadsWithAGrantAndElevatedWritesOnlyWithAGrant() {
        val policy = engine(
            AutonomyMode.SMART,
            listOf(grant(capability = "workspace.read"), grant(id = "grant-2", capability = "workspace.write")),
        )

        val read = policy.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/ws/notes.md"))
        assertTrue(read.allowed)
        assertEquals("smart_routine_grant", read.reason)

        val write = policy.authorize(ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/ws/file.txt"))
        assertTrue(write.allowed)
        assertEquals("smart_elevated_grant", write.reason)

        val command = policy.authorize(ActionRequest(ActionKind.RUN_MUTATING_COMMAND, "rm -rf build"))
        assertFalse(command.allowed)
        assertEquals("smart_elevated_grant_required", command.reason)
    }

    @Test
    fun smartModeEscalatesPackageInstallationToHumanApproval() {
        val policy = engine(AutonomyMode.SMART, listOf(grant(capability = "packages.install")))

        val decision = policy.authorize(
            ActionRequest(ActionKind.INSTALL_PACKAGE, "org.example.tool", trustedSource = true),
        )

        assertEquals(RiskLevel.HIGH, decision.risk)
        assertFalse(decision.allowed)
        assertTrue(decision.requiresApproval)
        assertEquals("smart_high_requires_approval", decision.reason)
    }

    @Test
    fun smartModeDeniesCriticalPublishingEvenWhenGranted() {
        val decision = engine(AutonomyMode.SMART, listOf(grant(capability = "publish")))
            .authorize(ActionRequest(ActionKind.PUBLISH, "/root/ws/site"))

        assertEquals(RiskLevel.CRITICAL, decision.risk)
        assertFalse(decision.allowed)
        assertFalse(decision.requiresApproval)
        assertEquals("smart_critical_denied", decision.reason)
    }

    @Test
    fun fullModeInstallsTrustedPackagesAutonomouslyWithAGrant() {
        val policy = engine(AutonomyMode.FULL, listOf(grant(capability = "packages.install")))
        val request = ActionRequest(ActionKind.INSTALL_PACKAGE, "org.example.tool", trustedSource = true)

        val decision = policy.authorize(request)
        assertTrue(decision.allowed)
        assertFalse(decision.requiresApproval)
        assertEquals("full_high_grant", decision.reason)

        val ungranted = engine(AutonomyMode.FULL).authorize(request)
        assertFalse(ungranted.allowed)
        assertEquals("full_high_grant_required", ungranted.reason)
    }

    @Test
    fun fullModeStillDeniesPublishingAndAsksBeforeInstallingApks() {
        val policy = engine(
            AutonomyMode.FULL,
            listOf(grant(id = "grant-1", capability = "publish"), grant(id = "grant-2", capability = "apk.install")),
        )

        val publish = policy.authorize(ActionRequest(ActionKind.PUBLISH, "/root/ws/site"))
        assertFalse(publish.allowed)
        assertEquals("full_critical_denied", publish.reason)

        val apk = policy.authorize(ActionRequest(ActionKind.INSTALL_APK, "/sdcard/nyx.apk", trustedSource = true))
        assertFalse(apk.allowed)
        assertTrue(apk.requiresApproval)
        assertEquals("full_high_requires_approval", apk.reason)
    }

    @Test
    fun fullModeAsksWhenPrivateDataWouldLeaveTheDevice() {
        val policy = engine(AutonomyMode.FULL, listOf(grant(capability = "git.commit")))

        val decision = policy.authorize(
            ActionRequest(ActionKind.GIT_COMMIT, "/root/p", privateDataOutbound = true),
        )

        assertEquals(RiskLevel.HIGH, decision.risk)
        assertFalse(decision.allowed)
        assertTrue(decision.requiresApproval)
        assertEquals("full_high_requires_approval", decision.reason)
    }

    @Test
    fun fullModeDeniesUnreviewedHighRiskActionsWithoutAGrant() {
        val decision = engine(AutonomyMode.FULL)
            .authorize(ActionRequest(ActionKind.GIT_PUSH, "origin/main", insideAuthorizedWorkspace = true))

        assertEquals(RiskLevel.HIGH, decision.risk)
        assertFalse(decision.allowed)
        assertFalse(decision.requiresApproval)
        assertEquals("full_high_grant_required", decision.reason)
    }

    @Test
    fun emergencyStopDeniesEverythingUntilReset() {
        val policy = engine(AutonomyMode.FULL, listOf(grant(capability = "workspace.read")))
        policy.emergencyStop()

        val read = policy.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/ws"))
        assertFalse(read.allowed)
        assertFalse(read.requiresApproval)
        assertEquals("emergency_stop", read.reason)

        val delegated = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/root/ws", delegationDepth = 1),
            DelegatedPermissionScope(allowedKinds = setOf(ActionKind.READ_WORKSPACE)),
        )
        assertFalse(delegated.allowed)
        assertEquals("emergency_stop", delegated.reason)

        policy.resetStop()
        val resumed = policy.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/ws"))
        assertTrue(resumed.allowed)
    }

    @Test
    fun revokedGrantStopsAuthorizingAndCannotBeRevokedTwice() {
        val policy = engine(AutonomyMode.FULL, listOf(grant(capability = "workspace.write")))
        val request = ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/ws/file.txt")

        assertTrue(policy.revoke("grant-1"))
        assertFalse(policy.revoke("grant-1"))

        val decision = policy.authorize(request)
        assertFalse(decision.allowed)
        assertEquals("full_elevated_grant_required", decision.reason)
        assertTrue(policy.snapshot().grants.single().revoked)
    }

    @Test
    fun expiredGrantDoesNotAuthorize() {
        val policy = engine(AutonomyMode.FULL, listOf(grant(capability = "workspace.write", expiresAt = 10_000L)))
        val request = ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/ws/file.txt")

        val expired = policy.authorize(request)
        assertFalse(expired.allowed)
        assertEquals("full_elevated_grant_required", expired.reason)

        policy.replaceGrants(listOf(grant(capability = "workspace.write", expiresAt = 10_001L)))
        val valid = policy.authorize(request)
        assertTrue(valid.allowed)
        assertEquals("full_elevated_grant", valid.reason)
    }

    @Test
    fun grantScopeIsPathAware() {
        val policy = engine(
            AutonomyMode.FULL,
            listOf(grant(capability = "workspace.write", scope = "/root/p")),
        )

        assertTrue(policy.authorize(ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/p")).allowed)
        assertTrue(policy.authorize(ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/p/src/Main.kt")).allowed)

        val sibling = policy.authorize(ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/p2/src/Main.kt"))
        assertFalse(sibling.allowed)
        assertEquals("full_elevated_grant_required", sibling.reason)
    }

    @Test
    fun grantEnvironmentsMustMatchTheRequestEnvironment() {
        val policy = engine(
            AutonomyMode.FULL,
            listOf(
                grant(capability = "workspace.write", environmentId = "termux_host"),
                grant(id = "grant-2", capability = "workspace.write", environmentId = "*"),
            ),
        )

        val host = ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/p", environmentId = "termux_host")
        val proot = ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/p", environmentId = "proot_ubuntu")
        assertTrue(policy.authorize(host).allowed)
        assertTrue(policy.authorize(proot).allowed)

        policy.replaceGrants(listOf(grant(capability = "workspace.write", environmentId = "termux_host")))
        val other = policy.authorize(
            ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/p", environmentId = "proot_ubuntu"),
        )
        assertFalse(other.allowed)
        assertEquals("full_elevated_grant_required", other.reason)
    }

    @Test
    fun delegatedScopeAllowsOnlyMatchingKindEnvironmentAndPath() {
        val policy = engine(AutonomyMode.FULL, listOf(grant(capability = "workspace.read")))
        val scope = DelegatedPermissionScope(
            allowedKinds = setOf(ActionKind.READ_WORKSPACE),
            maxRisk = RiskLevel.ELEVATED,
            environmentIds = setOf("termux_host"),
            scopePrefixes = listOf("/root/p"),
            maxDepth = 2,
        )

        val allowed = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/root/p/a.md", delegationDepth = 1),
            scope,
        )
        assertTrue(allowed.allowed)
        assertEquals("full_elevated_grant", allowed.reason)

        val wrongKind = policy.authorize(
            ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/p/a.md", delegationDepth = 1),
            scope,
        )
        assertEquals("delegation_kind_not_allowed", wrongKind.reason)

        val tooDeep = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/root/p/a.md", delegationDepth = 3),
            scope,
        )
        assertEquals("delegation_depth_exceeded", tooDeep.reason)

        val wrongEnvironment = policy.authorize(
            ActionRequest(
                kind = ActionKind.READ_WORKSPACE,
                target = "/root/p/a.md",
                environmentId = "proot_ubuntu",
                delegationDepth = 1,
            ),
            scope,
        )
        assertEquals("delegation_environment_not_allowed", wrongEnvironment.reason)

        val wrongPath = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/root/p2/a.md", delegationDepth = 1),
            scope,
        )
        assertEquals("delegation_scope_not_allowed", wrongPath.reason)
    }

    @Test
    fun delegatedScopeRejectsRiskAboveItsCeiling() {
        val policy = engine(AutonomyMode.FULL, listOf(grant(capability = "workspace.read")))
        val scope = DelegatedPermissionScope(
            allowedKinds = setOf(ActionKind.READ_WORKSPACE),
            maxRisk = RiskLevel.ROUTINE,
        )

        val decision = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/root/p/a.md", delegationDepth = 1),
            scope,
        )

        assertEquals(RiskLevel.ELEVATED, decision.risk)
        assertFalse(decision.allowed)
        assertEquals("delegation_risk_exceeded", decision.reason)
    }

    @Test
    fun delegationNeverSubstitutesForAGrant() {
        val policy = engine(AutonomyMode.FULL)
        val scope = DelegatedPermissionScope(allowedKinds = setOf(ActionKind.READ_WORKSPACE))

        val decision = policy.authorize(
            ActionRequest(ActionKind.READ_WORKSPACE, "/root/p/a.md", delegationDepth = 1),
            scope,
        )

        assertFalse(decision.allowed)
        assertEquals("full_elevated_grant_required", decision.reason)
    }

    @Test
    fun everyAuthorizationIsJournaledWithItsDecision() {
        clock = 42L
        val policy = engine(AutonomyMode.MANUAL)

        policy.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/ws"))
        policy.authorize(ActionRequest(ActionKind.WRITE_WORKSPACE, "/root/ws/file.txt"))

        val entries = policy.journalEntries()
        assertEquals(2, entries.size)
        assertEquals(42L, entries[0].at)
        assertEquals("allowed", entries[0].decision)
        assertEquals(AutonomyMode.MANUAL, entries[0].mode)
        assertEquals(ActionKind.WRITE_WORKSPACE, entries[1].kind)
        assertEquals("approval_required", entries[1].decision)
        assertEquals("manual_requires_approval", entries[1].reason)
    }

    @Test
    fun snapshotCapturesModeStopFlagAndGrants() {
        val policy = engine(AutonomyMode.SMART, listOf(grant(capability = "workspace.read")))
        policy.updateMode(AutonomyMode.FULL)
        policy.emergencyStop()
        policy.grant(grant(id = "grant-1", capability = "git.push", scope = "/root/p"))

        val snapshot = policy.snapshot()
        assertEquals(AutonomyMode.FULL, snapshot.mode)
        assertTrue(snapshot.stopped)
        assertEquals(1, snapshot.grants.size)
        assertEquals("git.push", snapshot.grants.single().capability)
    }

    @Test
    fun journalKeepsOnlyTheMostRecentEntries() {
        val journal = AuthorizationJournal(capacity = 2)
        val entry = { minute: Long ->
            AuthorizationEntry(
                at = minute,
                mode = AutonomyMode.SMART,
                kind = ActionKind.NETWORK_READ,
                target = "https://example.test",
                risk = RiskLevel.ROUTINE,
                decision = "allowed",
                reason = "smart_routine_grant",
            )
        }

        journal.record(entry(1L))
        journal.record(entry(2L))
        journal.record(entry(3L))

        assertEquals(listOf(2L, 3L), journal.entries().map { it.at })

        journal.clear()
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun policyCodecRoundTripsGrantsAndJournalRecords() {
        val grants = listOf(
            grant(capability = "workspace.write", scope = "/root/p", expiresAt = 99_000L),
            grant(id = "grant-2", capability = "publish", revoked = true),
        )

        val decoded = AutonomyPolicyCodec.decodeGrants(AutonomyPolicyCodec.encodeGrants(grants))

        assertEquals(2, decoded.size)
        assertEquals(grants[0], decoded[0])
        assertEquals(grants[1], decoded[1])

        val entry = AuthorizationEntry(
            at = 7L,
            mode = AutonomyMode.FULL,
            kind = ActionKind.GIT_PUSH,
            target = "origin/main",
            risk = RiskLevel.HIGH,
            decision = "denied",
            reason = "full_high_grant_required",
        )
        assertEquals(entry, AutonomyPolicyCodec.decodeEntry(AutonomyPolicyCodec.encodeEntry(entry)))
    }

    @Test
    fun policyCodecFallsBackToSafeDefaultsOnCorruptInput() {
        assertTrue(AutonomyPolicyCodec.decodeGrants("not json").isEmpty())
        assertTrue(AutonomyPolicyCodec.decodeGrants(null).isEmpty())
        assertEquals(AutonomyMode.MANUAL, AutonomyPolicyCodec.decodeMode("TURBO"))
        assertEquals(AutonomyMode.FULL, AutonomyPolicyCodec.decodeMode("FULL"))
        assertNull(AutonomyPolicyCodec.decodeEntry("{\"at\":1}"))
        assertNull(AutonomyPolicyCodec.decodeEntry("garbage"))
    }

    @Test
    fun scopeCoverageRequiresAPathBoundary() {
        assertTrue(scopeCovers("/root/p", "/root/p"))
        assertTrue(scopeCovers("/root/p", "/root/p/nested/file"))
        assertTrue(scopeCovers("/root/p/", "/root/p/file"))
        assertFalse(scopeCovers("/root/p", "/root/p2/file"))
        assertTrue(scopeCovers("*", "/anything"))
        assertFalse(scopeCovers("/root/p", "/root"))
    }
}

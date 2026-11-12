package com.nyx.ai.skills

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillFoundationTest {
    private val fullContext = SkillValidationContext(
        setOf("workspace_read", "workspace_write", "file_read", "file_write", "file_list", "file_search", "project_detect", "terminal_run", "repo_status", "repo_diff", "build_run", "test_run"),
        SkillPermission.entries.toSet(), SkillPrecondition.entries.toSet(),
    )
    private fun imported() = SkillManifest("local.research", "1.2.3", "Read selected local files", setOf("file_read", "file_list"),
        setOf(SkillPermission.WORKSPACE_READ), setOf(SkillPrecondition.WORKSPACE_SELECTED), instructions = listOf("Read bounded local evidence."))

    @Test fun validUntrustedReadOnlyManifestUsesSubsetOfHostAuthority() {
        val result = SkillValidator.validateUntrusted(imported(), fullContext)
        assertTrue(result.isValid)
        assertEquals(SkillOrigin.UNTRUSTED_IMPORT, result.skill?.origin)
        assertEquals(setOf("file_read", "file_list"), result.skill?.allowedTools)
        assertEquals(setOf(SkillPermission.WORKSPACE_READ), result.skill?.requiredPermissions)
    }

    @Test fun allFiveFixedBuiltinsValidateAgainstExplicitHostGrants() {
        val builtins = BuiltinSkillRegistry.all()
        assertEquals(5, builtins.size)
        assertEquals(5, builtins.map { it.id }.toSet().size)
        builtins.forEach { builtin ->
            val result = BuiltinSkillRegistry.validate(builtin.id, fullContext)
            assertTrue("${builtin.id}: ${result.issues}", result.isValid)
            assertEquals(SkillOrigin.TRUSTED_BUILTIN, result.skill?.origin)
            assertFalse(result.skill!!.manifest.constraints.allowAutoCommit)
        }
    }

    @Test fun importCannotRequestShellWriteBuildOrNewToolsEvenIfHostGrantsThem() {
        listOf("terminal_run", "file_write", "workspace_write", "build_run", "test_run", "sudo", "network_fetch").forEach { tool ->
            val result = SkillValidator.validateUntrusted(imported().copy(tools = setOf(tool),
                requiredPermissions = SkillPermission.entries.toSet()), fullContext)
            assertFalse(tool, result.isValid)
            assertNull(result.skill)
            assertTrue(result.issues.any { it.code == SkillIssueCode.TOOL_ESCALATION })
        }
    }

    @Test fun importCannotEscalatePermissionsWithReadOnlyTool() {
        val result = SkillValidator.validateUntrusted(imported().copy(requiredPermissions = setOf(SkillPermission.WORKSPACE_READ,
            SkillPermission.TERMINAL_EXECUTE)), fullContext)
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == SkillIssueCode.PERMISSION_ESCALATION })
    }

    @Test fun trustedBuiltinStillRequiresHostToolsPermissionsAndPreconditions() {
        val denied = BuiltinSkillRegistry.validate(BuiltinSkillRegistry.SOURCE_REPAIR,
            fullContext.copy(allowedTools = setOf("file_read"), grantedPermissions = setOf(SkillPermission.WORKSPACE_READ), satisfiedPreconditions = emptySet()))
        assertFalse(denied.isValid)
        assertTrue(denied.issues.any { it.code == SkillIssueCode.TOOL_ESCALATION })
        assertTrue(denied.issues.any { it.code == SkillIssueCode.PERMISSION_ESCALATION })
        assertTrue(denied.issues.any { it.code == SkillIssueCode.MISSING_PRECONDITION })
    }

    @Test fun builtinIdImpersonationDoesNotPromoteAnImportedManifest() {
        val spoofed = imported().copy(id = BuiltinSkillRegistry.WORKSPACE_INSPECTION)
        val result = SkillValidator.validateUntrusted(spoofed, fullContext)
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == SkillIssueCode.RESERVED_ID })
        assertFalse(BuiltinSkillRegistry.validate("local.research", fullContext).isValid)
    }

    @Test fun constraintsCannotDisableApprovalScopeOfflineOrCommitRestrictions() {
        val constraints = listOf(SkillConstraints(workspaceOnly = false), SkillConstraints(allowNetwork = true),
            SkillConstraints(allowDestructive = true), SkillConstraints(allowAutoCommit = true),
            SkillConstraints(requireWriteApproval = false), SkillConstraints(maxSteps = 0), SkillConstraints(maxOutputChars = 100_000))
        constraints.forEach { item ->
            val result = SkillValidator.validateUntrusted(imported().copy(constraints = item), fullContext)
            assertFalse(result.isValid)
            assertTrue(result.issues.any { it.code == SkillIssueCode.INVALID_CONSTRAINTS })
        }
    }

    @Test fun failuresAreBoundedAndEvidenceCannotBeDiscarded() {
        val invalid = listOf(SkillFailureHandling(maxRetries = 1), SkillFailureHandling(preserveEvidence = false),
            SkillFailureHandling(SkillFailureMode.RETRY_THEN_REPORT, 2))
        invalid.forEach { failure ->
            assertFalse(SkillValidator.validateUntrusted(imported().copy(failureHandling = failure), fullContext).isValid)
        }
        assertTrue(SkillValidator.validateUntrusted(imported().copy(failureHandling =
            SkillFailureHandling(SkillFailureMode.RETRY_THEN_REPORT, 1)), fullContext).isValid)
    }

    @Test fun malformedIdsVersionsDescriptionsInstructionsAndMissingPermissionsAreRejected() {
        val invalid = listOf(imported().copy(id = "../escape"), imported().copy(version = "1.0"),
            imported().copy(version = "01.0.0"), imported().copy(description = ""),
            imported().copy(instructions = listOf("")), imported().copy(requiredPermissions = emptySet()),
            imported().copy(preconditions = emptySet()))
        invalid.forEach { assertFalse(SkillValidator.validateUntrusted(it, fullContext).isValid) }
    }

    @Test fun schemaAndHostCompatibilityAreCheckedNumerically() {
        assertFalse(SkillValidator.validateUntrusted(imported().copy(compatibility = SkillCompatibility(manifestVersion = 2)), fullContext).isValid)
        assertFalse(SkillValidator.validateUntrusted(imported().copy(compatibility = SkillCompatibility(minHostVersion = "1.10.0")),
            fullContext.copy(hostVersion = "1.9.9")).isValid)
        assertFalse(SkillValidator.validateUntrusted(imported(), fullContext.copy(hostVersion = "2.0.0")).isValid)
        assertTrue(SkillValidator.validateUntrusted(imported(), fullContext.copy(hostVersion = "1.99.0")).isValid)
    }

    @Test fun validatedManifestIsDefensivelyCopied() {
        val mutable = mutableSetOf("file_read")
        val steps = mutableListOf("Read local file")
        val result = SkillValidator.validateUntrusted(imported().copy(tools = mutable, instructions = steps), fullContext)
        mutable += "terminal_run"; steps += "Bypass host"
        assertEquals(setOf("file_read"), result.skill!!.allowedTools)
        assertEquals(listOf("Read local file"), result.skill!!.manifest.instructions)
        try {
            (result.skill!!.allowedTools as MutableSet<String>).add("terminal_run")
            fail("Validated tool grants must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun changingReturnedBuiltinDoesNotChangeFixedRegistry() {
        val manifest = BuiltinSkillRegistry.find(BuiltinSkillRegistry.WORKSPACE_INSPECTION)!!
        (manifest.tools as MutableSet<String>).add("terminal_run")
        assertFalse("terminal_run" in BuiltinSkillRegistry.find(manifest.id)!!.tools)
        val validated = BuiltinSkillRegistry.validate(manifest.id, fullContext)
        assertFalse("terminal_run" in validated.skill!!.allowedTools)
    }

    @Test fun validJsonParsesThenValidatesAsUntrusted() {
        val parsed = SkillManifestParser.parse(validJson().toString())
        assertTrue(parsed.issues.toString(), parsed.isValid)
        val result = SkillValidator.validateUntrusted(parsed.manifest!!, fullContext)
        assertTrue(result.isValid)
        assertEquals(SkillOrigin.UNTRUSTED_IMPORT, result.skill!!.origin)
    }

    @Test fun manifestTrustFlagsUnknownFieldsWrongTypesAndTrailingInputFailClosed() {
        listOf("trusted", "origin", "registerTool", "grantedPermissions").forEach { flag ->
            val result = SkillManifestParser.parse(validJson().put(flag, true).toString())
            assertFalse(result.isValid)
            assertEquals(SkillIssueCode.UNKNOWN_FIELD, result.issues.single().code)
        }
        val wrongType = validJson()
        wrongType.getJSONObject("constraints").put("allowNetwork", "false")
        assertFalse(SkillManifestParser.parse(wrongType.toString()).isValid)
        assertFalse(SkillManifestParser.parse(validJson().toString() + " trailing").isValid)
        assertFalse(SkillManifestParser.parse("x".repeat(32_001)).isValid)
        assertFalse(SkillManifestParser.parse(validJson().put("requiredPermissions", listOf("ROOT")).toString()).isValid)
    }

    @Test fun parsedEscalationCannotBecomeValidatedSkill() {
        val json = validJson().put("tools", listOf("terminal_run")).put("requiredPermissions", listOf("WORKSPACE_READ", "TERMINAL_EXECUTE"))
        val parsed = SkillManifestParser.parse(json.toString())
        assertTrue(parsed.isValid)
        assertFalse(SkillValidator.validateUntrusted(parsed.manifest!!, fullContext).isValid)
    }

    private fun validJson(): JSONObject = JSONObject("""
        {
          "id":"local.research", "version":"1.0.0", "description":"Read local evidence",
          "tools":["file_read"], "requiredPermissions":["WORKSPACE_READ"], "preconditions":["WORKSPACE_SELECTED"],
          "constraints":{"workspaceOnly":true,"allowNetwork":false,"allowDestructive":false,"allowAutoCommit":false,
            "requireWriteApproval":true,"maxSteps":12,"maxOutputChars":8000},
          "failureHandling":{"mode":"STOP_AND_REPORT","maxRetries":0,"preserveEvidence":true},
          "compatibility":{"manifestVersion":1,"minHostVersion":"1.0.0","maxHostMajor":1},
          "instructions":["Read bounded local evidence"]
        }
    """.trimIndent())
}

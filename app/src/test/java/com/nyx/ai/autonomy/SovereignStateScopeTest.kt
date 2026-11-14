package com.nyx.ai.autonomy

import com.nyx.ai.agent.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignStateScopeTest {
    @Test fun workingStateRoundTripsAndDeduplicatesFacts() {
        val state = SovereignWorkingState.start("Build the app", "Run the build", 1)
            .withFact("The project uses Gradle.")
            .withFact("The project uses Gradle.")
            .withRequirement("Tests must pass.")
            .withError("Initial build failed with exit 1.")
            .withEvidence("build_run: BUILD SUCCESSFUL")
            .withNextAction("Inspect the failing task.")
        val decoded = SovereignWorkingState.fromJson(state.toJson())
        assertNotNull(decoded)
        assertEquals(1, decoded!!.facts.size)
        assertEquals("Tests must pass.", decoded.requirements.single())
        assertEquals("Inspect the failing task.", decoded.nextAction)
        assertTrue(decoded.promptBlock().contains("SOVEREIGN WORKING STATE"))
        val checkpoint = org.json.JSONObject()
            .put("phase", "running")
            .put("attempt", 2)
            .put("sovereignState", state.toJson())
            .toString()
        assertEquals("Build the app", SovereignWorkingState.fromCheckpoint(checkpoint)?.objective)
    }

    @Test fun inferenceScopeExcludesMutationAndCommands() {
        val scope = TaskCapabilityScope.forTask(
            "inference",
            configured = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "project_run"),
            available = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "project_run"),
        )
        assertTrue("file_read" in scope.allowed)
        assertFalse("file_write" in scope.allowed)
        assertFalse("terminal_run" in scope.allowed)
        assertFalse("project_run" in scope.allowed)
        assertTrue(scope.readOnly)
    }

    @Test fun filesystemScopeCanWriteButCannotRunCommands() {
        val scope = TaskCapabilityScope.forTask(
            "filesystem",
            configured = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "project_run"),
            available = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "project_run"),
        )
        assertTrue("file_write" in scope.allowed)
        assertFalse("terminal_run" in scope.allowed)
        assertFalse("project_run" in scope.allowed)
    }

    @Test fun verificationScopeAllowsTestsButNotWritesOrArbitraryShell() {
        val scope = TaskCapabilityScope.forTask(
            "verification",
            configured = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "test_run", "build_run"),
            available = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "test_run", "build_run"),
        )
        assertTrue("test_run" in scope.allowed)
        assertFalse("build_run" in scope.allowed)
        assertFalse("file_write" in scope.allowed)
        assertFalse("terminal_run" in scope.allowed)
    }

    @Test fun developmentScopeKeepsFullApprovedCapabilities() {
        val scope = TaskCapabilityScope.forTask(
            "development",
            configured = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "project_run", "build_run", "test_run"),
            available = AgentTools.DEFAULT_TOOLS + setOf("file_read", "file_write", "terminal_run", "project_run", "build_run", "test_run"),
        )
        assertTrue("terminal_run" in scope.allowed)
        assertTrue("file_write" in scope.allowed)
        assertTrue("build_run" in scope.allowed)
        assertFalse(scope.readOnly)
    }

    @Test fun unavailableTerminalCapabilitiesAreNeverScopedIn() {
        val scope = TaskCapabilityScope.forTask(
            "development",
            configured = AgentTools.DEFAULT_TOOLS + setOf("terminal_run", "file_read"),
            available = AgentTools.DEFAULT_TOOLS,
            terminalAvailable = false,
        )
        assertFalse("terminal_run" in scope.allowed)
        assertFalse("file_read" in scope.allowed)
    }
}

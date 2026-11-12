package com.nyx.ai.autonomy

import com.nyx.ai.terminal.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {

    private fun input(
        providerConfigured: Boolean = true,
        modelSelected: Boolean = true,
        requiresTerminal: Boolean = false,
        environmentVerified: Boolean = true,
        environmentKind: String = "termux_host",
        workspaceAuthorized: Boolean = true,
        workspaceAccessible: Boolean? = true,
        requiredTools: Set<String> = emptySet(),
        capabilities: Map<String, CapabilityState> = emptyMap(),
        availableMemoryMb: Long? = 4096L,
        requiresDevelopment: Boolean = false,
    ): PreflightInput = PreflightInput(
        providerConfigured = providerConfigured,
        modelSelected = modelSelected,
        requiresTerminal = requiresTerminal,
        environmentVerified = environmentVerified,
        environmentKind = environmentKind,
        workspaceAuthorized = workspaceAuthorized,
        workspaceAccessible = workspaceAccessible,
        requiredTools = requiredTools,
        capabilities = capabilities,
        availableMemoryMb = availableMemoryMb,
        requiresDevelopment = requiresDevelopment,
    )

    @Test
    fun `ready when nothing is missing`() {
        val result = Preflight.evaluate(input())
        assertTrue(result.ready)
        assertTrue(result.blockers.isEmpty())
        assertEquals("Ready", result.summary)
    }

    @Test
    fun `provider and model are always required`() {
        val result = Preflight.evaluate(input(providerConfigured = false, modelSelected = false))
        assertFalse(result.ready)
        assertEquals(listOf("provider_missing", "model_missing"), result.blockers.map { it.code })
        assertEquals("2 blockers: provider_missing, model_missing", result.summary)
    }

    @Test
    fun `terminal objectives need an available termux`() {
        val blank = Preflight.evaluate(input(requiresTerminal = true, environmentKind = ""))
        assertEquals(listOf("termux_unavailable"), blank.blockers.map { it.code })

        val explicit = Preflight.evaluate(input(requiresTerminal = true, environmentKind = "none"))
        assertEquals("termux_unavailable", explicit.blockers.single().code)

        val absent = Preflight.evaluate(input(requiresTerminal = true, environmentKind = "unavailable"))
        assertEquals("termux_unavailable", absent.blockers.single().code)
    }

    @Test
    fun `development objectives need a verified environment`() {
        val result = Preflight.evaluate(input(requiresDevelopment = true, environmentVerified = false))
        assertEquals("environment_unverified", result.blockers.single().code)
        assertTrue(result.blockers.single().message.contains("Termux"))
    }

    @Test
    fun `the environment label reflects a proot guest`() {
        val result = Preflight.evaluate(
            input(
                requiresTerminal = true,
                environmentKind = "proot:debian",
                environmentVerified = false,
            ),
        )
        assertTrue(result.blockers.single().message.contains("PRoot debian"))
    }

    @Test
    fun `workspace authorization and access are separate blockers`() {
        val unauthorized = Preflight.evaluate(input(requiresTerminal = true, workspaceAuthorized = false))
        assertEquals(listOf("workspace_unauthorized"), unauthorized.blockers.map { it.code })

        val inaccessible = Preflight.evaluate(
            input(requiresTerminal = true, workspaceAuthorized = true, workspaceAccessible = false),
        )
        assertEquals(listOf("workspace_inaccessible"), inaccessible.blockers.map { it.code })
    }

    @Test
    fun `an unchecked workspace is not reported as inaccessible`() {
        val result = Preflight.evaluate(input(requiresTerminal = true, workspaceAccessible = null))
        assertTrue(result.ready)
    }

    @Test
    fun `terminal requirements are ignored for chat-only objectives`() {
        val result = Preflight.evaluate(
            input(
                requiresTerminal = false,
                requiresDevelopment = false,
                environmentKind = "",
                environmentVerified = false,
                workspaceAuthorized = false,
                workspaceAccessible = false,
            ),
        )
        assertTrue(result.ready)
    }

    @Test
    fun `each tool state maps to its own blocker code`() {
        val result = Preflight.evaluate(
            input(
                requiredTools = setOf("git", "java", "kotlinc", "adb"),
                capabilities = mapOf(
                    "git" to CapabilityState.VERIFIED,
                    "java" to CapabilityState.MISSING,
                    "kotlinc" to CapabilityState.INCOMPATIBLE,
                    "adb" to CapabilityState.PERMISSION_DENIED,
                ),
            ),
        )
        assertEquals(
            listOf(
                "tool_permission_denied:adb",
                "tool_missing:java",
                "tool_incompatible:kotlinc",
            ),
            result.blockers.map { it.code },
        )
    }

    @Test
    fun `tools without evidence are reported as not checked`() {
        val result = Preflight.evaluate(input(requiredTools = setOf("cmake")))
        assertEquals(listOf("tool_not_checked:cmake"), result.blockers.map { it.code })
    }

    @Test
    fun `tools that are not required never block`() {
        val result = Preflight.evaluate(
            input(requiredTools = emptySet(), capabilities = mapOf("git" to CapabilityState.MISSING)),
        )
        assertTrue(result.ready)
    }

    @Test
    fun `blank and duplicate tool names are normalized`() {
        val result = Preflight.evaluate(
            input(
                requiredTools = setOf(" git ", "", "git"),
                capabilities = mapOf("git" to CapabilityState.MISSING),
            ),
        )
        assertEquals(listOf("tool_missing:git"), result.blockers.map { it.code })
    }

    @Test
    fun `memory is only enforced for development`() {
        val low = Preflight.evaluate(input(requiresDevelopment = true, availableMemoryMb = 384L))
        assertEquals("memory_low", low.blockers.single().code)
        assertTrue(low.blockers.single().message.contains("384"))

        val chat = Preflight.evaluate(input(requiresDevelopment = false, availableMemoryMb = 128L))
        assertTrue(chat.ready)

        val unknown = Preflight.evaluate(input(requiresDevelopment = true, availableMemoryMb = null))
        assertTrue(unknown.ready)

        val enough = Preflight.evaluate(input(requiresDevelopment = true, availableMemoryMb = 512L))
        assertTrue(enough.ready)
    }

    @Test
    fun `summary is capped for many blockers`() {
        val result = Preflight.evaluate(
            input(
                providerConfigured = false,
                modelSelected = false,
                requiresTerminal = true,
                environmentKind = "",
                workspaceAuthorized = false,
                requiredTools = setOf("a", "b", "c", "d", "e", "f"),
                availableMemoryMb = 100L,
                requiresDevelopment = true,
            ),
        )
        assertEquals(11, result.blockers.size)
        assertEquals(
            "11 blockers: provider_missing, model_missing, termux_unavailable, workspace_unauthorized, " +
                "tool_not_checked:a, +6 more",
            result.summary,
        )
    }

    @Test
    fun `every blocker carries a message and a short action`() {
        val result = Preflight.evaluate(
            input(
                providerConfigured = false,
                modelSelected = false,
                requiresTerminal = true,
                environmentKind = "",
                requiredTools = setOf("git", "java", "kotlinc", "adb"),
                capabilities = mapOf(
                    "java" to CapabilityState.MISSING,
                    "kotlinc" to CapabilityState.INCOMPATIBLE,
                    "adb" to CapabilityState.PERMISSION_DENIED,
                ),
                requiresDevelopment = true,
                availableMemoryMb = 256L,
            ),
        )
        assertTrue(result.blockers.size >= 8)
        for (blocker in result.blockers) {
            assertTrue("${blocker.code} has no message", blocker.message.length > 10)
            assertTrue("${blocker.code} has no action", blocker.action.isNotBlank())
            assertTrue("${blocker.code} action is too long", blocker.action.length <= 80)
        }
    }

    @Test
    fun `evaluation is deterministic`() {
        val first = Preflight.evaluate(input(providerConfigured = false, requiredTools = setOf("git", "adb")))
        val second = Preflight.evaluate(input(providerConfigured = false, requiredTools = setOf("git", "adb")))
        assertEquals(first, second)
        assertEquals(first.summary, second.summary)
    }
}

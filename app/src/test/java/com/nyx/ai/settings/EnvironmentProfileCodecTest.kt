package com.nyx.ai.settings

import com.nyx.ai.terminal.CapabilityState
import com.nyx.ai.terminal.EnvironmentCapabilities
import com.nyx.ai.terminal.ToolCapability
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentProfileCodecTest {

    private fun profile(): EnvironmentCapabilities = EnvironmentCapabilities(
        environmentId = "proot:debian",
        kind = "proot:debian",
        checkedAt = 1_700_000_000_000L,
        tools = mapOf(
            "git" to ToolCapability("git", CapabilityState.VERIFIED, "/usr/bin/git", "git version 2.43.0"),
            "python3" to ToolCapability(
                "python3",
                CapabilityState.PERMISSION_DENIED,
                "/usr/bin/python3",
                null,
                "python3: Permission denied",
            ),
            "kotlinc" to ToolCapability("kotlinc", CapabilityState.MISSING),
        ),
        storageFreeBytes = 8_589_934_592L,
        workspaceAccessible = true,
        source = "probe",
    )

    @Test
    fun `round trip preserves every field`() {
        val original = profile()
        val decoded = EnvironmentProfileCodec.decode(EnvironmentProfileCodec.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `absent optional fields survive the round trip`() {
        val original = EnvironmentCapabilities("termux_host", "termux_host", 42L)
        val decoded = EnvironmentProfileCodec.decode(EnvironmentProfileCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `encoded json is versioned and bounded`() {
        val root = JSONObject(EnvironmentProfileCodec.encode(profile()))
        assertEquals(EnvironmentProfileCodec.SCHEMA_VERSION, root.getInt("schemaVersion"))
        assertEquals("proot:debian", root.getString("environmentId"))
        assertEquals("proot:debian", root.getString("kind"))
        assertEquals(1_700_000_000_000L, root.getLong("checkedAt"))
        assertEquals(3, root.getJSONObject("tools").length())
    }

    @Test
    fun `corrupt json is rejected`() {
        assertNull(EnvironmentProfileCodec.decode("{not json"))
        assertNull(EnvironmentProfileCodec.decode("[]"))
        assertNull(EnvironmentProfileCodec.decode(""))
        assertNull(EnvironmentProfileCodec.decode("   "))
        assertNull(EnvironmentProfileCodec.decode("null"))
        assertNull(EnvironmentProfileCodec.decode("{\"schemaVersion\":1}"))
    }

    @Test
    fun `missing required fields are rejected`() {
        assertNull(
            EnvironmentProfileCodec.decode(
                """{"schemaVersion":1,"kind":"termux_host","checkedAt":1}""",
            ),
        )
        assertNull(
            EnvironmentProfileCodec.decode(
                """{"schemaVersion":1,"environmentId":"e","checkedAt":1}""",
            ),
        )
        assertNull(
            EnvironmentProfileCodec.decode(
                """{"schemaVersion":1,"environmentId":"e","kind":"termux_host"}""",
            ),
        )
        assertNull(
            EnvironmentProfileCodec.decode(
                """{"schemaVersion":1,"environmentId":"e","kind":"termux_host","checkedAt":0}""",
            ),
        )
        assertNull(
            EnvironmentProfileCodec.decode(
                """{"schemaVersion":1,"environmentId":"","kind":"termux_host","checkedAt":1}""",
            ),
        )
    }

    @Test
    fun `future schema versions are rejected`() {
        assertNull(
            EnvironmentProfileCodec.decode(
                """{"schemaVersion":2,"environmentId":"e","kind":"termux_host","checkedAt":1}""",
            ),
        )
    }

    @Test
    fun `oversized input is rejected`() {
        val huge = "x".repeat(EnvironmentProfileCodec.MAX_TEXT_CHARS + 1)
        assertNull(EnvironmentProfileCodec.decode(huge))
    }

    @Test
    fun `invalid tool entries are skipped without losing the profile`() {
        val text = """
            {"schemaVersion":1,"environmentId":"e","kind":"termux_host","checkedAt":5,
             "tools":{"git":{"state":"VERIFIED","path":"/usr/bin/git"},
                      "bad name":{"state":"VERIFIED","path":"/usr/bin/x"},
                      "java":{"state":"BROKEN"},
                      "node":{"state":"VERIFIED","path":"relative/node"}}}
        """.trimIndent()
        val decoded = EnvironmentProfileCodec.decode(text) ?: error("profile was rejected")
        assertEquals(setOf("git"), decoded.tools.keys)
        assertEquals(CapabilityState.VERIFIED, decoded.stateOf("git"))
        assertEquals("/usr/bin/git", decoded.tools["git"]?.path)
    }

    @Test
    fun `tool states decode case insensitively`() {
        val text = """{"schemaVersion":1,"environmentId":"e","kind":"termux_host","checkedAt":5,
            "tools":{"git":{"state":"verified","path":"/usr/bin/git"}}}"""
        val decoded = EnvironmentProfileCodec.decode(text) ?: error("profile was rejected")
        assertEquals(CapabilityState.VERIFIED, decoded.stateOf("git"))
    }

    @Test
    fun `tool entries are capped`() {
        val tools = (1..100).joinToString(",") { """"t$it":{"state":"MISSING"}""" }
        val text = """{"schemaVersion":1,"environmentId":"e","kind":"termux_host","checkedAt":5,
            "tools":{$tools}}"""
        val decoded = EnvironmentProfileCodec.decode(text) ?: error("profile was rejected")
        assertEquals(EnvironmentProfileCodec.MAX_TOOLS, decoded.tools.size)
    }

    @Test
    fun `encoded versions are truncated to the model bound`() {
        val original = EnvironmentCapabilities(
            environmentId = "termux_host",
            kind = "termux_host",
            checkedAt = 1L,
            tools = mapOf(
                "git" to ToolCapability("git", CapabilityState.VERIFIED, "/usr/bin/git", "v".repeat(500)),
            ),
        )
        val decoded = EnvironmentProfileCodec.decode(EnvironmentProfileCodec.encode(original))
        assertEquals(
            EnvironmentProfileCodec.MAX_VERSION_CHARS,
            decoded?.tools?.get("git")?.version?.length ?: 0,
        )
    }

    @Test
    fun `absent source falls back to probe`() {
        val decoded = EnvironmentProfileCodec.decode(
            """{"schemaVersion":1,"environmentId":"e","kind":"termux_host","checkedAt":5}""",
        )
        assertEquals("probe", decoded?.source)
        assertTrue(decoded?.tools?.isEmpty() == true)
    }

    @Test
    fun `negative storage and explicit nulls are tolerated`() {
        val text = """{"schemaVersion":1,"environmentId":"e","kind":"termux_host","checkedAt":5,
            "storageFreeBytes":-1,"workspaceAccessible":null}"""
        val decoded = EnvironmentProfileCodec.decode(text) ?: error("profile was rejected")
        assertNull(decoded.storageFreeBytes)
        assertNull(decoded.workspaceAccessible)
    }

    @Test
    fun `environment ids with control characters are rejected`() {
        val text = """{"schemaVersion":1,"environmentId":"e\u0000x","kind":"termux_host","checkedAt":5}"""
        assertNull(EnvironmentProfileCodec.decode(text))
    }
}

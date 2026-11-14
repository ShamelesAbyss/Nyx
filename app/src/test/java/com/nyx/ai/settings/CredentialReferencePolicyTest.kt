package com.nyx.ai.settings

import org.junit.Assert.*
import org.junit.Test

class CredentialReferencePolicyTest {
    @Test fun `existing inference reference storage identity stays unchanged`() {
        listOf("fixture-key", "123e4567-e89b-12d3-a456-426614174000", "legacy_credential").forEach {
            assertEquals(it, CredentialReferencePolicy.fileStem(it))
        }
    }

    @Test fun `gateway caller reference maps deterministically to a disjoint safe filename`() {
        val reference = "gateway:gateway-fixture"
        val stem = CredentialReferencePolicy.fileStem(reference)
        assertTrue(stem.matches(Regex("gateway\\.[a-f0-9]{64}")))
        assertEquals(stem, CredentialReferencePolicy.fileStem(reference))
        assertNotEquals(stem, CredentialReferencePolicy.fileStem("gateway:gateway-other"))
        assertNotEquals(stem, CredentialReferencePolicy.fileStem("gateway-fixture"))
        assertTrue(CredentialReferencePolicy.fileStem("gateway:fixture.id").startsWith("gateway."))
    }

    @Test fun `gateway refs cannot escape storage or silently alias logical identity`() {
        listOf("gateway:", "gateway:../credentials", "gateway:fixture/secret", "gateway:fixture\\secret", "gateway:fixture:other",
            "../secret", "gateway:UPPER", "gateway:fixture\n", "gateway:" + "x".repeat(65), "gateway.fixture").forEach {
            assertThrows(IllegalArgumentException::class.java) { CredentialReferencePolicy.fileStem(it) }
        }
    }
}

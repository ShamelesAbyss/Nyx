package com.nyx.ai.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialTransportPolicyTest {
    @Test
    fun allowsHttpsRemoteAndLoopbackDevelopment() {
        assertTrue(CredentialTransportPolicy.allows("https://api.example.test/v1"))
        assertTrue(CredentialTransportPolicy.allows("http://127.0.0.1:8080/v1"))
        assertTrue(CredentialTransportPolicy.allows("http://localhost:8080/v1"))
        assertTrue(CredentialTransportPolicy.allows("http://[::1]:8080/v1"))
    }

    @Test
    fun rejectsRemoteHttpAndCredentialBearingUrls() {
        assertFalse(CredentialTransportPolicy.allows("http://api.example.test/v1"))
        assertFalse(CredentialTransportPolicy.allows("https://user:password@api.example.test/v1"))
        assertFalse(CredentialTransportPolicy.allows("https://api.example.test/v1?token=secret"))
    }
}

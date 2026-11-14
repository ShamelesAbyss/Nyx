package com.nyx.ai.network

import com.nyx.ai.autonomy.policy.AutonomyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {
    @Test
    fun `https public read is allowed in smart mode`() {
        val result = NetworkPolicy.assess("https://example.com/docs", upload = false, mode = AutonomyMode.SMART)
        assertEquals(NetworkDecision.ALLOW, result.decision)
        assertEquals("example.com", result.host)
    }

    @Test
    fun `public http is refused`() {
        val result = NetworkPolicy.assess("http://example.com/docs", upload = false, mode = AutonomyMode.FULL)
        assertEquals(NetworkDecision.DENY, result.decision)
        assertTrue(result.reason.contains("HTTPS"))
    }

    @Test
    fun `public tools deny loopback and emulator host even in full mode`() {
        listOf("127.0.0.1", "localhost", "service.localhost", "[::1]", "10.0.2.2").forEach { host ->
            listOf("http", "https").forEach { scheme ->
                assertEquals(NetworkDecision.DENY, NetworkPolicy.assess("$scheme://$host/api/tags", false, AutonomyMode.FULL).decision)
            }
        }
        assertTrue(NetworkPolicy.isLoopbackHost("localhost"))
        assertFalse(NetworkPolicy.isLoopbackHost("10.0.2.2"))
    }

    @Test
    fun `embedded credentials are refused`() {
        val result = NetworkPolicy.assess("https://user:secret@example.com/x", upload = false, mode = AutonomyMode.FULL)
        assertEquals(NetworkDecision.DENY, result.decision)
    }

    @Test
    fun `private addresses are refused even over https`() {
        assertEquals(NetworkDecision.DENY, NetworkPolicy.assess("https://192.168.1.10/x", false, AutonomyMode.FULL).decision)
        assertEquals(NetworkDecision.DENY, NetworkPolicy.assess("https://169.254.169.254/latest", false, AutonomyMode.FULL).decision)
        assertEquals(NetworkDecision.DENY, NetworkPolicy.assess("https://10.1.2.3/x", false, AutonomyMode.FULL).decision)
        assertTrue(NetworkPolicy.isPrivateHost("172.16.4.5"))
        assertFalse(NetworkPolicy.isPrivateHost("172.32.4.5"))
    }

    @Test
    fun `manual mode asks for ordinary reads`() {
        assertEquals(NetworkDecision.ASK, NetworkPolicy.assess("https://example.com", false, AutonomyMode.MANUAL).decision)
    }

    @Test
    fun `uploads always ask outside manual`() {
        assertEquals(NetworkDecision.ASK, NetworkPolicy.assess("https://example.com/upload", true, AutonomyMode.SMART).decision)
        assertEquals(NetworkDecision.ASK, NetworkPolicy.assess("https://example.com/upload", true, AutonomyMode.FULL).decision)
    }

    @Test
    fun `full mode allows ordinary reads but refuses unusual ports`() {
        assertEquals(NetworkDecision.ALLOW, NetworkPolicy.assess("https://example.com/a", false, AutonomyMode.FULL).decision)
        assertEquals(NetworkDecision.DENY, NetworkPolicy.assess("https://example.com:8443/a", false, AutonomyMode.FULL).decision)
    }

    @Test
    fun `redirects cannot downgrade public https or reach private hosts`() {
        assertFalse(NetworkPolicy.redirectAllowed("https://example.com/a", "http://example.com/b"))
        assertFalse(NetworkPolicy.redirectAllowed("https://example.com/a", "https://127.0.0.1/b"))
        assertFalse(NetworkPolicy.redirectAllowed("https://example.com/a", "https://example.com:8443/b"))
        assertFalse(NetworkPolicy.redirectAllowed("http://127.0.0.1/a", "http://example.com/b"))
        assertTrue(NetworkPolicy.redirectAllowed("https://example.com/a", "https://example.org/b"))
    }
}

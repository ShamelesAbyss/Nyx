package com.nyx.ai.network

import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ExecutionTimeoutPolicyStore
import com.nyx.ai.autonomy.ExecutionTimeoutProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionTimeoutIntegrationTest {
    @Test fun ordinaryHttpChatRetainsExistingProviderDefaultsWithoutProfileSelection() {
        val client = OllamaClient("http://localhost:11434")
        val http = client.httpFor(InferenceOptions())
        assertEquals(180_000, http.readTimeoutMillis)
        assertEquals(15 * 60_000, http.callTimeoutMillis)
        assertEquals(5_000, http.connectTimeoutMillis)
        assertFalse(http.followRedirects)
    }

    @Test fun patientHttpHasNoShortReadOrCallCapAndKeepsSecurityAndPool() {
        val client = OllamaClient("http://localhost:11434")
        val defaults = client.httpFor(InferenceOptions())
        val policy = ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.PATIENT)
        val selected = client.httpFor(InferenceOptions(executionTimeouts = policy))
        assertEquals(60 * 60_000, selected.readTimeoutMillis)
        assertEquals(60 * 60_000, selected.callTimeoutMillis)
        assertEquals(5_000, selected.connectTimeoutMillis)
        assertFalse(selected.followRedirects)
        assertFalse(selected.followSslRedirects)
        assertSame(defaults.connectionPool, selected.connectionPool)
        assertSame(defaults.dispatcher, selected.dispatcher)
    }

    @Test fun snapshotsRoundTripCanonicalApprovedProfilesAndDefaultLegacyToStandard() {
        val policy = ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.CUSTOM, 720, true)
        val config = JSONObject().put("timeoutPolicy", ExecutionTimeoutPolicyStore.encode(policy))
        assertEquals(policy, ExecutionTimeoutPolicyStore.fromSnapshot(config))
        assertEquals(ExecutionTimeoutPolicy.forProfile(), ExecutionTimeoutPolicyStore.fromSnapshot(JSONObject()))
        assertFalse(config.toString().contains("credential"))
    }

    @Test fun invalidUnapprovedOverflowAndNullSnapshotsFailClosed() {
        listOf(
            JSONObject().put("profile", "EXTENDED"),
            JSONObject().put("profile", "CUSTOM").put("customMinutes", 60),
            JSONObject().put("profile", "CUSTOM").put("customMinutes", Long.MAX_VALUE).put("explicitLongRunApproval", true),
            JSONObject().put("profile", "CUSTOM").put("customMinutes", 60.5).put("explicitLongRunApproval", true),
            JSONObject().put("profile", "CUSTOM").put("customMinutes", "60").put("explicitLongRunApproval", true),
            JSONObject().put("profile", "unknown"),
        ).forEach { snapshot ->
            try { ExecutionTimeoutPolicyStore.fromSnapshot(JSONObject().put("timeoutPolicy", snapshot)); fail("Expected rejection") }
            catch (_: Exception) { }
        }
        try { ExecutionTimeoutPolicyStore.fromSnapshot(JSONObject().put("timeoutPolicy", JSONObject.NULL)); fail("Expected null rejection") }
        catch (_: Exception) { }
    }
}

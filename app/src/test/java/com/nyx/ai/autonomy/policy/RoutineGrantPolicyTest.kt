package com.nyx.ai.autonomy.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RoutineGrantPolicyTest {
    @Test
    fun `no workspace produces no grants and no wildcard`() {
        val grants = RoutineGrantPolicy.build("debian", "", 1_000L) { "grant" }
        assertTrue(grants.isEmpty())
        val nullPath = RoutineGrantPolicy.build("debian", null, 1_000L) { "grant" }
        assertTrue(nullPath.isEmpty())
    }

    @Test
    fun `relative or root paths are refused`() {
        assertEquals(null, RoutineGrantPolicy.normalizeWorkspace("relative/path"))
        assertEquals(null, RoutineGrantPolicy.normalizeWorkspace("/"))
        assertEquals(null, RoutineGrantPolicy.normalizeWorkspace("/root//project"))
        assertEquals("/root/project", RoutineGrantPolicy.normalizeWorkspace("/root/project/"))
    }

    @Test
    fun `every routine grant is environment and workspace scoped`() {
        val grants = RoutineGrantPolicy.build("debian", "/root/project", 1_000L) { "grant" }
        assertEquals(RoutineGrantPolicy.CAPABILITIES, grants.map { it.capability }.toSet())
        assertTrue(grants.all { it.scope == "/root/project" })
        assertTrue(grants.all { it.environmentId == "debian" })
        assertTrue(grants.none { it.scope == "*" })
    }

    @Test
    fun `emergency stop is visible to concurrent authorization`() {
        val engine = AutonomyPolicyEngine(AutonomyMode.FULL)
        val pool = Executors.newFixedThreadPool(4)
        val failures = AtomicInteger(0)
        try {
            repeat(300) { index ->
                pool.submit {
                    runCatching {
                        if (index % 3 == 0) engine.grant(
                            CapabilityGrant("g-$index", "workspace.read", "debian", "/root/p", 0)
                        )
                        if (index % 5 == 0) engine.revoke("g-${(index / 5) * 3}")
                        engine.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/p"))
                        engine.snapshot()
                    }.onFailure { failures.incrementAndGet() }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
        assertEquals(0, failures.get())
        engine.emergencyStop()
        assertTrue(engine.stopped)
        val afterStop = engine.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/p"))
        assertFalse(afterStop.allowed)
        assertEquals("emergency_stop", afterStop.reason)
    }

    @Test
    fun `revocation and authorization do not lose updates`() {
        val engine = AutonomyPolicyEngine(
            AutonomyMode.FULL,
            listOf(CapabilityGrant("g1", "workspace.read", "debian", "/root/p", 0)),
        )
        val pool = Executors.newFixedThreadPool(4)
        val failures = AtomicInteger(0)
        try {
            repeat(200) {
                pool.submit {
                    runCatching {
                        engine.grant(CapabilityGrant("g-$it", "workspace.read", "debian", "/root/p", 0))
                        engine.authorize(ActionRequest(ActionKind.READ_WORKSPACE, "/root/p"))
                        engine.snapshot()
                    }.onFailure { failures.incrementAndGet() }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
        assertEquals(0, failures.get())
        assertTrue(engine.revoke("g1"))
        assertTrue(engine.snapshot().grants.none { it.id == "g1" && !it.revoked })
    }
}

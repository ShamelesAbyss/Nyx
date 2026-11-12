package com.nyx.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRolesTest {
    @Test
    fun `verification kind always maps to the verifier role`() {
        assertEquals(WorkerRole.VERIFIER, WorkerRoles.forTask("verification", "anything"))
    }

    @Test
    fun `environment titles map to the environment role`() {
        assertEquals(WorkerRole.ENVIRONMENT, WorkerRoles.forTask("terminal", "Prepare the toolchain"))
        assertEquals(WorkerRole.ENVIRONMENT, WorkerRoles.forTask("inference", "Check environment readiness"))
    }

    @Test
    fun `development kind maps to the coder role`() {
        assertEquals(WorkerRole.CODER, WorkerRoles.forTask("development", "Fix the parser"))
    }

    @Test
    fun `documentation titles map to the documenter role`() {
        assertEquals(WorkerRole.DOCUMENTER, WorkerRoles.forTask("inference", "Document the README"))
    }

    @Test
    fun `roles carry bounded non-empty instructions`() {
        WorkerRole.entries.forEach { role ->
            val prompt = WorkerRoles.prompt(role)
            assertTrue(role.name, prompt.isNotBlank())
            assertTrue(role.name, prompt.length < 400)
        }
    }

    @Test
    fun `coder and environment roles cannot run concurrently with themselves`() {
        assertFalse(WorkerRoles.mayRunConcurrently(WorkerRole.CODER, WorkerRole.CODER))
        assertFalse(WorkerRoles.mayRunConcurrently(WorkerRole.ENVIRONMENT, WorkerRole.ENVIRONMENT))
        assertTrue(WorkerRoles.mayRunConcurrently(WorkerRole.CODER, WorkerRole.VERIFIER))
    }
}

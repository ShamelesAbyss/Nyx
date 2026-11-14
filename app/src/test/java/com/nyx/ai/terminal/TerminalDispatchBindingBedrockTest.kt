package com.nyx.ai.terminal

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TerminalDispatchBindingBedrockTest {
    private val request = ControlledShell.request("printf owned", TerminalEnvironment.DEBIAN_PROOT, "/project", 10, false)
    private val identity = TerminalCommandIdentity("structured_reader", "/usr/bin/printf", listOf("owned"), "/project",
        "structured_readonly", TerminalCommandIdentity.digest(request.arguments.last()))
    private val operation = TerminalOperationContext(workspace = "/project")
    private val scope = TerminalExecutionScope("objective", "task")
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid binding admitted") } catch (_: IllegalArgumentException) { } }

    @Test fun journalRoundtripRetainsActualTaskAndBoundedCommandIdentity() {
        val root = Files.createTempDirectory("bedrock-command-identity").toFile()
        try {
            val bound = TerminalDispatchBinding.bind(request, operation, scope, identity)
            assertEquals("objective", bound.objectiveId); assertEquals("task", bound.taskId)
            assertEquals("/usr/bin/env", bound.commandPath)
            val registry = TerminalOperationRegistry(TerminalOperationJournal(root))
            registry.enqueue(bound); assertTrue(registry.tryStart(bound.operationId, 3))
            val recovered = TerminalOperationRegistry(TerminalOperationJournal(root)).snapshot().single()
            assertEquals(identity, recovered.context.commandIdentity)
            assertEquals(bound.argumentDigest, recovered.context.argumentDigest)
            assertEquals(TerminalOperationState.UNCERTAIN, recovered.state)
        } finally { root.deleteRecursively() }
    }

    @Test fun foreignTaskWorkspaceDigestAndLoginShellCannotForgeCommandWitness() {
        rejected { TerminalDispatchBinding.bind(request, operation.copy(taskId = "other"), scope, identity) }
        rejected { TerminalDispatchBinding.bind(request, operation.copy(objectiveId = "other"), scope, identity) }
        rejected { TerminalDispatchBinding.bind(request, operation.copy(workspace = "/other"), scope, identity) }
        rejected { TerminalDispatchBinding.bind(request, operation, scope, identity.copy(scriptSha256 = "a".repeat(64))) }
        rejected { TerminalDispatchBinding.bind(request.copy(commandPath = "/bin/bash"), operation, scope, identity) }
        rejected { TerminalDispatchBinding.bind(request.copy(arguments = listOf("-lc", request.arguments.last())), operation, scope, identity) }
    }

    @Test fun payloadIsDigestOnlyAndPartialIdentityMetadataFailsClosed() {
        val root = Files.createTempDirectory("bedrock-partial-identity").toFile()
        try {
            val bound = TerminalDispatchBinding.bind(request.copy(stdin = "fixture-secret-payload"), operation, scope, null)
            val journal = TerminalOperationJournal(root); val registry = TerminalOperationRegistry(journal)
            registry.enqueue(bound)
            val path = java.io.File(root, "${bound.operationId}.properties")
            assertFalse(path.readText().contains("fixture-secret-payload"))
            path.appendText("\ncommand.sha256=partial\n")
            try { TerminalOperationRegistry(journal); fail("Partial command identity discarded") } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
}

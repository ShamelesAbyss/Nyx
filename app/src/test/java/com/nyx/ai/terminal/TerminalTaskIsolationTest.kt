package com.nyx.ai.terminal

import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ExecutionTimeoutProfile
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TerminalTaskIsolationTest {
    private fun context(id: String, policy: ExecutionTimeoutPolicy = ExecutionTimeoutPolicy.forProfile()) =
        AgentToolTaskContext("objective", id, policy)
    private fun call(command: String, timeout: Long = 30) = ToolCall(command, "terminal_run",
        JSONObject().put("command", command).put("timeout_seconds", timeout))
    private fun gate(root: String) = ModelCommandGate { _, _, timeout ->
        ModelCommandDecision(true, "test approved", workingDirectory = root, timeoutSeconds = timeout, risk = CommandRisk.READ_ONLY)
    }

    @Test fun projectRunHoldsWorkspaceWriterAcrossDiscoveryAndExecution() = runBlocking {
        val firstBuild = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val unrelated = CompletableDeferred<Unit>()
        var siblingDispatched = false
        val executor = TerminalToolExecutor { command, _, _, _, _ ->
            val id = currentCoroutineContext()[TerminalExecutionScope]!!.taskId
            if (id == "sibling") siblingDispatched = true
            if (id == "other") unrelated.complete(Unit)
            val discovery = "MARKER:Makefile\n" + listOf("realpath", "sha256sum", "head", "wc", "base64", "mktemp", "mv", "cp", "mkdir", "cut", "chmod", "find", "timeout", "dd", "cc", "make").joinToString("\n") { "TOOL:$it=/usr/bin/$it" }
            if (id == "first" && "MARKER:" !in command) { firstBuild.complete(Unit); release.await() }
            TerminalCommandResult(id, TerminalResultKind.SUCCEEDED, 0, if ("MARKER:" in command) discovery else id)
        }
        val root = "/tmp/nyx-build-lease"
        val template = TerminalAgentTools(executor, root, workspaceApproved = true)
        val first = launch(TerminalExecutionScope("objective", "first")) {
            template.forTask(context("first")).runProject(com.nyx.ai.development.ProjectRunKind.BUILD)
        }
        withTimeout(3_000) { firstBuild.await() }
        val sibling = launch(TerminalExecutionScope("objective", "sibling"), start = CoroutineStart.UNDISPATCHED) {
            template.forTask(context("sibling")).runProject(com.nyx.ai.development.ProjectRunKind.TEST)
        }
        val other = launch(TerminalExecutionScope("objective", "other")) {
            val otherRoot = "/tmp/nyx-build-unrelated"
            TerminalAgentTools(executor, otherRoot, workspaceApproved = true, modelCommandGate = gate(otherRoot))
                .forTask(context("other")).execute(call("echo independent"))
        }
        withTimeout(3_000) { unrelated.await() }
        assertFalse(siblingDispatched)
        release.complete(Unit)
        withTimeout(3_000) { joinAll(first, sibling, other) }
        assertTrue(siblingDispatched)
    }

    @Test fun threeTaskLocalReadersOverlapAndCannotDrainEachOthersSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val root = "/tmp/nyx-isolation-readers"
        val executor = TerminalToolExecutor { command, _, _, _, _ ->
            if (active.incrementAndGet() == 3) entered.complete(Unit)
            release.await()
            val scope = currentCoroutineContext()[TerminalExecutionScope]!!
            val code = if (scope.taskId == "failure") 7 else 0
            active.decrementAndGet()
            TerminalCommandResult(scope.taskId, if (code == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT,
                code, scope.taskId, "stderr:${scope.taskId}")
        }
        val template = TerminalAgentTools(executor, root, workspaceApproved = true, modelCommandGate = gate(root))
        val tasks = listOf("one", "failure", "three").associateWith { template.forTask(context(it)) }
        withTimeout(3_000) {
            val jobs = tasks.map { (id, tools) -> async(TerminalExecutionScope("objective", id)) { tools.execute(call("echo $id")) } }
            entered.await()
            assertEquals(3, active.get())
            assertTrue(template.drainEvidence().isEmpty())
            release.complete(Unit)
            jobs.awaitAll()
        }
        tasks.forEach { (id, tools) ->
            val evidence = tools.drainEvidence().single()
            assertEquals(id, evidence.taskId)
            assertEquals("objective", evidence.objectiveId)
            assertEquals(id, evidence.stdout)
            assertEquals("stderr:$id", evidence.stderr)
            assertEquals(if (id == "failure") 7 else 0, evidence.exitCode)
            val report = com.nyx.ai.autonomy.TaskVerifier.verify(
                listOf(com.nyx.ai.autonomy.EvidenceSpec(com.nyx.ai.autonomy.EvidenceKind.EXIT_CODE, expected = "0")),
                com.nyx.ai.autonomy.ObservationBundle(exitCode = evidence.exitCode, stdout = evidence.stdout, agentOutput = "Completed successfully"),
            )
            assertEquals(id != "failure", report.passed)
            assertTrue(tools.drainEvidence().isEmpty())
        }
    }

    @Test fun writerLeaseBlocksNestedReadersButAllowsUnrelatedWorkspace() = runBlocking {
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val otherEntered = CompletableDeferred<Unit>()
        var nestedEntered = false
        val writer = launch { TerminalToolWorkspaceRegistry.withLease(TerminalOperationContext(workspace = "/tmp/nyx-conflict", readOnly = false)) {
            writerEntered.complete(Unit); releaseWriter.await()
        } }
        writerEntered.await()
        val nested = launch(start = CoroutineStart.UNDISPATCHED) {
            TerminalToolWorkspaceRegistry.withLease(TerminalOperationContext(workspace = "/tmp/nyx-conflict/child", readOnly = true)) { nestedEntered = true }
        }
        val unrelated = launch { TerminalToolWorkspaceRegistry.withLease(TerminalOperationContext(workspace = "/tmp/nyx-independent", readOnly = false)) { otherEntered.complete(Unit) } }
        withTimeout(3_000) { otherEntered.await() }
        assertFalse(nestedEntered)
        releaseWriter.complete(Unit)
        withTimeout(3_000) { joinAll(writer, nested, unrelated) }
        assertTrue(nestedEntered)
    }

    @Test fun cancelledQueuedLeaseDoesNotReleaseAnotherTaskWriter() = runBlocking {
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var intruderEntered = false
        val writer = launch { TerminalToolWorkspaceRegistry.withLease(TerminalOperationContext(workspace = "/tmp/nyx-cancel")) { acquired.complete(Unit); release.await() } }
        acquired.await()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { TerminalToolWorkspaceRegistry.withLease(TerminalOperationContext(workspace = "/tmp/nyx-cancel")) { fail("queued command admitted") } }
        cancelled.cancelAndJoin()
        val intruder = launch(start = CoroutineStart.UNDISPATCHED) { TerminalToolWorkspaceRegistry.withLease(TerminalOperationContext(workspace = "/tmp/nyx-cancel")) { intruderEntered = true } }
        assertFalse(intruderEntered)
        release.complete(Unit)
        withTimeout(3_000) { joinAll(writer, intruder) }
        assertTrue(intruderEntered)
    }

    @Test fun centralBuildAndTerminalCeilingsAreDistinctAndNotModelWidenable() = runBlocking {
        val policy = ExecutionTimeoutPolicy.forProfile(ExecutionTimeoutProfile.PATIENT)
        val observed = mutableListOf<Pair<String, Long>>()
        val tools = TerminalAgentTools(TerminalToolExecutor { command, _, _, timeout, _ ->
            observed += command to timeout
            val discovery = "MARKER:Makefile\n" + listOf("realpath", "sha256sum", "head", "wc", "base64", "mktemp", "mv", "cp", "mkdir", "cut", "chmod", "find", "timeout", "dd", "cc", "make").joinToString("\n") { "TOOL:$it=/usr/bin/$it" }
            TerminalCommandResult("fixture", TerminalResultKind.SUCCEEDED, 0, if ("MARKER:" in command) discovery else "ok")
        }, "/tmp/nyx-timeout-isolation", workspaceApproved = true, modelCommandGate = gate("/tmp/nyx-timeout-isolation")).forTask(context("timed", policy))
        assertTrue(tools.runProject(com.nyx.ai.development.ProjectRunKind.BUILD).passed)
        tools.execute(call("echo bounded", Long.MAX_VALUE))
        assertEquals(policy.buildMs / 1_000, observed.first { "'/usr/bin/make'" in it.first && "MARKER:" !in it.first }.second)
        assertEquals(policy.terminalMs / 1_000, observed.last().second)
        assertTrue(observed.filter { "MARKER:" in it.first }.all { it.second <= 45 })
    }
}

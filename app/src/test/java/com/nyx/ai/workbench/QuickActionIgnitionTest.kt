package com.nyx.ai.workbench

import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.autonomy.*
import com.nyx.ai.context.ContextStore
import com.nyx.ai.data.ObjectiveEntity
import com.nyx.ai.terminal.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real engine + controlled Linux processes + real retained bytes. DAO/Android are explicit fixtures. */
class QuickActionIgnitionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = CombinedHostFixture.OBJECTIVE
    private fun fixture() = CombinedHostFixture(temporary.newFolder())
    private fun action(type: QuickActionType, workspace: String = "", approved: Boolean = true) = QuickAction(owner, type, approved, workspace)
    private fun context() = AgentToolTaskContext(owner, "quick-task", ExecutionTimeoutPolicy.forProfile())
    private fun project(f: CombinedHostFixture, exit: Int = 0) = File(f.root, "project").apply {
        mkdirs()
        File(this, "test.sh").writeText("#!/bin/sh\n[ 2 -eq 2 ] || exit 9\nprintf 'IGNITION_REAL_ASSERTION\\n'\nexit $exit\n")
    }
    private fun forbiddenProvider() = CombinedHostProvider { _, _, _, _ -> error("Quick Action invoked a model") }

    @Test fun engineInspectsRealProjectWithoutAnyModelRequestOrScriptExecution() = runBlocking {
        val f = fixture(); val p = project(f)
        File(p, "test.sh").writeText("touch script-was-run\n")
        val provider = forbiddenProvider()
        val result = f.engine(provider, f.terminal(p, null)).executeQuickAction(owner,
            action(QuickActionType.INSPECT_PROJECT, p.canonicalPath), f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("${result.error}; ${f.db.events}", "completed", result.status)
        assertTrue(provider.requests.isEmpty())
        assertFalse(File(p, "script-was-run").exists())
        assertTrue(f.db.tasks.single().outcome!!.contains("shell"))
        assertTrue(f.db.evidence.isNotEmpty()); assertTrue(f.db.evidence.all { it.passed })
        assertTrue(f.executor.runs.none { it.identity.operationKind.startsWith("project_run_") })
        val phases = f.db.events.filter { it.kind == "quick_timing" }.map { JSONObject(it.detail!!).getString("phase") }
        assertTrue(phases.containsAll(listOf("permission_validated", "task_created", "execution_dispatch", "operation_dispatch", "first_verified_result", "final_completed")))
        println("IGNITION_INSPECT_TIMINGS=" + f.db.events.filter { it.kind == "quick_timing" }.map { it.detail })
        assertEquals("completed", HostDatabase.create(f.db.file).objective.status)
    }

    @Test fun approvedTestsUseActualFixedCommandExitOutputAndOwnedEvidence() = runBlocking {
        val f = fixture(); val p = project(f); val provider = forbiddenProvider()
        val result = f.engine(provider, f.terminal(p, null)).executeQuickAction(owner,
            action(QuickActionType.RUN_TESTS, p.canonicalPath), f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals(result.error, "completed", result.status)
        assertTrue(provider.requests.isEmpty())
        assertTrue(f.db.tasks.single().outcome!!.contains("IGNITION_REAL_ASSERTION"))
        assertEquals(0, f.executor.runs.single { it.identity.operationKind == "project_run_test" }.exit)
        assertTrue(f.db.events.any { it.kind == "quick_operation_evidence" && JSONObject(it.detail!!).getString("taskId") == f.db.tasks.single().id })
        assertTrue(f.db.evidence.any { it.kind == "exit_code" && it.actual == "0" && it.passed })
        println("IGNITION_TEST_TIMINGS=" + f.db.events.filter { it.kind == "quick_timing" }.map { it.detail })
    }

    @Test fun failingRealTestExitIsRetainedAndNeverClaimedSuccessful() = runBlocking {
        val f = fixture(); val p = project(f, 7)
        val result = f.engine(forbiddenProvider(), f.terminal(p, null)).executeQuickAction(owner,
            action(QuickActionType.RUN_TESTS, p.canonicalPath), f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("needs_attention", result.status)
        assertTrue(f.db.tasks.single().outcome!!.contains("IGNITION_REAL_ASSERTION"))
        assertTrue(f.db.evidence.any { !it.passed && it.actual == "7" })
        assertEquals(7, f.executor.runs.single { it.identity.operationKind == "project_run_test" }.exit)
    }

    @Test fun missingApprovalAndMissingTerminalRejectBeforeDispatch() = runBlocking {
        val f = fixture(); val p = project(f)
        assertThrows(IllegalArgumentException::class.java) { action(QuickActionType.RUN_TESTS, p.canonicalPath, false).validate(owner) }
        val result = f.engine(forbiddenProvider()).executeQuickAction(owner,
            action(QuickActionType.RUN_TESTS, p.canonicalPath), f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("needs_attention", result.status); assertTrue(f.db.tasks.isEmpty()); assertTrue(f.executor.runs.isEmpty())
    }

    @Test fun existingTerminalPermissionGateDeniesAnUnapprovedToolInstance() = runBlocking {
        val f = fixture(); val p = project(f)
        val denied = TerminalAgentTools(f.executor, p.canonicalPath, TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = false)
        val result = f.engine(forbiddenProvider(), denied).executeQuickAction(owner,
            action(QuickActionType.RUN_TESTS, p.canonicalPath), f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("needs_attention", result.status)
        assertTrue(f.executor.runs.isEmpty()); assertTrue(f.db.tasks.none { it.status == "completed" })
    }

    @Test fun workspaceRebindingRejectsBeforeAnyFilesystemOrProcessAccess() = runBlocking {
        val f = fixture(); val p = project(f)
        val result = f.engine(forbiddenProvider(), f.terminal(p, null)).executeQuickAction(owner,
            action(QuickActionType.RUN_TESTS, "/tmp/unapproved"), f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("needs_attention", result.status); assertTrue(f.executor.runs.isEmpty())
    }

    @Test fun malformedPathsOwnersArtifactsAndAdditionalFieldsFailClosed() {
        for (path in listOf("../outside", "/tmp/a/../b", "/tmp/a/./b", "~/project", "/", "/tmp/a\u0000b")) {
            assertThrows("Accepted $path", IllegalArgumentException::class.java) { action(QuickActionType.INSPECT_PROJECT, path).validate(owner) }
        }
        assertThrows(IllegalArgumentException::class.java) { action(QuickActionType.INSPECT_PROJECT, "/tmp/project").validate("foreign") }
        assertThrows(IllegalArgumentException::class.java) { QuickAction(owner, QuickActionType.VERIFY_ARTIFACT, true, sourceObjectiveId = "../source", artifactId = "a", sha256 = "0".repeat(64)).validate(owner) }
        val snapshot = JSONObject().put(QuickAction.KEY, action(QuickActionType.INSPECT_PROJECT, "/tmp/project").toJson().put("command", "evil"))
        assertThrows(IllegalArgumentException::class.java) { QuickAction.fromSnapshot(snapshot) }
    }

    @Test fun artifactVerificationUsesRealRegisteredBytesAndDoesNotCreateANewArtifact() = runBlocking {
        val root = temporary.newFolder(); val source = WorkbenchStore(root, "source-objective")
        val record = source.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "# Real retained bytes\n".toByteArray())
        val selected = QuickAction(owner, QuickActionType.VERIFY_ARTIFACT, true, sourceObjectiveId = source.objectiveId, artifactId = record.id, sha256 = record.sha256)
        val result = QuickActionOperation.run(selected, context(), null, root) { it == source.objectiveId }
        assertEquals(0, result.modelCalls); assertTrue(JSONObject(result.result).getBoolean("verified"))
        assertTrue(result.result.contains("no new file produced")); assertEquals(listOf(record), source.listArtifacts())
        assertEquals(owner, result.evidence.single().objectiveId); assertNull(result.evidence.single().exitCode)
        assertFalse(File(root, owner).exists())
    }

    @Test fun tamperedForeignRemovedOrStaleArtifactsNeverVerify() = runBlocking {
        val root = temporary.newFolder(); val source = WorkbenchStore(root, "source-objective")
        val record = source.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "original".toByteArray())
        val selected = QuickAction(owner, QuickActionType.VERIFY_ARTIFACT, true, sourceObjectiveId = source.objectiveId, artifactId = record.id, sha256 = record.sha256)
        suspend fun rejects(a: QuickAction = selected, exists: Boolean = true) {
            try { QuickActionOperation.run(a, context(), null, root) { exists }; fail("accepted invalid artifact") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
        rejects(exists = false)
        rejects(selected.copy(sourceObjectiveId = "foreign"))
        rejects(selected.copy(sha256 = "0".repeat(64)))
        File(root, "source-objective/${record.relativePath}").writeText("tampered")
        rejects()
    }

    @Test fun cancelBeforeSubmissionCreatesNoTaskAndRunsNoOperation() = runBlocking {
        val f = fixture(); val p = project(f); val control = ExecutionControl().apply { requestCancel() }
        try { f.engine(forbiddenProvider(), f.terminal(p, null)).executeQuickAction(owner,
            action(QuickActionType.RUN_TESTS, p.canonicalPath), f.agent, SchedulingPolicy(), control); fail("ignored cancel") } catch (_: CancellationException) { }
        assertTrue(f.db.tasks.isEmpty()); assertTrue(f.executor.runs.isEmpty()); assertEquals("paused", f.db.objective.status)
    }

    @Test fun interruptionMarksTaskForReviewAndOrdinaryResumeCannotReplayIt() = runBlocking {
        val f = fixture(); val p = project(f)
        val entered = CompletableDeferred<Unit>()
        val terminal = TerminalAgentTools(TerminalToolExecutor { _, _, _, _, _ -> entered.complete(Unit); awaitCancellation() }, p.canonicalPath, workspaceApproved = true)
        val engine = f.engine(forbiddenProvider(), terminal)
        val job = launch { engine.executeQuickAction(owner, action(QuickActionType.RUN_TESTS, p.canonicalPath), f.agent, SchedulingPolicy(), ExecutionControl()) }
        entered.await(); job.cancelAndJoin()
        assertEquals("paused", f.db.objective.status)
        assertTrue(f.db.tasks.none { it.status == "completed" })
        try { engine.resumeAfterReview(owner, f.agent, SchedulingPolicy(), ExecutionControl()); fail("replayed Quick Action") } catch (_: IllegalArgumentException) { }
    }

    @Test fun interruptedBeforeTaskCreationCannotEnterOrdinaryResumeOrInference() = runBlocking {
        val f = fixture(); val p = project(f); val provider = forbiddenProvider()
        val saved = JSONObject().put(QuickAction.KEY, action(QuickActionType.RUN_TESTS, p.canonicalPath).toJson())
        f.db.events += com.nyx.ai.data.ObjectiveEventEntity("saved", owner, null, "execution_config", saved.toString(), System.currentTimeMillis())
        try { f.engine(provider).resumeAfterReview(owner, f.agent, SchedulingPolicy(), ExecutionControl()); fail("replayed config-only action") } catch (_: IllegalArgumentException) { }
        assertTrue(f.db.tasks.isEmpty()); assertTrue(f.executor.runs.isEmpty()); assertTrue(provider.requests.isEmpty())
    }

    @Test fun oldConfigurationRemainsOrdinaryAndSelectedContractRoundTripsExactly() {
        assertNull(QuickAction.fromSnapshot(JSONObject()))
        val original = action(QuickActionType.INSPECT_PROJECT, "/tmp/project")
        assertEquals(original, QuickAction.fromSnapshot(JSONObject().put(QuickAction.KEY, original.toJson())))
        assertEquals(1, original.task("agent").evidence.size)
    }

    @Test fun engineVerifiesExistingArtifactWithoutInventingWriteWitnessOrTerminalAccess() = runBlocking {
        val f = fixture()
        val sourceId = "retained-source"
        f.db.additionalObjectives[sourceId] = ObjectiveEntity(sourceId, "Existing deliverable", "Historical objective", status = "completed")
        val source = WorkbenchStore(File(f.root, "workbench"), sourceId)
        val record = source.writeArtifact("report.md", ArtifactFormat.MARKDOWN, "# Retained output\n".toByteArray())
        val selected = QuickAction(owner, QuickActionType.VERIFY_ARTIFACT, true, sourceObjectiveId = sourceId, artifactId = record.id, sha256 = record.sha256)
        val provider = forbiddenProvider()
        val result = f.engine(provider).executeQuickAction(owner, selected, f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals(result.error, "completed", result.status)
        assertTrue(provider.requests.isEmpty()); assertTrue(f.executor.runs.isEmpty())
        assertTrue(f.db.events.none { it.kind == "workbench_artifact_verified" })
        assertTrue(f.db.tasks.single().outcome!!.contains(record.sha256))
        assertTrue(f.db.evidence.any { it.kind == "checksum_sha256" && it.passed && it.actual == record.sha256 })
        assertEquals(listOf(record), source.listArtifacts()); assertTrue(f.store.listArtifacts().isEmpty())
        println("IGNITION_ARTIFACT_TIMINGS=" + f.db.events.filter { it.kind == "quick_timing" }.map { it.detail })
    }

    @Test fun ordinaryPlannerStillRequestsAPlanAndExecutesItsValidatedTask() = runBlocking {
        val f = fixture()
        f.db.objective = f.db.objective.copy(goal = "Inspect project requirements and summarize results")
        val provider = CombinedHostProvider { count, _, _, options ->
            when (count) {
                1 -> { assertTrue(options.responseSchema!!.contains("steps")); com.nyx.ai.network.ChatResult("{\"steps\":[{\"do\":\"Summarize requirements\",\"worker\":\"inference\"}]}") }
                2 -> com.nyx.ai.network.ChatResult("Actual deterministic fixture provider output")
                3 -> com.nyx.ai.network.ChatResult("{\"done\":true}")
                else -> error("Unexpected model request")
            }
        }
        val started = System.nanoTime()
        val result = f.engine(provider).planAndExecute(owner, f.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals(result.error, "completed", result.status)
        assertEquals(3, provider.requests.size)
        assertTrue(f.db.events.any { it.kind == "planning_input" })
        assertTrue(f.db.events.any { it.kind == "planning_response" })
        assertTrue(f.db.events.none { it.kind == "quick_action" })
        println("IGNITION_ORDINARY_FIXTURE_ONLY totalNanos=" + (System.nanoTime() - started) + " requests=" + provider.requests.size)
    }

    @Test fun deterministicSessionRejectsAnyAccidentalInference() = runBlocking {
        val session = quickActionSession(ExecutionTimeoutPolicy.forProfile())
        try { session.provider.chat("model", emptyList(), null, session.options); fail("allowed inference") } catch (_: IllegalStateException) { }
    }
}

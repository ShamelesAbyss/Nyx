package com.nyx.ai.development

import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.ProviderCapabilities
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DevelopmentOrchestratorTest {
    class Provider(private val reply: suspend (Int, List<ApiMessage>) -> ChatResult) : InferenceProvider {
        var calls = 0
        override val id = "fixture"; override val displayName = "Deterministic fixture"
        override val kind = "fixture"; override val local = true
        override suspend fun capabilities() = ProviderCapabilities(false, false, 4096, 1, true, true)
        override suspend fun listModels() = listOf(LocalModel("fixture"))
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            assertNull(tools)
            return reply(++calls, messages)
        }
    }
    class Environment : DevelopmentEnvironment {
        var source = "broken"
        var available = true
        var infrastructure = true
        var runs = 0
        var applies = 0
        var testPass = { source == "fixed" }
        val observations = mutableListOf<ToolExecutionEvidence>()
        override suspend fun discover() = DevelopmentDiscovery("/tmp/fixture", "fixture", ProjectRunKind.entries.associateWith { "fixture-${it.name}" },
            if (available) emptyList() else listOf("javac"), available, if (available) "ready" else "compiler missing")
        override suspend fun index(maxFiles: Int, maxFileBytes: Int) = listOf(SourceSnapshot("src/answer.kt", developmentSha256(source), source))
        override suspend fun run(kind: ProjectRunKind): DevelopmentCommand {
            runs++
            val code = if (!infrastructure) null else if (kind == ProjectRunKind.BUILD || testPass()) 0 else 1
            observations += ToolExecutionEvidence(kind.name, code, "original stdout $runs", "original stderr $runs")
            return DevelopmentCommand(kind, "fixture-${kind.name}", code, "original stdout $runs", "original stderr $runs", infrastructureOk = infrastructure)
        }
        override suspend fun apply(patch: DevelopmentPatch): PatchApplication {
            applies++
            val file = patch.files.single()
            if (file.expectedSha256 != developmentSha256(source)) return PatchApplication(false, reason = "stale")
            val before = SourceSnapshot(file.path, developmentSha256(source), source)
            source = file.content
            observations += ToolExecutionEvidence("apply", 0, "actual atomic write", files = listOf(file.path), mutating = true)
            return PatchApplication(true, DevelopmentCheckpoint("cp-$applies", listOf(CheckpointFile(file.path, before, developmentSha256(source), "/tmp/fixture/.nyx-checkpoints/cp-$applies"))))
        }
        override suspend fun rollback(checkpoint: DevelopmentCheckpoint): PatchApplication = error("No automatic rollback allowed")
        override suspend fun diff(checkpoints: List<DevelopmentCheckpoint>, maxChars: Int) = developmentDiff(checkpoints.first().files.first().before,
            SourceSnapshot("src/answer.kt", developmentSha256(source), source), "src/answer.kt", maxChars)
        override fun drainEvidence() = observations.toList().also { observations.clear() }
    }
    companion object {
        fun patch(path: String, hash: String, content: String): ChatResult = ChatResult(JSONObject().put("files", JSONArray().put(
            JSONObject().put("path", path).put("expectedSha256", hash).put("content", content))).toString())
    }
    @Test fun repairsOverTwoIterationsWithActualExitEvidence() = runBlocking {
        val env = Environment()
        val provider = Provider { call, _ -> patch("src/answer.kt", developmentSha256(env.source), if (call == 1) "partway" else "fixed") }
        val phases = mutableListOf<String>()
        val report = DevelopmentOrchestrator(env).run("fix failing test", provider, "fixture", onEvent = { phases += it.phase })
        assertTrue(report.passed); assertEquals("verification_passed", report.reason)
        assertEquals(2, report.inferenceCalls); assertEquals(3, report.iterations.size)
        assertEquals(listOf(0, 1, 0, 1, 0, 0), report.commands.map { it.exitCode })
        assertEquals(listOf("src/answer.kt"), report.files); assertTrue(report.diff.contains("-broken")); assertTrue(report.diff.contains("+fixed"))
        assertEquals(2, report.checkpoints.size); assertEquals(2, report.modelResponses.size)
        assertTrue(report.evidence.any { it.stdout == "original stdout 2" }); assertTrue(phases.containsAll(listOf("discover", "index", "diagnose", "checkpoint", "verify", "complete")))
    }
    @Test fun missingCompilerStopsWithoutBuildOrInference() = runBlocking {
        val env = Environment().apply { available = false }
        val provider = Provider { _, _ -> error("must not infer") }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture")
        assertFalse(report.passed); assertEquals(0, env.runs); assertEquals(0, provider.calls)
        assertTrue(report.reason.startsWith("environment_unavailable"))
    }
    @Test fun infrastructureFailureIsNotDiagnosedAsSourceBug() = runBlocking {
        val env = Environment().apply { infrastructure = false }
        val provider = Provider { _, _ -> error("must not infer") }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture")
        assertFalse(report.passed); assertEquals("command_infrastructure_failure", report.reason); assertEquals(0, provider.calls)
    }
    @Test fun iterationAndInferenceBudgetsStopSustainedFailure() = runBlocking {
        val env = Environment().apply { testPass = { false } }
        val provider = Provider { call, _ -> patch("src/answer.kt", developmentSha256(env.source), "attempt-$call") }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture", budget = DevelopmentBudget(maxIterations = 2, maxInferenceCalls = 1))
        assertFalse(report.passed); assertEquals("inference_budget_exhausted", report.reason); assertEquals(1, provider.calls)
        assertEquals(4, report.commands.size); assertTrue(report.humanReviewRequired)
    }
    @Test fun duplicatePatchStopsWithoutSecondEdit() = runBlocking {
        val env = Environment().apply { testPass = { false } }
        val provider = Provider { _, _ -> patch("src/answer.kt", developmentSha256(env.source), "still-wrong") }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture")
        assertEquals("duplicate_patch", report.reason); assertEquals(1, env.applies); assertFalse(report.passed)
    }
    @Test fun unsafeModelPatchNeverReachesEnvironment() = runBlocking {
        val env = Environment()
        val provider = Provider { _, _ -> patch("../secret", developmentSha256(env.source), "oops") }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture")
        assertTrue(report.reason.startsWith("invalid_patch")); assertEquals(0, env.applies)
    }
    @Test fun modelToolCallsAndClaimedSuccessAreNotExecutionEvidence() = runBlocking {
        val env = Environment()
        val provider = Provider { _, _ -> ChatResult("tests pass", listOf(ToolCall("x", "terminal_run", JSONObject().put("command", "rm -rf /")))) }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture")
        assertFalse(report.passed); assertEquals("incomplete_or_tool_response_denied", report.reason); assertEquals(0, env.applies)
    }
    @Test fun timeoutReturnsBoundedFailure() = runBlocking {
        val env = Environment()
        val provider = Provider { _, _ -> delay(1_000); error("not reached") }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture", budget = DevelopmentBudget(maxElapsedMillis = 50))
        assertFalse(report.passed); assertEquals("elapsed_budget_exhausted", report.reason)
    }
    @Test fun externalCancellationIsRethrown() = runBlocking {
        val provider = Provider { _, _ -> throw CancellationException("external cancellation") }
        try { DevelopmentOrchestrator(Environment()).run("fix", provider, "fixture"); fail("cancellation swallowed") }
        catch (expected: CancellationException) { assertEquals("external cancellation", expected.message) }
    }
    @Test fun alreadyPassingBaselineIsPreciselyReportedNotClaimedAsRepair() = runBlocking {
        val env = Environment().apply { source = "fixed" }
        val provider = Provider { _, _ -> error("baseline requires no repair") }
        val report = DevelopmentOrchestrator(env).run("fix", provider, "fixture")
        assertTrue(report.passed); assertEquals("baseline_passed", report.reason); assertEquals(0, provider.calls); assertTrue(report.files.isEmpty())
    }
}

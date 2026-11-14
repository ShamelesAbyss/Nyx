package com.nyx.ai.workbench

import com.nyx.ai.agent.*
import com.nyx.ai.autonomy.*
import com.nyx.ai.data.*
import com.nyx.ai.network.*
import com.nyx.ai.terminal.TerminalEnvironment
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Production callers run against deterministic provider + file-backed fake DAOs, never Android/Room/Ollama. */
class CombinedAgentValidationBedrockTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun productionContinuationUsesNextSchemaPersistsOrdinalsAndRecoversUniqueTaskIds() = runBlocking {
        val root = temporary.newFolder()
        val first = CombinedHostFixture(root)
        first.db.objective = first.db.objective.copy(goal = "Inspect project requirements and summarize results")
        val provider = CombinedHostProvider { count, _, tools, options ->
            assertNull(tools)
            when (count) {
                1 -> { assertTrue(options.responseSchema!!.contains("steps")); ChatResult("{\"steps\":[{\"do\":\"Summarize requirement one\",\"worker\":\"inference\"}]}") }
                2 -> ChatResult("Requirement one summarized.")
                3 -> { assertTrue(options.responseSchema!!.contains("next")); assertTrue(options.responseSchema.contains("done")); assertFalse(options.responseSchema.contains("steps")); ChatResult("{\"next\":{\"do\":\"Summarize requirement two\",\"worker\":\"inference\"}}") }
                4 -> error("Fixture interruption after continuation task persistence")
                else -> error("Unexpected request $count")
            }
        }
        val result = first.engine(provider).planAndExecute(first.db.objective.id, first.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("needs_attention", result.status)
        assertEquals(2, first.db.tasks.size)
        val ids = first.db.tasks.sortedBy { it.ordinal }.map { it.id }
        assertEquals(2, ids.distinct().size)
        assertEquals("[\"${ids.first()}\"]", first.db.tasks.single { it.ordinal == 1 }.dependsOnJson)
        assertEquals(1, first.db.events.count { it.kind == "planning_continuation_reserved" })
        assertEquals(1, JSONObject(first.db.events.last { it.kind == "planning_checkpoint" }.detail!!).getInt("continuationOrdinal"))
        val restarted = CombinedHostFixture(root)
        assertEquals(ids, restarted.db.tasks.sortedBy { it.ordinal }.map { it.id })
        val nextProvider = CombinedHostProvider { count, _, _, options -> when (count) {
            1 -> ChatResult("Requirement two summarized after explicit review.")
            2 -> { assertTrue(options.responseSchema!!.contains("next")); ChatResult("{\"done\":true}") }
            else -> error("Persisted decision was replayed")
        } }
        val resumed = restarted.engine(nextProvider).resumeAfterReview(restarted.db.objective.id, restarted.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("Resume failed ${resumed.error}", "completed", resumed.status)
        assertEquals(ids, restarted.db.tasks.sortedBy { it.ordinal }.map { it.id })
        assertEquals(listOf(1, 2), restarted.db.events.filter { it.kind == "planning_continuation_reserved" }.map { JSONObject(it.detail!!).getInt("continuationOrdinal") })
        val final = JSONObject(restarted.db.events.last { it.kind == "planning_checkpoint" }.detail!!)
        assertTrue(final.getBoolean("done")); assertEquals(2, final.getInt("continuationOrdinal"))
        val again = CombinedHostFixture(root)
        val noReplay = CombinedHostProvider { _, _, _, _ -> error("Persisted DONE must not request another continuation") }
        assertEquals("completed", again.engine(noReplay).resumeAfterReview(again.db.objective.id, again.agent, SchedulingPolicy(), ExecutionControl()).status)
        assertTrue(noReplay.requests.isEmpty())
        val actual = ModelIdentity.fromRequest(provider, "fixture", provider.requests[0].options, null)
        assertEquals(2048, actual.contextLength)
        assertEquals(true, actual.think)
        assertNotNull(actual.responseSchemaHash)
        assertNotEquals(actual, ModelIdentity.fromRequest(provider, "fixture", InferenceOptions(contextLength = 8192, think = true), null))
    }

    @Test fun productionFullStrategyRequiresExactRequestEvidenceAndPersistsWholeDagProfile() = runBlocking {
        val fixture = CombinedHostFixture(temporary.newFolder())
        fixture.db.objective = fixture.db.objective.copy(goal = "Refactor and integrate project architecture")
        val tools = fixedSurface()
        val provider = CombinedHostProvider { _, messages, _, options ->
            assertEquals(8192, options.contextLength)
            assertEquals(1024, options.maxOutputTokens)
            assertTrue(options.responseSchema!!.contains("tasks"))
            assertTrue(messages.first().content.contains("directed acyclic graph"))
            ChatResult(fullPlan(3))
        }
        val store = ModelCapabilityStore()
        val base = CompactPlanningPolicy.profile(fixture.db.objective.goal, true, AgentTools.DEFAULT_TOOLS + tools.names, 8192)
        val profile = CompactPlanningPolicy.forDecision(base, PlanningDecision(PlanningStrategy.FULL, "fixture", 12, false, false), 8192)
        val identity = ModelIdentity.fromRequest(provider, "fixture", InferenceOptions(contextLength = 8192, think = true, temperature = 0.1, maxOutputTokens = profile.maxOutputTokens, responseSchema = profile.schema), null)
        // Trusted fixture setup establishes the decision prerequisite; the production caller must subsequently validate the whole reply.
        store.recordValidatedFullPlan(identity)
        store.recordObservation(identity, false, InferenceTokenStats(outputTokens = 80, generationDurationNanos = 1_000_000_000), 5)
        fixture.engine(provider, tools, before = { false }, capabilityStore = store).planAndExecute(fixture.db.objective.id, fixture.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals(3, fixture.db.tasks.size)
        assertEquals(3, fixture.db.tasks.map { it.id }.distinct().size)
        assertTrue(fixture.db.tasks.single { it.ordinal == 2 }.dependsOnJson.contains(fixture.db.tasks.single { it.ordinal == 0 }.id))
        assertTrue(store.get(ModelIdentity.fromRequest(provider, "fixture", provider.requests.single().options, null))!!.fullPlanObservedAtEpochMs > 0)
        val persisted = JSONObject(fixture.db.events.last { it.kind == "planning_checkpoint" }.detail!!).getJSONObject("profile")
        assertTrue(persisted.getBoolean("fullGraph")); assertFalse(persisted.getBoolean("compact")); assertEquals(12, persisted.getInt("maxSteps"))
        val wrong = CombinedHostFixture(temporary.newFolder())
        wrong.db.objective = wrong.db.objective.copy(goal = fixture.db.objective.goal)
        val boundedProvider = CombinedHostProvider { _, _, _, options -> assertTrue(options.responseSchema!!.contains("steps")); ChatResult("{\"steps\":[{\"do\":\"Summarize architecture\",\"worker\":\"inference\"}]}") }
        val wrongStore = ModelCapabilityStore()
        val wrongIdentity = identity.copy(providerId = boundedProvider.id, maxOutputTokens = 32)
        wrongStore.recordValidatedFullPlan(wrongIdentity)
        wrongStore.recordObservation(wrongIdentity, false, InferenceTokenStats(outputTokens = 80, generationDurationNanos = 1_000_000_000), 5)
        wrong.engine(boundedProvider, tools, before = { false }, capabilityStore = wrongStore).planAndExecute(wrong.db.objective.id, wrong.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals(1, wrong.db.tasks.size)
        assertEquals("INCREMENTAL", JSONObject(wrong.db.events.last { it.kind == "planning_checkpoint" }.detail!!).getString("strategy"))
    }

    @Test fun productionFullCallerRejectsOverBudgetDagAndUnavailableWorkerBeforePersistence() = runBlocking {
        for (reply in listOf(fullPlan(13), "{\"tasks\":[{\"id\":\"x\",\"title\":\"Forbidden\",\"instructions\":\"Run a shell\",\"worker\":\"terminal\",\"dependsOn\":[],\"evidence\":[{\"kind\":\"exit_code\",\"expected\":\"0\"}]}]}")) {
            val fixture = CombinedHostFixture(temporary.newFolder())
            fixture.db.objective = fixture.db.objective.copy(goal = "Refactor and integrate project architecture")
            val tools = fixedSurface()
            val provider = CombinedHostProvider { _, _, _, _ -> ChatResult(reply) }
            val base = CompactPlanningPolicy.profile(fixture.db.objective.goal, true, AgentTools.DEFAULT_TOOLS + tools.names, 8192)
            val profile = CompactPlanningPolicy.forDecision(base, PlanningDecision(PlanningStrategy.FULL, "fixture", 12, false, false), 8192)
            val identity = ModelIdentity.fromRequest(provider, "fixture", InferenceOptions(contextLength = 8192, think = true, temperature = 0.1, maxOutputTokens = profile.maxOutputTokens, responseSchema = profile.schema), null)
            val store = ModelCapabilityStore().apply { recordValidatedFullPlan(identity); recordObservation(identity, false, InferenceTokenStats(outputTokens = 80, generationDurationNanos = 1_000_000_000), 5) }
            val result = fixture.engine(provider, tools, before = { false }, capabilityStore = store).planAndExecute(fixture.db.objective.id, fixture.agent, SchedulingPolicy(), ExecutionControl())
            assertEquals("needs_attention", result.status); assertTrue(fixture.db.tasks.isEmpty()); assertEquals(2, provider.requests.size)
            assertTrue(fixture.db.events.none { it.kind == "planning_checkpoint" })
        }
    }

    @Test fun latestConfigurationPreservesOriginalCombinedProjectAndGlobalRevocationFailsClosed() {
        val fixture = CombinedHostFixture(temporary.newFolder())
        val original = File(fixture.root, "original").apply { mkdirs() }
        val approval = fixture.approval(original)
        val first = WorkbenchExecutionConfiguration.scope(JSONObject().put("endpointId", "provider-one"), approval, CombinedHostFixture.OBJECTIVE,
            true, TerminalEnvironment.DEBIAN_PROOT, "debian", original.path)
        val second = WorkbenchExecutionConfiguration.scope(JSONObject().put("endpointId", "provider-two"), WorkbenchAuthorization.fromSnapshot(first), CombinedHostFixture.OBJECTIVE,
            true, TerminalEnvironment.TERMUX_HOST, "changed", "/tmp/current-project", first)
        val roundtrip = JSONObject(second.toString())
        assertEquals(approval, WorkbenchAuthorization.fromSnapshot(roundtrip))
        assertEquals(original.canonicalPath, roundtrip.getString("workspace"))
        assertEquals("DEBIAN_PROOT", roundtrip.getString("environment")); assertEquals("debian", roundtrip.getString("guestAlias"))
        assertEquals(2L, roundtrip.getLong("configurationSequence"))
        assertEquals("provider-two", WorkbenchExecutionConfiguration.latest(listOf(second, first))!!.getString("endpointId"))
        assertTrue(runCatching { WorkbenchExecutionConfiguration.scope(JSONObject(), approval, CombinedHostFixture.OBJECTIVE,
            false, TerminalEnvironment.TERMUX_HOST, "changed", "/tmp/current-project", first) }.isFailure)
        assertTrue(runCatching { approval.bindObjective("foreign") }.isFailure)
        val legacy = WorkbenchAuthorization(enabled = true, requiredFiles = mapOf("result.json" to ArtifactFormat.JSON))
        val artifactOnly = WorkbenchExecutionConfiguration.scope(JSONObject(), legacy, "legacy", true, TerminalEnvironment.DEBIAN_PROOT, "debian", original.path)
        assertFalse(artifactOnly.getBoolean("terminalEnabled")); assertEquals("", artifactOnly.getString("workspace"))
        val malformed = JSONObject(first.toString()); malformed.getJSONObject("workbench").getJSONObject("combinedDevelopment").put("environment", "forged")
        assertTrue(runCatching { WorkbenchAuthorization.fromSnapshot(malformed) }.isFailure)
    }

    @Test fun productionRunnerPublishesOnlyLocallyValidatedExactTextActionEvidence() = runBlocking {
        for (content in listOf("{\"type\":\"tool\",\"name\":\"terminal_run\",\"arguments\":{\"command\":\"id\"}}", "{\"type\":\"tool\",\"name\":\"workbench_write\",\"arguments\":{\"name\":\"report.md\",\"format\":\"MARKDOWN\",\"content\":42}}", "ordinary prose")) {
            val fixture = CombinedHostFixture(temporary.newFolder())
            val approval = WorkbenchAuthorization(enabled = true, requiredFiles = mapOf("report.md" to ArtifactFormat.MARKDOWN))
            val provider = CombinedHostProvider { _, _, _, _ -> ChatResult(content) }
            val tools = WorkbenchAgentTools(fixture.store, CombinedHostFixture.OBJECTIVE, approval).forTask(AgentToolTaskContext(CombinedHostFixture.OBJECTIVE, "runner", ExecutionTimeoutPolicy.forProfile()))
            val store = ModelCapabilityStore()
            AgentRunner(HostContext.create(fixture.root), provider, tools, store).run(AgentSpec("a", "fixture", "fixture", "Use only Workbench", maxSteps = 1, allowedTools = setOf(WorkbenchAgentTools.WRITE)), "Write report.md")
            assertTrue(fixture.store.listArtifacts().isEmpty())
            assertTrue(store.all().none { it.textActions == CapabilitySupport.SUPPORTED || it.nativeTools == CapabilitySupport.SUPPORTED })
        }
        val fixture = CombinedHostFixture(temporary.newFolder())
        val approval = WorkbenchAuthorization(enabled = true, requiredFiles = mapOf("report.md" to ArtifactFormat.MARKDOWN))
        val options = InferenceOptions(temperature = 0.2, contextLength = 4096, think = true, executionTimeouts = ExecutionTimeoutPolicy.forProfile())
        val provider = CombinedHostProvider { _, _, tools, outgoing ->
            assertNull(tools); assertNotNull(outgoing.thinkingObserver)
            assertEquals(options, outgoing.copy(thinkingObserver = null))
            ChatResult("{\"type\":\"tool\",\"name\":\"workbench_write\",\"arguments\":{\"name\":\"report.md\",\"format\":\"MARKDOWN\",\"content\":\"# Observed bytes\\n\"}}")
        }
        val store = ModelCapabilityStore()
        val tools = WorkbenchAgentTools(fixture.store, CombinedHostFixture.OBJECTIVE, approval).forTask(AgentToolTaskContext(CombinedHostFixture.OBJECTIVE, "runner", ExecutionTimeoutPolicy.forProfile()))
        val result = AgentRunner(HostContext.create(fixture.root), provider, tools, store).run(AgentSpec("a", "fixture", "fixture", "Use Workbench", maxSteps = 1, allowedTools = setOf(WorkbenchAgentTools.WRITE)), "Write report.md", options = options)
        assertEquals(1, result.toolCalls)
        val identity = ModelIdentity.fromRequest(provider, "fixture", provider.requests.single().options, null)
        assertEquals(CapabilitySupport.SUPPORTED, store.get(identity)!!.textActions)
        assertEquals(CapabilitySupport.UNKNOWN, store.get(identity)!!.nativeTools)
        assertNull(store.get(identity.copy(contextLength = 8192)))
    }

    @Test fun combinedDestinationsCannotBeForgedThroughModelWriteOrBundle() = runBlocking {
        val fixture = CombinedHostFixture(temporary.newFolder())
        val approval = fixture.approval(fixture.root)
        val tools = WorkbenchAgentTools(fixture.store, CombinedHostFixture.OBJECTIVE, approval).forTask(AgentToolTaskContext(CombinedHostFixture.OBJECTIVE, "runner", ExecutionTimeoutPolicy.forProfile()))
        val args = JSONObject().put("name", "result.json").put("format", "JSON").put("content", "{\"passed\":true}")
        assertTrue(tools.execute(ToolCall("forged", WorkbenchAgentTools.WRITE, args)).startsWith("Tool error"))
        assertTrue(fixture.store.listArtifacts().isEmpty())
        val generic = WorkbenchCompletionPolicy.WriteWitness("runner", "result.json", "0".repeat(64), WorkbenchAgentTools.WRITE)
        assertEquals(listOf("result.json"), WorkbenchCompletionPolicy.missing(approval, fixture.store, setOf("runner"), listOf(generic)))
    }

    @Test fun runnerActiveObserverDoesNotBypassNoActivityDeadline() = runBlocking {
        val fixture = CombinedHostFixture(temporary.newFolder())
        val policy = ExecutionTimeoutPolicy(ExecutionTimeoutProfile.QUICK, firstOutputMs = 20, inactivityMs = 20, requestMs = 100,
            buildMs = 100, terminalMs = 100, overallAllowanceMs = 100, serviceSegmentMs = 100)
        val provider = object : InferenceProvider {
            override val id = "runner-timeout-fixture"; override val kind = "native-gguf"; override val displayName = "fixture"; override val local = true
            override suspend fun capabilities() = ProviderCapabilities(false, false, 4096, 1, true, true)
            override suspend fun listModels() = emptyList<LocalModel>()
            override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
                assertNotNull(options.thinkingObserver); delay(60); return ChatResult("too late")
            }
        }
        val failure = runCatching { AgentRunner(HostContext.create(fixture.root), provider).run(AgentSpec("a", "fixture", "fixture", "Text", allowedTools = emptySet()), "Explain", options = InferenceOptions(executionTimeouts = policy)) }.exceptionOrNull()
        assertTrue("Expected actual watchdog rejection, got $failure", failure is ExecutionTimeoutException)
    }

    private fun fixedSurface() = object : AgentToolProvider {
        override val names = setOf("file_read", "file_write", "build_run", "test_run")
        override fun schemas() = JSONArray()
        override suspend fun execute(call: ToolCall): String = error("Planning must not dispatch tools")
    }
    private fun fullPlan(count: Int) = JSONObject().put("tasks", JSONArray().apply { repeat(count) { index ->
        put(JSONObject().put("id", "part_$index").put("title", "Part $index").put("instructions", "Summarize part $index").put("worker", "inference")
            .put("verification", "Nonempty output only").put("dependsOn", JSONArray(if (index == 2) listOf("part_0") else emptyList<String>()))
            .put("evidence", JSONArray().put(JSONObject().put("kind", "artifact_nonempty"))))
    } }).toString()
}

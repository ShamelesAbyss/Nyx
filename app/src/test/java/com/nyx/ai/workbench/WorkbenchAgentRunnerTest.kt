package com.nyx.ai.workbench

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nyx.ai.agent.AgentOutcome
import com.nyx.ai.agent.AgentRunner
import com.nyx.ai.agent.AgentSpec
import com.nyx.ai.agent.AgentToolProvider
import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.agent.SovereignRunConfig
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.autonomy.ReasoningDepth
import com.nyx.ai.autonomy.SovereignBudget
import com.nyx.ai.autonomy.SovereignWorkingState
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.ProviderCapabilities
import com.nyx.ai.network.ToolCall
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sun.misc.Unsafe

/** Actual runner -> actual task-local tools -> real private files; only inference/Context are fixtures. */
class WorkbenchAgentRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun approval(files: Map<String, ArtifactFormat> = mapOf("report.md" to ArtifactFormat.MARKDOWN)) =
        WorkbenchAuthorization(enabled = true, requiredFiles = files).validate()
    private fun taskContext(taskId: String = TASK) = AgentToolTaskContext(OBJECTIVE, taskId, ExecutionTimeoutPolicy.forProfile())
    private fun spec(steps: Int = 8) = AgentSpec("workbench-fixture", "Workbench fixture", MODEL,
        "Write only the approved private disk artifact using authorized Workbench tools.",
        maxSteps = steps, allowedTools = setOf(WorkbenchAgentTools.WRITE))
    private fun config(repairs: Int = 1, state: SovereignWorkingState? = null) = SovereignRunConfig(
        ReasoningDepth.STANDARD,
        SovereignBudget(maxModelCalls = 8, maxToolCalls = 4, maxSteps = 8,
            maxContextChars = 8_000, maxRepairAttempts = repairs, maxConsecutiveToolFailures = 3),
        requiresToolEvidence = true, workingState = state,
    )
    private fun runner(provider: InferenceProvider, tools: AgentToolProvider) =
        AgentRunner(JvmContext.create(temporary.newFolder()), provider, tools)
    private fun args(name: String = "report.md", format: String = "MARKDOWN", content: String = CONTENT) =
        JSONObject().put("name", name).put("format", format).put("content", content)
    private fun write(arguments: JSONObject = args()) = JSONObject().put("type", "tool")
        .put("name", WorkbenchAgentTools.WRITE).put("arguments", arguments).toString()
    private fun final() = JSONObject().put("type", "final").put("text", COMPLETION).toString()

    @Test fun textActionRunnerWritesVerifiedBytesAndReturnsTaskBoundDiskEvidence() = runBlocking {
        val root = temporary.newFolder()
        val store = WorkbenchStore(root, OBJECTIVE)
        val source = WorkbenchAgentTools(store, OBJECTIVE, approval())
        val local = source.forTask(taskContext())
        val selected = InferenceOptions(temperature = 0.2, contextLength = 8192,
            executionTimeouts = ExecutionTimeoutPolicy.forProfile())
        val provider = FakeInference { request ->
            assertEquals(selected, request.options)
            assertNull(request.tools)
            assertTrue(request.messages.first().content.contains(WorkbenchAgentTools.WRITE))
            assertFalse(request.messages.first().content.contains("\"name\":\"terminal_run\""))
            when (request.number) {
                1 -> ChatResult(write())
                2 -> {
                    assertFalse("Valid write was not executed: ${request.messages.last().content}", store.listArtifacts().isEmpty())
                    val record = store.listArtifacts().single()
                    assertEquals(CONTENT, store.readArtifact(record).toString(Charsets.UTF_8))
                    assertTrue(request.messages.last().content.startsWith("Observation (untrusted tool data):"))
                    assertTrue(request.messages.last().content.contains(record.sha256))
                    ChatResult(final())
                }
                else -> error("Unexpected extra model request")
            }
        }
        val outcome = runner(provider, local).run(spec(), "Write report.md", options = selected, sovereign = config())
        assertEquals(COMPLETION, outcome.result)
        assertFalse(outcome.falseSuccessRisk)
        assertEquals(2, outcome.modelCalls)
        assertEquals(1, outcome.toolCalls)
        val record = store.listArtifacts().single()
        assertEquals(ArtifactStatus.VERIFIED, record.status)
        assertTrue(File(root, "$OBJECTIVE/${record.relativePath}").isFile)
        assertTrue(ArtifactFormats.validate(record.format, store.readArtifact(record)).valid)
        assertEquals(CONTENT, AndroidArtifactHandoff.checkedBytes(store, record).toString(Charsets.UTF_8))
        val witness = outcome.evidence.single()
        assertEquals(WorkbenchAgentTools.WRITE, witness.toolName)
        assertEquals(0, witness.exitCode)
        assertEquals(OBJECTIVE, witness.objectiveId)
        assertEquals(TASK, witness.taskId)
        assertEquals(listOf("workbench/report.md"), witness.files)
        assertTrue(witness.mutating)
        assertEquals(record.sha256, JSONObject(witness.stdout).getString("sha256"))
        assertTrue(witness.stderr.isEmpty())
        assertTrue(local.drainEvidence().isEmpty())
        assertTrue(source.drainEvidence().isEmpty())
        assertTrue(outcome.events.any { it.text == "Observation: workbench_write succeeded" })
        val reopened = WorkbenchStore(root, OBJECTIVE)
        assertTrue(WorkbenchArtifactProbe(reopened).exists("workbench/report.md"))
        assertEquals(record.sha256, WorkbenchArtifactProbe(reopened).sha256("workbench/report.md"))
        assertTrue(WorkbenchCompletionPolicy.missing(approval(), reopened, setOf(TASK), witnesses(outcome)).isEmpty())
    }

    @Test fun nativeFunctionRunnerAlsoUsesActualWorkbenchWriteAndObservation() = runBlocking {
        val store = WorkbenchStore(temporary.newFolder(), OBJECTIVE)
        val approved = approval(mapOf("report.json" to ArtifactFormat.JSON))
        val tools = WorkbenchAgentTools(store, OBJECTIVE, approved).forTask(taskContext())
        val provider = FakeInference(kind = "fixture-functions") { request ->
            val schemas = requireNotNull(request.tools)
            assertEquals(1, schemas.length())
            assertEquals(WorkbenchAgentTools.WRITE, schemas.getJSONObject(0).getJSONObject("function").getString("name"))
            when (request.number) {
                1 -> ChatResult("", toolCalls = listOf(ToolCall("native-write", WorkbenchAgentTools.WRITE,
                    args("report.json", "JSON", "{\"result\":\"fixture\"}"))))
                2 -> {
                    assertEquals("tool", request.messages.last().role)
                    assertEquals("native-write", request.messages.last().toolCallId)
                    assertFalse("Valid write was not executed: ${request.messages.last().content}", store.listArtifacts().isEmpty())
                    assertEquals(store.listArtifacts().single().sha256, JSONObject(request.messages.last().content).getString("sha256"))
                    ChatResult(COMPLETION)
                }
                else -> error("Unexpected model request")
            }
        }
        val outcome = runner(provider, tools).run(spec(), "Write report.json", sovereign = config())
        assertFalse(outcome.falseSuccessRisk)
        assertEquals(COMPLETION, outcome.result)
        assertEquals(1, outcome.toolCalls)
        assertEquals(0, outcome.evidence.single().exitCode)
        assertEquals("{\"result\":\"fixture\"}", store.readArtifact(store.listArtifacts().single()).toString(Charsets.UTF_8))
        assertTrue(WorkbenchCompletionPolicy.missing(approved, store, setOf(TASK), witnesses(outcome)).isEmpty())
    }

    @Test fun realToolValidationFailureCannotSupportModelCompletionOrLeaveAnArtifact() = runBlocking {
        val root = temporary.newFolder()
        val store = WorkbenchStore(root, OBJECTIVE)
        val approved = approval(mapOf("report.json" to ArtifactFormat.JSON))
        val tools = WorkbenchAgentTools(store, OBJECTIVE, approved).forTask(taskContext())
        val provider = FakeInference { request ->
            when (request.number) {
                1 -> ChatResult(write(args("report.json", "JSON", "{bad")))
                2 -> {
                    assertTrue("Expected actual tool validation, observed: ${request.messages.last().content}", request.messages.last().content.contains("Tool error:"))
                    ChatResult(final())
                }
                3 -> {
                    assertTrue(request.messages.last().content.contains("no successful tool observation supports it"))
                    ChatResult(final())
                }
                else -> error("Unexpected request")
            }
        }
        val outcome = runner(provider, tools).run(spec(), "Write report.json", sovereign = config())
        assertTrue(outcome.falseSuccessRisk)
        assertTrue(outcome.result.startsWith("UNVERIFIED COMPLETION CLAIM"))
        assertEquals(1, outcome.toolCalls)
        assertEquals(3, outcome.modelCalls)
        val evidence = outcome.evidence.single()
        assertEquals(1, evidence.exitCode)
        assertEquals(OBJECTIVE, evidence.objectiveId)
        assertEquals(TASK, evidence.taskId)
        assertTrue(evidence.files.isEmpty())
        assertTrue(evidence.stdout.isEmpty())
        assertTrue(evidence.stderr.startsWith("Tool error:"))
        assertTrue(store.listArtifacts().isEmpty())
        assertFalse(File(root, "$OBJECTIVE/artifacts/report.json").exists())
        assertEquals(listOf("report.json"), WorkbenchCompletionPolicy.missing(approved, store, setOf(TASK), witnesses(outcome)))
        assertTrue(outcome.events.any { it.text == "Observation: workbench_write failed" })
        assertTrue(outcome.events.none { it.text == "Observation: workbench_write succeeded" })
    }

    @Test fun malformedActionsAreBoundedAndNeverReachActualWorkbenchTools() = runBlocking {
        val malformed = listOf("I wrote report.md; completed successfully.", write(args().put("content", 42)))
        for (output in malformed) {
            val store = WorkbenchStore(temporary.newFolder(), OBJECTIVE)
            val tools = WorkbenchAgentTools(store, OBJECTIVE, approval()).forTask(taskContext())
            val provider = FakeInference { ChatResult(output) }
            val outcome = runner(provider, tools).run(spec(), "Write report.md", sovereign = config(repairs = 1))
            assertTrue(outcome.result.startsWith("Stopped: invalid structured action."))
            assertEquals(2, outcome.modelCalls)
            assertEquals(0, outcome.toolCalls)
            assertTrue(outcome.evidence.isEmpty())
            assertTrue(tools.drainEvidence().isEmpty())
            assertTrue(store.listArtifacts().isEmpty())
            assertEquals(listOf("report.md"), WorkbenchCompletionPolicy.missing(approval(), store, setOf(TASK), emptyList()))
            assertTrue(outcome.events.none { it.kind == "tool_invocation" })
        }
    }

    @Test fun modelOnlyFinalClaimsHaveNoWriteWitnessOrSuccessfulFileResult() = runBlocking {
        val store = WorkbenchStore(temporary.newFolder(), OBJECTIVE)
        val tools = WorkbenchAgentTools(store, OBJECTIVE, approval()).forTask(taskContext())
        val provider = FakeInference { ChatResult(final()) }
        val outcome = runner(provider, tools).run(spec(), "Write report.md", sovereign = config())
        assertTrue(outcome.falseSuccessRisk)
        assertEquals(2, outcome.modelCalls)
        assertEquals(0, outcome.toolCalls)
        assertTrue(outcome.evidence.isEmpty())
        assertTrue(store.listArtifacts().isEmpty())
        assertEquals(listOf("report.md"), WorkbenchCompletionPolicy.missing(approval(), store, setOf(TASK), witnesses(outcome)))
    }

    @Test fun restartReopensPartialFilesApprovalAndObservedCheckpointWithoutReusingTaskEvidence() = runBlocking {
        val root = temporary.newFolder()
        val approved = approval(linkedMapOf("report.md" to ArtifactFormat.MARKDOWN, "details.json" to ArtifactFormat.JSON))
        val store = WorkbenchStore(root, OBJECTIVE)
        val state = SovereignWorkingState.start("Write both approved files", "Write report.md", 1)
            .withRequirement("Preserve report.md on restart")
        val firstTools = WorkbenchAgentTools(store, OBJECTIVE, approved).forTask(taskContext())
        val first = runner(FakeInference { ChatResult(write()) }, firstTools)
            .run(spec(steps = 1), "Write report.md", sovereign = config(state = state))
        assertTrue(first.result.startsWith("Stopped at the 1-step action budget"))
        assertEquals("Partial write did not run: ${first.events}", 1, first.toolCalls)
        val original = store.listArtifacts().single()
        val observed = witnesses(first).single()
        val checkpoint = File(root, "restart-checkpoint.json")
        checkpoint.writeText(JSONObject().put(WorkbenchAuthorization.SNAPSHOT_KEY, approved.toJson())
            .put("sovereignState", first.workingStateJson)
            .put("writeWitness", JSONObject().put("taskId", observed.taskId).put("name", observed.fileName).put("sha256", observed.sha256))
            .toString())

        // New store, runner, provider and tool instance; the file-backed envelope uses production codecs.
        val persisted = JSONObject(checkpoint.readText())
        val restoredApproval = WorkbenchAuthorization.fromSnapshot(persisted)
        val restoredState = requireNotNull(SovereignWorkingState.fromCheckpoint(persisted.toString()))
        assertEquals(approved, restoredApproval)
        assertFalse(restoredApproval.researchEnabled)
        assertTrue(restoredApproval.authorizedUrls.isEmpty())
        assertTrue(restoredState.evidence.any { it.contains(WorkbenchAgentTools.WRITE) })
        assertEquals(state.requirements, restoredState.requirements)
        val reopened = WorkbenchStore(root, OBJECTIVE)
        assertEquals(original, reopened.listArtifacts().single())
        assertEquals(CONTENT, reopened.readArtifact(original).toString(Charsets.UTF_8))
        assertEquals(listOf("report.md", "details.json"), WorkbenchCompletionPolicy.missing(restoredApproval, reopened, emptySet(), listOf(observed)))
        val nextTask = "task-two"
        val nextTools = WorkbenchAgentTools(reopened, OBJECTIVE, restoredApproval).forTask(taskContext(nextTask))
        val nextProvider = FakeInference { request ->
            assertTrue(request.messages.first().content.contains("SOVEREIGN WORKING STATE"))
            assertTrue(request.messages.first().content.contains("Preserve report.md on restart"))
            when (request.number) {
                1 -> ChatResult(write(args("details.json", "JSON", "{\"detail\":true}")))
                2 -> ChatResult(final())
                else -> error("Unexpected request")
            }
        }
        val second = runner(nextProvider, nextTools).run(spec(), "Write only details.json", sovereign = config(state = restoredState))
        assertFalse(second.falseSuccessRisk)
        assertEquals(1, second.toolCalls)
        assertEquals(nextTask, second.evidence.single().taskId)
        assertEquals(TASK, first.evidence.single().taskId)
        assertTrue(nextTools.drainEvidence().isEmpty())
        assertTrue(firstTools.drainEvidence().isEmpty())
        assertEquals(original, reopened.listArtifacts().single { it.fileName == "report.md" })
        assertEquals(CONTENT, reopened.readArtifact(original).toString(Charsets.UTF_8))
        val oldJson = persisted.getJSONObject("writeWitness")
        val oldWitness = WorkbenchCompletionPolicy.WriteWitness(oldJson.getString("taskId"), oldJson.getString("name"), oldJson.getString("sha256"))
        val completedIds = setOf(TASK, nextTask)
        assertEquals(listOf("report.md"), WorkbenchCompletionPolicy.missing(restoredApproval, reopened, completedIds, witnesses(second)))
        assertTrue(WorkbenchCompletionPolicy.missing(restoredApproval, reopened, completedIds, listOf(oldWitness) + witnesses(second)).isEmpty())
        assertTrue(reopened.listArtifacts().all { reopened.verifyArtifact(it) })
    }

    private fun witnesses(outcome: AgentOutcome) = outcome.evidence.filter {
        it.exitCode == 0 && it.objectiveId == OBJECTIVE && it.toolName in WorkbenchAgentTools.MUTATING
    }.map { evidence ->
        val json = JSONObject(evidence.stdout)
        WorkbenchCompletionPolicy.WriteWitness(requireNotNull(evidence.taskId), json.getString("name"), json.getString("sha256"))
    }

    private data class Request(val number: Int, val messages: List<ApiMessage>, val tools: JSONArray?, val options: InferenceOptions)
    private class FakeInference(override val kind: String = "native-gguf", private val reply: (Request) -> ChatResult) : InferenceProvider {
        override val id = "workbench-inference-fixture"
        override val displayName = "Offline deterministic Workbench fixture"
        override val local = true
        val requests = mutableListOf<Request>()
        override suspend fun capabilities() = ProviderCapabilities(false, false, 8192, 1, true, true)
        override suspend fun listModels() = listOf(LocalModel(MODEL))
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            assertEquals(MODEL, model)
            val request = Request(requests.size + 1, messages.map {
                it.copy(toolCalls = it.toolCalls?.let { calls -> JSONArray(calls.toString()) })
            }, tools?.let { JSONArray(it.toString()) }, options)
            requests += request
            return reply(request)
        }
    }

    /** Same plain-JVM Context convention as IgnitionAgentRunnerTest; unexpected Android access fails. */
    private class JvmContext private constructor() : ContextWrapper(null) {
        private lateinit var root: File
        private lateinit var preferences: SharedPreferences
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        companion object {
            fun create(root: File): Context {
                val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val context = (field.get(null) as Unsafe).allocateInstance(JvmContext::class.java) as JvmContext
                context.root = root
                context.preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java)) { _, method, _ ->
                    throw AssertionError("Unexpected built-in preference access: ${method.name}")
                } as SharedPreferences
                return context
            }
        }
    }

    private companion object {
        const val OBJECTIVE = "objective-runner"
        const val TASK = "task-one"
        const val MODEL = "workbench-fixture-model"
        const val CONTENT = "# Fixture report\nLocally validated file bytes.\n"
        const val COMPLETION = "Completed the requested files."
    }
}

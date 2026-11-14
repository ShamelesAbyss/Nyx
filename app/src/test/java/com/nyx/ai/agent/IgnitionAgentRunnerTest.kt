package com.nyx.ai.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nyx.ai.autonomy.ReasoningDepth
import com.nyx.ai.autonomy.SovereignBudget
import com.nyx.ai.autonomy.ExecutionTimeoutException
import com.nyx.ai.autonomy.ExecutionTimeoutKind
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.ProviderCapabilities
import com.nyx.ai.network.ToolCall
import com.nyx.ai.network.ToolCallingUnsupportedException
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sun.misc.Unsafe

/** Exercises AgentRunner.run itself: no real model, shell, Android service, or network. */
class IgnitionAgentRunnerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun incompleteStructuredResponseNeverExecutesOrGetsModelRepair() = runBlocking {
        val tools = FakeTools { _, _ -> error("Incomplete action must not execute") }
        val provider = FakeInference { ChatResult(action("fixture-build"), done = false) }
        try {
            runner(provider, tools).run(spec(), "Build", options = InferenceOptions(executionTimeouts = ExecutionTimeoutPolicy.forProfile()))
            fail("Expected incomplete response rejection")
        } catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("incomplete")) }
        assertEquals(1, provider.requests.size)
        assertTrue(tools.calls.isEmpty())
    }

    @Test fun selectedOptionsReachDirectProviderAndUserCancellationDoesNotRunAnyTool() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val selected = InferenceOptions(temperature = 0.3, contextLength = 8192, cpuThreads = 6,
            executionTimeouts = ExecutionTimeoutPolicy.forProfile())
        val provider = object : InferenceProvider by FakeInference(reply = { ChatResult("unused") }) {
            override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
                assertEquals(selected, options)
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        val tools = FakeTools { _, _ -> error("Cancelled inference must not execute") }
        val job = launch { runner(provider, tools).run(spec(), "Build", options = selected) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertTrue(tools.calls.isEmpty())
    }

    @Test fun directProviderFirstOutputTimeoutCancelsRequestWithoutStructuredRepairOrTool() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val provider = object : InferenceProvider by FakeInference(reply = { ChatResult("unused") }) {
            override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        val tools = FakeTools { _, _ -> error("Timed-out inference must not execute") }
        val selected = ExecutionTimeoutPolicy.forProfile().copy(firstOutputMs = 50, inactivityMs = 2000, requestMs = 4000)
        try {
            runner(provider, tools).run(spec(), "Build", options = InferenceOptions(executionTimeouts = selected))
            fail("Expected first-output timeout")
        } catch (timeout: ExecutionTimeoutException) { assertEquals(ExecutionTimeoutKind.FIRST_OUTPUT, timeout.kind) }
        assertTrue(stopped.isCompleted)
        assertTrue(tools.calls.isEmpty())
    }

    @Test
    fun unsupportedFunctionFallbackDoesNotExceedOneCallBudget() = runBlocking {
        val tools = FakeTools { _, _ -> success() }
        val provider = FakeInference(kind = "fixture") { _ ->
            throw ToolCallingUnsupportedException("no native functions")
        }
        val defaults = config()
        val outcome = runner(provider, tools).run(
            spec(), "Build the fixture",
            sovereign = defaults.copy(budget = defaults.budget.copy(maxModelCalls = 1)),
        )
        assertEquals(1, outcome.modelCalls)
        assertEquals(1, provider.requests.size)
        assertEquals(0, outcome.toolCalls)
        assertTrue(outcome.result.contains("model budget"))
    }

    @Test
    fun unsupportedNativeFunctionCallsFallBackToValidatedTextActions() = runBlocking {
        val tools = FakeTools { _, _ -> success() }
        val provider = FakeInference(kind = "fixture") { request ->
            when (request.number) {
                1 -> {
                    assertNotNull(request.tools)
                    assertEquals(listOf(TOOL), schemaNames(request.tools!!))
                    throw ToolCallingUnsupportedException("fixture has no native function calling")
                }
                2 -> {
                    assertNull(request.tools)
                    assertTrue(request.messages.first().content.contains("Return exactly ONE JSON object"))
                    assertTrue(request.messages.first().content.contains(TOOL))
                    ChatResult(action("fixture-build", summary = "Check the build result."))
                }
                3 -> {
                    assertNull(request.tools)
                    assertObservation(request, tools.observations.single())
                    ChatResult(finalAction(COMPLETION))
                }
                else -> error("Unexpected extra inference request")
            }
        }
        val streamed = mutableListOf<String>()
        val emitted = mutableListOf<AgentEvent>()
        val outcome = runner(provider, tools).run(
            spec(), "Build the fixture", sovereign = config(),
            onEvent = { emitted += it }, onToken = { streamed += it },
        )

        assertEquals(COMPLETION, outcome.result)
        assertFalse(outcome.falseSuccessRisk)
        assertEquals(2, outcome.steps)
        assertEquals(3, outcome.modelCalls) // Includes the unsupported native-call attempt.
        assertEquals(3, provider.requests.size)
        assertEquals(1, outcome.toolCalls)
        assertEquals("fixture-build", tools.calls.single().arguments.getString("command"))
        assertEquals("text-action", tools.calls.single().id)
        assertEquals(1, outcome.events.count { it.kind == "warning" })
        assertTrue(outcome.events.first { it.kind == "warning" }.text.contains("validated text JSON"))
        assertEquals(listOf("decision_summary", "tool_invocation", "tool_observation", "decision_summary"),
            outcome.events.filter { it.kind != "warning" }.map { it.kind })
        assertEquals("Decision: Check the build result.", outcome.events.first { it.kind == "decision_summary" }.text)
        assertEquals(outcome.events, emitted.filter { it.kind != "thinking" })
        assertEquals(listOf(COMPLETION), streamed)
        assertEquals(listOf(tools.producedEvidence.single()), outcome.evidence)
        assertTrue(tools.drainEvidence().isEmpty())
    }

    @Test
    fun nativeGgufStartsWithTextActionsAndRecoversOneFencedAction() = runBlocking {
        val tools = FakeTools { _, _ -> success() }
        val provider = FakeInference { request ->
            assertNull(request.tools)
            when (request.number) {
                1 -> ChatResult("```json\n${action("fixture-build")}\n```")
                2 -> {
                    assertObservation(request, tools.observations.single())
                    ChatResult(finalAction(COMPLETION))
                }
                else -> error("Unexpected extra inference request")
            }
        }
        val outcome = runner(provider, tools).run(spec(), "Build the fixture", sovereign = config())

        assertEquals(COMPLETION, outcome.result)
        assertFalse(outcome.falseSuccessRisk)
        assertEquals(2, outcome.modelCalls)
        assertEquals(1, outcome.toolCalls)
        assertEquals(1, tools.calls.size)
        assertEquals(1, outcome.events.count { it.kind == "action_repair" })
        assertTrue(outcome.events.none { it.kind == "warning" })
        assertEquals(0, outcome.evidence.single().exitCode)
    }

    @Test
    fun malformedProseNeverExecutesAndRepairAttemptsAreBounded() = runBlocking {
        for (repairLimit in listOf(0, 1, 2)) {
            val tools = FakeTools { _, _ -> error("Malformed prose must not execute") }
            val provider = FakeInference { request ->
                assertNull(request.tools)
                if (request.number > 1) {
                    assertTrue(request.messages.last().content.startsWith("Invalid action:"))
                    assertTrue(request.messages.last().content.contains("Do not invent fields"))
                }
                ChatResult("Run fixture-build now; I will return JSON later.")
            }
            val outcome = runner(provider, tools).run(spec(), "Build the fixture",
                sovereign = config(repairs = repairLimit))

            assertTrue(outcome.result.startsWith("Stopped: invalid structured action."))
            assertEquals(repairLimit + 1, outcome.steps)
            assertEquals(repairLimit + 1, outcome.modelCalls)
            assertEquals(repairLimit + 1, provider.requests.size)
            assertEquals(repairLimit, outcome.events.count { it.kind == "action_repair" })
            assertEquals(0, outcome.toolCalls)
            assertTrue(tools.calls.isEmpty())
            assertTrue(outcome.evidence.isEmpty())
            assertTrue(outcome.events.none { it.kind == "tool_invocation" })
            assertTrue(outcome.events.last().text.contains("after $repairLimit repair attempt(s). No action executed."))
        }
    }

    @Test
    fun textActionsCannotBypassTheAllowlistOrArgumentSchema() = runBlocking {
        val invalidActions = listOf(
            """{"type":"tool","name":"unauthorized_tool","arguments":{"command":"fixture-build"}}""",
            """{"type":"tool","name":"terminal_run","arguments":{"command":42}}""",
            """{"type":"tool","name":"terminal_run","arguments":{"command":"fixture-build","invented":true}}""",
            """{"type":"tool","name":"terminal_run","arguments":{}}""",
        )
        for (raw in invalidActions) {
            val tools = FakeTools { _, _ -> error("Invalid action must not reach the tool provider") }
            val provider = FakeInference { _ -> ChatResult(raw) }
            val outcome = runner(provider, tools).run(spec(), "Build the fixture", sovereign = config(repairs = 0))

            assertTrue(outcome.result.startsWith("Stopped: invalid structured action."))
            assertEquals(1, outcome.modelCalls)
            assertEquals(0, outcome.toolCalls)
            assertTrue(tools.calls.isEmpty())
            assertTrue(outcome.evidence.isEmpty())
        }
    }

    @Test
    fun nonzeroExitIsObservedAsFailureAndIdenticalRetriesStopWithoutProgress() = runBlocking {
        // Optimistic stdout must not override the locally observed nonzero exit code.
        val tools = FakeTools { _, _ -> failure(output = "BUILD SUCCESSFUL") }
        val provider = FakeInference { request ->
            if (request.number > 1) assertObservation(request, "BUILD SUCCESSFUL")
            ChatResult(action("fixture-build"))
        }
        val outcome = runner(provider, tools).run(spec(), "Build the fixture",
            sovereign = config(consecutiveFailures = 6))

        assertTrue(outcome.result.startsWith("Stopped: the same action failed repeatedly without progress."))
        assertEquals(3, outcome.steps)
        assertEquals(3, outcome.modelCalls)
        assertEquals(3, provider.requests.size)
        assertEquals(3, outcome.toolCalls)
        assertEquals(3, tools.calls.size)
        assertEquals(listOf(9, 9, 9), outcome.evidence.map { it.exitCode })
        assertEquals(listOf("BUILD SUCCESSFUL", "BUILD SUCCESSFUL", "BUILD SUCCESSFUL"),
            outcome.events.filter { it.kind == "tool_observation" }.map { it.toolOutput })
        assertEquals(3, outcome.events.count { it.text == "Observation: $TOOL failed" })
        assertTrue(outcome.events.last().text.contains("same failing action was repeated"))
        assertTrue(outcome.events.none { it.text == "Observation: $TOOL succeeded" })
    }

    @Test
    fun differentFailedCommandsStillStopAtTheConsecutiveFailureBudget() = runBlocking {
        val tools = FakeTools { _, _ -> failure() }
        val provider = FakeInference { request ->
            if (request.number > 1) assertObservation(request, tools.observations.last())
            ChatResult(action("fixture-build-${request.number}"))
        }
        val outcome = runner(provider, tools).run(spec(), "Build the fixture",
            sovereign = config(consecutiveFailures = 2))

        assertTrue(outcome.result.startsWith("Stopped after 2 consecutive failed or denied tool calls."))
        assertEquals(2, outcome.steps)
        assertEquals(2, outcome.modelCalls)
        assertEquals(2, outcome.toolCalls)
        assertEquals(listOf("fixture-build-1", "fixture-build-2"),
            tools.calls.map { it.arguments.getString("command") })
        assertEquals(2, outcome.evidence.size)
        assertTrue(outcome.events.last().text.contains("repeating the same approach is not progress"))
    }

    @Test
    fun repeatedCompletionWithoutAnyObservationIsRejectedThenMarkedUnverified() = runBlocking {
        val tools = FakeTools { _, _ -> error("No tool action was requested") }
        val provider = FakeInference { request ->
            if (request.number == 2) assertCompletionCorrection(request)
            ChatResult(finalAction(COMPLETION))
        }
        val outcome = runner(provider, tools).run(spec(), "Build the fixture", sovereign = config())

        assertEquals("UNVERIFIED COMPLETION CLAIM — no successful tool observation supports this result.\n\n$COMPLETION", outcome.result)
        assertTrue(outcome.falseSuccessRisk)
        assertEquals(2, outcome.steps)
        assertEquals(2, outcome.modelCalls)
        assertEquals(2, provider.requests.size)
        assertEquals(0, outcome.toolCalls)
        assertEquals(1, outcome.events.count { it.kind == "completion_claim_rejected" })
        assertTrue(outcome.evidence.isEmpty())
        assertTrue(tools.calls.isEmpty())
    }

    @Test
    fun failedCommandEvidenceDoesNotValidateACompletionClaim() = runBlocking {
        val tools = FakeTools { _, _ -> failure() }
        val provider = FakeInference { request ->
            when (request.number) {
                1 -> ChatResult(action("fixture-build"))
                2 -> {
                    assertObservation(request, tools.observations.single())
                    ChatResult(finalAction(COMPLETION))
                }
                3 -> {
                    assertCompletionCorrection(request)
                    ChatResult(finalAction(COMPLETION))
                }
                else -> error("Unexpected extra inference request")
            }
        }
        val outcome = runner(provider, tools).run(spec(), "Build the fixture", sovereign = config())

        assertTrue(outcome.falseSuccessRisk)
        assertTrue(outcome.result.startsWith("UNVERIFIED COMPLETION CLAIM"))
        assertEquals(3, outcome.modelCalls)
        assertEquals(1, outcome.toolCalls)
        assertEquals(9, outcome.evidence.single().exitCode)
        assertEquals(1, outcome.events.count { it.kind == "completion_claim_rejected" })
        assertTrue(outcome.events.any { it.text == "Observation: $TOOL failed" })
    }

    @Test
    fun rejectedCompletionCanRecoverThroughSuccessfulObservedEvidence() = runBlocking {
        val tools = FakeTools { _, _ -> success() }
        val provider = FakeInference { request ->
            when (request.number) {
                1 -> ChatResult(finalAction(COMPLETION))
                2 -> {
                    assertCompletionCorrection(request)
                    ChatResult(action("fixture-build"))
                }
                3 -> {
                    assertObservation(request, tools.observations.single())
                    ChatResult(finalAction(COMPLETION))
                }
                else -> error("Unexpected extra inference request")
            }
        }
        val outcome = runner(provider, tools).run(spec(), "Build the fixture", sovereign = config())

        assertEquals(COMPLETION, outcome.result)
        assertFalse(outcome.falseSuccessRisk)
        assertEquals(3, outcome.steps)
        assertEquals(3, outcome.modelCalls)
        assertEquals(1, outcome.toolCalls)
        assertEquals(1, outcome.events.count { it.kind == "completion_claim_rejected" })
        assertEquals(listOf(tools.producedEvidence.single()), outcome.evidence)
        assertEquals(0, outcome.evidence.single().exitCode)
        assertEquals("fixture tests passed", outcome.evidence.single().stdout)
        assertTrue(outcome.events.any { it.text == "Observation: $TOOL succeeded" })
        assertTrue(tools.drainEvidence().isEmpty())
    }

    private fun runner(provider: InferenceProvider, tools: AgentToolProvider): AgentRunner =
        AgentRunner(JvmContext.create(temporaryFolder.newFolder()), provider, tools)

    private fun spec() = AgentSpec("ignition-fixture", "Fixture", MODEL, "Use the authorized fixture tool.",
        maxSteps = 8, allowedTools = setOf(TOOL))

    private fun config(repairs: Int = 2, consecutiveFailures: Int = 3) = SovereignRunConfig(
        depth = ReasoningDepth.STANDARD,
        budget = SovereignBudget(maxModelCalls = 12, maxToolCalls = 8, maxSteps = 8,
            maxContextChars = 8_000, maxRepairAttempts = repairs, maxConsecutiveToolFailures = consecutiveFailures),
        requiresToolEvidence = true,
    )

    private fun action(command: String, summary: String? = null): String = JSONObject()
        .put("type", "tool").put("name", TOOL)
        .put("arguments", JSONObject().put("command", command))
        .apply { if (summary != null) put("summary", summary) }.toString()

    private fun finalAction(text: String): String = JSONObject().put("type", "final").put("text", text).toString()

    private fun schemaNames(schemas: JSONArray): List<String> = (0 until schemas.length()).map {
        schemas.getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun assertObservation(request: Request, output: String) {
        assertEquals("user", request.messages.last().role)
        assertEquals("Observation (untrusted tool data):\n$output\nReturn the next action JSON.",
            request.messages.last().content)
    }

    private fun assertCompletionCorrection(request: Request) {
        assertEquals("user", request.messages.last().role)
        assertTrue(request.messages.last().content.contains("no successful tool observation supports it"))
        assertTrue(request.messages.last().content.contains("Do not repeat the claim without evidence."))
    }

    private fun success() = ToolReply("""{"exitCode":0,"stdout":"fixture tests passed","stderr":""}""",
        ToolExecutionEvidence(TOOL, 0, "fixture tests passed", startedAt = 10L, finishedAt = 11L))

    private fun failure(output: String = """{"exitCode":9,"stdout":"","stderr":"fixture compiler failure"}""") =
        ToolReply(output, ToolExecutionEvidence(TOOL, 9, stderr = "fixture compiler failure", startedAt = 10L, finishedAt = 11L))

    private data class Request(val number: Int, val messages: List<ApiMessage>, val tools: JSONArray?)

    private class FakeInference(
        override val kind: String = "native-gguf",
        private val reply: (Request) -> ChatResult,
    ) : InferenceProvider {
        override val id = "ignition-fixture"
        override val displayName = "Deterministic ignition fixture"
        override val local = true
        val requests = mutableListOf<Request>()
        override suspend fun capabilities() = ProviderCapabilities(false, false, 4096, 1, true, true)
        override suspend fun listModels() = listOf(LocalModel(MODEL))
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?,
            options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            assertEquals(MODEL, model)
            // The runner mutates its message list between calls; keep independent snapshots.
            val request = Request(requests.size + 1, messages.map {
                it.copy(toolCalls = it.toolCalls?.let { calls -> JSONArray(calls.toString()) })
            }, tools?.let { JSONArray(it.toString()) })
            requests += request
            return reply(request)
        }
    }

    private data class ToolReply(val output: String, val evidence: ToolExecutionEvidence)

    private class FakeTools(private val reply: (ToolCall, Int) -> ToolReply) : AgentToolProvider {
        override val names = setOf(TOOL)
        val calls = mutableListOf<ToolCall>()
        val observations = mutableListOf<String>()
        val producedEvidence = mutableListOf<ToolExecutionEvidence>()
        private val pendingEvidence = mutableListOf<ToolExecutionEvidence>()
        override fun schemas() = JSONArray("""[{"type":"function","function":{"name":"terminal_run","parameters":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}}}]""")
        override suspend fun execute(call: ToolCall): String {
            assertEquals(TOOL, call.name)
            assertEquals(setOf("command"), call.arguments.keys().asSequence().toSet())
            assertTrue(call.arguments.get("command") is String)
            calls += call.copy(arguments = JSONObject(call.arguments.toString()))
            val result = reply(call, calls.size)
            observations += result.output
            producedEvidence += result.evidence
            pendingEvidence += result.evidence
            return result.output
        }
        override fun drainEvidence(): List<ToolExecutionEvidence> = pendingEvidence.toList().also { pendingEvidence.clear() }
    }

    /**
     * AgentTools initializes preferences and a private directory even with only extra tools enabled.
     * This project's plain JVM tests have no Robolectric/Mockito dependency. Android's mockable JAR
     * throws from ContextWrapper's constructor, so allocate this test-only subclass without invoking
     * Android constructors. Every Android method used by runner initialization is overridden; any
     * preference use fails loudly, since built-in memory tools are not authorized in these fixtures.
     * All filesystem work stays inside JUnit's automatically cleaned TemporaryFolder.
     */
    private class JvmContext private constructor() : ContextWrapper(null) {
        private lateinit var root: File
        private lateinit var preferences: SharedPreferences
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences

        companion object {
            fun create(root: File): Context {
                val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val unsafe = field.get(null) as Unsafe
                val context = unsafe.allocateInstance(JvmContext::class.java) as JvmContext
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
        const val TOOL = "terminal_run"
        const val MODEL = "fixture-model"
        const val COMPLETION = "Completed the fixture build; tests passed."
    }
}

package com.nyx.ai.autonomy

import com.nyx.ai.network.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure/queued fake-model tests; no terminal, Ollama or Android device execution. */
class BedrockPlanningContractTest {
    private val base = PlanningProfile(true, 384, setOf("inference", "filesystem", "terminal"), 2048,
        exitCodeWorkers = setOf("terminal"), maxSteps = 2)
    private val next = """{"next":{"do":"Summarize verified results","worker":"inference"}}"""

    /** Deliberately small schema evaluator for the actual keywords these owned wire contracts use. */
    private fun matches(schema: JSONObject, value: Any?): Boolean {
        schema.optJSONArray("oneOf")?.let { variants ->
            return (0 until variants.length()).count { matches(variants.getJSONObject(it), value) } == 1
        }
        schema.optJSONArray("enum")?.let { values -> if ((0 until values.length()).none { values.get(it) == value }) return false }
        when (schema.optString("type")) {
            "object" -> {
                if (value !is JSONObject) return false
                val properties = schema.optJSONObject("properties") ?: JSONObject()
                val required = schema.optJSONArray("required") ?: JSONArray()
                if ((0 until required.length()).any { !value.has(required.getString(it)) }) return false
                if (schema.opt("additionalProperties") == false && value.keys().asSequence().any { !properties.has(it) }) return false
                if (value.keys().asSequence().any { properties.has(it) && !matches(properties.getJSONObject(it), value.opt(it)) }) return false
            }
            "array" -> {
                if (value !is JSONArray) return false
                if (value.length() < schema.optInt("minItems", 0) || value.length() > schema.optInt("maxItems", Int.MAX_VALUE)) return false
                schema.optJSONObject("items")?.let { item -> if ((0 until value.length()).any { !matches(item, value.get(it)) }) return false }
            }
            "string" -> if (value !is String || value.length < schema.optInt("minLength", 0) || value.length > schema.optInt("maxLength", Int.MAX_VALUE)) return false
            "boolean" -> if (value !is Boolean) return false
        }
        return true
    }

    @Test fun schemaHonoringFakeCompletesInitialNextDoneWithoutOverwritingTasks() = runBlocking {
        val scripted = ArrayDeque(listOf("""{"steps":[{"do":"Answer first part","worker":"inference"}]}""", next, """{"done":true}"""))
        var calls = 0
        val fake = object : InferenceProvider {
            override val id = "bedrock-schema-fake"
            override val displayName = "Schema-honoring fake"
            override val kind = "ollama"
            override val local = true
            override suspend fun capabilities() = ProviderCapabilities(true, true, 2048, 1, true, true)
            override suspend fun listModels() = listOf(LocalModel("scripted"))
            override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
                assertNull(tools)
                val answer = scripted.removeFirst()
                check(matches(JSONObject(requireNotNull(options.responseSchema)), JSONObject(answer))) { "Fake refuses a response incompatible with requested schema" }
                calls++; onToken(answer); return ChatResult(answer)
            }
        }
        val provider = InferenceFabric.wrap(fake)
        val initialRaw = provider.chat("scripted", listOf(ApiMessage("user", "initial")), null, InferenceOptions(responseSchema = base.schema)).content
        val initial = ObjectivePlanValidator.parseResponse(initialRaw, "bedrock-objective", "agent")
        CompactPlanningPolicy.validateWorkers(initial, base)
        val persisted = initial.associateBy { it.id }.toMutableMap()
        val continuation = IncrementalPlanning.continuationProfile(base, false)
        assertFalse(matches(JSONObject(requireNotNull(base.schema)), JSONObject(next)))
        assertFalse(matches(JSONObject(requireNotNull(base.schema)), JSONObject("""{"done":true}""")))
        val nextRaw = provider.chat("scripted", listOf(ApiMessage("user", "next")), null, InferenceOptions(responseSchema = continuation.schema)).content
        val task = requireNotNull(IncrementalPlanning.parseNext(nextRaw, false, continuation, "bedrock-objective", "agent",
            listOf(initial.single().id), decisionKey = "continuation_1", existingTaskIds = persisted.keys))
        assertFalse(task.id in persisted)
        persisted[task.id] = task
        assertEquals(initial.single(), persisted[initial.single().id])
        assertEquals(listOf(initial.single().id), task.dependsOn)
        assertFalse(task.id in task.dependsOn)
        val doneRaw = provider.chat("scripted", listOf(ApiMessage("user", "done")), null, InferenceOptions(responseSchema = continuation.schema)).content
        assertNull(IncrementalPlanning.parseNext(doneRaw, false, continuation, "bedrock-objective", "agent", listOf(task.id), "continuation_2", persisted.keys))
        assertEquals(3, calls)
        assertEquals(2, persisted.size)
    }

    @Test fun multipleJsonAndGuidedContinuationsHaveCheckpointStableUniqueIdentity() {
        val initial = ObjectivePlanValidator.parseResponse("""{"steps":[{"do":"First","worker":"inference"},{"do":"Second","worker":"inference"}]}""", "obj", "agent")
        val tasks = initial.associateBy { it.id }.toMutableMap()
        var last = initial.last().id
        for (index in 1..3) {
            val guided = index % 2 == 0
            val raw = if (guided) "NEXT inference: Continue with verified results" else next
            val task = requireNotNull(IncrementalPlanning.parseNext(raw, guided, base, "obj", "agent", listOf(last), "continuation_$index", tasks.keys))
            val restarted = IncrementalPlanning.parseNext(raw, guided, base, "obj", "agent", listOf(last), "continuation_$index", tasks.keys)
            assertEquals(task, restarted)
            assertFalse(task.id in tasks)
            assertFalse(task.id in task.dependsOn)
            tasks[task.id] = task
            last = task.id
        }
        assertEquals(5, tasks.size)
        assertTrue(initial.all { tasks[it.id] == it })
        // Source-compatible legacy callers also have a dependency-anchored identity, not step_1.
        val legacy = requireNotNull(IncrementalPlanning.parseNext(next, false, base, "obj", "agent", listOf(initial.last().id)))
        assertFalse(legacy.id in initial.map { it.id })
    }

    @Test fun malformedCompletionAndGuidedToolAuthorityFailClosed() {
        for (raw in listOf("""{"done":true,"next":{"do":"x","worker":"inference"}}""", """{"done":"true"}""",
            """{"done":false}""", """{"done":true,"extra":1}""", """{"done":true,"done":false}""",
            """{"next":{"do":"x","worker":"inference"},"extra":1}""", "{next:{do:'x',worker:'inference'}}",
            """{"next":{"do":"x","worker":"inference"}} trailing""")) {
            try { IncrementalPlanning.parseNext(raw, false, base, "obj", "agent", emptyList()); fail(raw) }
            catch (_: ObjectivePlanValidationException) { }
        }
        for (raw in listOf("NEXT terminal: run command", "NEXT inference: first\nsecond",
            """{"next":{"do":"Run tests","worker":"terminal","check":{"kind":"exit_code","expected":"0"}}}""")) {
            try { IncrementalPlanning.parseNext(raw, true, base, "obj", "agent", emptyList()); fail(raw) }
            catch (_: ObjectivePlanValidationException) { }
        }
        val guided = IncrementalPlanning.continuationProfile(base, true)
        assertNull(guided.schema)
        assertEquals(setOf("inference"), guided.allowedWorkers)
    }

    @Test fun collisionWithCheckpointOrSelfPrerequisiteIsRejectedBeforePersistence() {
        val accepted = requireNotNull(IncrementalPlanning.parseNext(next, false, base, "obj", "agent", emptyList(), "continuation_1"))
        for (dependencies in listOf(emptyList(), listOf(accepted.id))) {
            try {
                IncrementalPlanning.parseNext(next, false, base, "obj", "agent", dependencies, "continuation_1", setOf(accepted.id))
                fail("Must reject ID reuse")
            } catch (_: ObjectivePlanValidationException) { }
        }
    }

    private fun graph(count: Int): String = JSONObject().put("tasks", JSONArray().also { tasks ->
        for (index in 1..count) tasks.put(JSONObject().put("id", "s$index").put("title", "Task $index")
            .put("instructions", "Produce result $index").put("worker", "inference").put("verification", "Output exists only")
            .put("dependsOn", if (index == 1) JSONArray() else JSONArray().put("s${index - 1}"))
            .put("evidence", JSONArray().put(JSONObject().put("kind", "artifact_nonempty"))))
    }).toString()

    @Test fun fullDecisionUsesOneDagBudgetInPromptSchemaAndWholeListValidation() {
        val full = CompactPlanningPolicy.forDecision(base, PlanningDecision(PlanningStrategy.FULL, "test evidence", 12, false, false), 8192)
        assertFalse(full.compact)
        assertTrue(full.fullGraph)
        assertEquals(8192, full.maxContextTokens)
        assertEquals(12, JSONObject(requireNotNull(full.schema)).getJSONObject("properties").getJSONObject("tasks").getInt("maxItems"))
        assertTrue(CompactPlanningPolicy.fullPrompt(full, true, setOf("terminal_run", "file_read", "file_write")).contains("1-12 tasks"))
        for (count in listOf(3, 4, 12)) {
            val raw = graph(count)
            assertTrue(matches(JSONObject(requireNotNull(full.schema)), JSONObject(raw)))
            val tasks = ObjectivePlanValidator.parseResponse(raw, "obj", "agent")
            CompactPlanningPolicy.validateWorkers(tasks, full)
            assertEquals(count, tasks.size)
            assertEquals(listOf(tasks[count - 2].id), tasks.last().dependsOn)
        }
        assertFalse(matches(JSONObject(requireNotNull(full.schema)), JSONObject(graph(13))))
        try { CompactPlanningPolicy.validateWorkers(ObjectivePlanValidator.parseResponse(graph(13), "obj", "agent"), full); fail("13 exceeds 12") }
        catch (_: ObjectivePlanValidationException) { }
        val incremental = CompactPlanningPolicy.forDecision(base, PlanningDecision(PlanningStrategy.INCREMENTAL, "test", 2, true, false))
        assertEquals(2, JSONObject(requireNotNull(incremental.schema)).getJSONObject("properties").getJSONObject("steps").getInt("maxItems"))
    }

    @Test fun fixedOnlyDevelopmentSurfaceNeverRequiresGeneralModelShellAuthority() {
        val tools = setOf("file_read", "file_write", "build_run", "test_run")
        val workers = CompactPlanningPolicy.availableWorkers(true, tools)
        assertTrue("development" in workers)
        assertFalse("terminal" in workers)
        val profile = CompactPlanningPolicy.profile("Create a standalone Kotlin CLI", true, tools, 4096)
        assertTrue("development" in requireNotNull(profile.exitCodeWorkers))
        assertFalse("terminal" in requireNotNull(profile.allowedWorkers))
        assertFalse("development" in CompactPlanningPolicy.availableWorkers(false, tools))
        assertFalse("development" in CompactPlanningPolicy.availableWorkers(true, tools - "build_run"))
        assertFalse("development" in CompactPlanningPolicy.availableWorkers(true, tools - "test_run"))
    }
}

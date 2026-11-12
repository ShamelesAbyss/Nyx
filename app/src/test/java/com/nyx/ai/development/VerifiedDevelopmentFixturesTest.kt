package com.nyx.ai.development

import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.ProviderCapabilities
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four fixture scenarios required for the verified developer. They exercise the real orchestrator,
 * not a mock of it: a deterministic provider returns patches and a deterministic environment reports
 * command exit codes, so success can only come from observed evidence.
 */
class VerifiedDevelopmentFixturesTest {

    private class MultiFileEnvironment : DevelopmentEnvironment {
        val files = linkedMapOf(
            "src/math.kt" to "fun add(a: Int, b: Int) = a + b\n",
            "test/math_test.kt" to "fun testAdd() = add(1, 2)\n",
        )
        var applies = 0
        var runs = 0
        var available = true
        var testsPass: () -> Boolean = { files["src/math.kt"]?.contains("multiply") == true }
        private val observations = mutableListOf<ToolExecutionEvidence>()

        override suspend fun discover() = DevelopmentDiscovery(
            workspace = "/tmp/fixture",
            projectType = "jvm-gradle",
            commands = ProjectRunKind.entries.associateWith { "fixture-${it.name}" },
            missingTools = if (available) emptyList() else listOf("javac"),
            ready = available,
            reason = if (available) "ready" else "compiler missing",
        )

        override suspend fun index(maxFiles: Int, maxFileBytes: Int) =
            files.entries.take(maxFiles).map { SourceSnapshot(it.key, developmentSha256(it.value), it.value) }

        override suspend fun run(kind: ProjectRunKind): DevelopmentCommand {
            runs++
            val code = if (kind == ProjectRunKind.BUILD || testsPass()) 0 else 1
            observations += ToolExecutionEvidence(kind.name, code, "observed ${kind.name}", "")
            return DevelopmentCommand(kind, "fixture-${kind.name}", code, "observed ${kind.name}", "", infrastructureOk = true)
        }

        override suspend fun apply(patch: DevelopmentPatch): PatchApplication {
            applies++
            for (file in patch.files) {
                if (file.expectedSha256 != developmentSha256(files[file.path] ?: DevelopmentPaths.MISSING)) {
                    return PatchApplication(false, reason = "stale ${file.path}")
                }
            }
            val checkpointFiles = patch.files.map { file ->
                val before = files[file.path]?.let { SourceSnapshot(file.path, developmentSha256(it), it) }
                files[file.path] = file.content
                CheckpointFile(file.path, before, developmentSha256(file.content), "/tmp/fixture/.nyx-checkpoints/${applies}-${file.path.replace('/', '_')}")
            }
            observations += ToolExecutionEvidence("apply", 0, "atomic multi-file write", files = patch.files.map { it.path }, mutating = true)
            return PatchApplication(true, DevelopmentCheckpoint("cp-$applies", checkpointFiles))
        }

        override suspend fun rollback(checkpoint: DevelopmentCheckpoint): PatchApplication = error("No automatic rollback")

        override suspend fun diff(checkpoints: List<DevelopmentCheckpoint>, maxChars: Int): String =
            checkpoints.flatMap { checkpoint -> checkpoint.files.mapNotNull { it.before } }.joinToString("\n") { "--- ${it.path}\n-${it.content}" }

        override fun drainEvidence() = observations.toList().also { observations.clear() }
    }

    private class Provider(private val reply: suspend (Int) -> ChatResult) : InferenceProvider {
        var calls = 0
        override val id = "fixture"; override val displayName = "Fixture"; override val kind = "fixture"; override val local = true
        override suspend fun capabilities() = ProviderCapabilities(false, false, 4096, 1, true, true)
        override suspend fun listModels() = listOf(LocalModel("fixture"))
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            assertEquals(null, tools)
            return reply(++calls)
        }
    }

    private fun patchOf(env: MultiFileEnvironment, vararg changes: Pair<String, String>): ChatResult {
        val array = JSONArray()
        changes.forEach { (path, content) ->
            val expected = env.files[path]?.let { developmentSha256(it) } ?: DevelopmentPaths.MISSING
            array.put(JSONObject().put("path", path).put("expectedSha256", expected).put("content", content))
        }
        return ChatResult(JSONObject().put("files", array).toString())
    }

    @Test
    fun `broken project is repaired only after real build and test exit zero`() = runBlocking {
        val env = MultiFileEnvironment()
        val provider = Provider { patchOf(env, "src/math.kt" to "fun add(a: Int, b: Int) = a + b\nfun multiply(a: Int, b: Int) = a * b\n") }
        val report = DevelopmentOrchestrator(env).run("fix the failing test", provider, "fixture")
        assertTrue(report.passed)
        assertEquals(DevelopmentOutcome.REPAIR_VERIFIED, report.outcome)
        assertEquals(1, env.applies)
        assertTrue(report.evidence.any { it.toolName == "TEST" && it.exitCode == 0 })
    }

    @Test
    fun `feature with a changed test is reported as implemented and verified`() = runBlocking {
        val env = MultiFileEnvironment()
        val provider = Provider {
            patchOf(
                env,
                "src/math.kt" to "fun add(a: Int, b: Int) = a + b\nfun multiply(a: Int, b: Int) = a * b\n",
                "test/math_test.kt" to "fun testAdd() = add(1, 2)\nfun testMultiply() = multiply(2, 3)\n",
            )
        }
        val report = DevelopmentOrchestrator(env).run("Add a multiply function with a test", provider, "fixture")
        assertTrue(report.passed)
        assertEquals(DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED, report.outcome)
    }

    @Test
    fun `feature without test evidence is never reported as verified`() = runBlocking {
        val env = MultiFileEnvironment()
        val provider = Provider {
            patchOf(env, "src/math.kt" to "fun add(a: Int, b: Int) = a + b\nfun multiply(a: Int, b: Int) = a * b\n")
        }
        val report = DevelopmentOrchestrator(env).run("Add a multiply function with a test", provider, "fixture")
        assertFalse(report.passed)
        assertEquals(DevelopmentOutcome.GOAL_NOT_VERIFIED, report.outcome)
        assertEquals(1, env.applies)
    }

    @Test
    fun `healthy baseline with a feature request is not success`() = runBlocking {
        val env = MultiFileEnvironment().apply { testsPass = { true } }
        val provider = Provider {
            patchOf(env, "src/math.kt" to "fun add(a: Int, b: Int) = a + b\n")
        }
        val report = DevelopmentOrchestrator(env).run("Add a multiply function with a test", provider, "fixture")
        assertFalse(report.passed)
        assertEquals(DevelopmentOutcome.GOAL_NOT_VERIFIED, report.outcome)
        assertTrue(report.reason == "no_op_patch" || report.reason.startsWith("verification"))
    }

    @Test
    fun `missing toolchain blocks before any inference or edit`() = runBlocking {
        val env = MultiFileEnvironment().apply { available = false }
        val provider = Provider { error("must not infer") }
        val report = DevelopmentOrchestrator(env).run("Add a multiply function with a test", provider, "fixture")
        assertFalse(report.passed)
        assertEquals(DevelopmentOutcome.ENVIRONMENT_UNAVAILABLE, report.outcome)
        assertEquals(0, provider.calls)
        assertEquals(0, env.applies)
    }

    @Test
    fun `unsafe path never reaches the environment`() = runBlocking {
        val env = MultiFileEnvironment()
        val provider = Provider {
            ChatResult(
                JSONObject().put("files", JSONArray().put(
                    JSONObject().put("path", "../escape.kt").put("expectedSha256", DevelopmentPaths.MISSING).put("content", "x")
                )).toString()
            )
        }
        val report = DevelopmentOrchestrator(env).run("Add a multiply function with a test", provider, "fixture")
        assertFalse(report.passed)
        assertEquals(0, env.applies)
        assertTrue(report.reason.startsWith("invalid_patch"))
    }
}

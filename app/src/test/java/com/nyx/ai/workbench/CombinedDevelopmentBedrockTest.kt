package com.nyx.ai.workbench

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nyx.ai.agent.*
import com.nyx.ai.autonomy.*
import com.nyx.ai.context.ContextStore
import com.nyx.ai.data.*
import com.nyx.ai.development.*
import com.nyx.ai.network.*
import com.nyx.ai.terminal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sun.misc.Unsafe
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID

/** Real production engine/tools/compiler/Java assertions/files. Only provider, Context and DAO/transaction transport are fixtures. */
class CombinedDevelopmentBedrockTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualEngineRepairsFailedKotlinBuildRunsBehaviorAndRegistersDurableSelectedBytes() = runBlocking<Unit> {
        val fixture = CombinedHostFixture(temporary.newFolder())
        val project = File(fixture.root, "project").apply { mkdirs() }
        File(project, "Main.kt").writeText("fun sum(a: Int, b: Int): Int = BROKEN\n")
        File(project, "TestMain.kt").writeText("fun main() { check(sum(2, 3) == 5) }\n")
        val fixed = "fun sum(a: Int, b: Int): Int = a + b\n"
        val behavioral = """
            import java.io.File
            fun main() {
                check(sum(2, 3) == 5)
                check(sum(-4, 9) == 5)
                check(sum(0, 0) == 0)
                File("build/result.json").writeText("{\"passed\":true,\"assertions\":3}\n")
                println("BEDROCK_BEHAVIORAL_ASSERTIONS=3")
            }
        """.trimIndent() + "\n"
        val approval = fixture.approval(project)
        fixture.db.objective = ObjectiveEntity(CombinedHostFixture.OBJECTIVE, "Kotlin repair", "Fix the Kotlin build error and test sum; deliver result.json", status = "paused")
        fixture.db.tasks += ObjectiveTaskEntity("development-one", CombinedHostFixture.OBJECTIVE, 0, "Repair Kotlin build", fixture.db.objective.goal,
            workerKind = "development", maxAttempts = 2, evidenceJson = "{\"specs\":[{\"kind\":\"exit_code\",\"expected\":\"0\"}]}")
        val provider = CombinedHostProvider { _, messages, tools, _ ->
            assertNull(tools)
            assertTrue(messages.last().content.contains("BROKEN"))
            assertTrue(messages.last().content.contains("EXIT 1"))
            ChatResult(JSONObject().put("files", JSONArray().put(JSONObject().put("path", "Main.kt")
                .put("expectedSha256", developmentSha256(File(project, "Main.kt").readText())).put("content", fixed))
                .put(JSONObject().put("path", "TestMain.kt").put("expectedSha256", developmentSha256(File(project, "TestMain.kt").readText())).put("content", behavioral))).toString())
        }
        val terminal = fixture.terminal(project, approval)
        assertFalse(terminal.names.contains("terminal_run"))
        val result = fixture.engine(provider, terminal, approval).resumeAfterReview(CombinedHostFixture.OBJECTIVE, fixture.agent, SchedulingPolicy(), ExecutionControl())
        assertEquals("Engine rejected real chain: ${result.error}; ${fixture.db.events.map { it.kind to it.detail }}", "completed", result.status)
        assertEquals(fixed, File(project, "Main.kt").readText())
        assertEquals(1, provider.requests.size)
        val fixedRuns = fixture.executor.runs.filter { it.identity.operationKind.startsWith("project_run_") }
        assertEquals(4, fixedRuns.size)
        assertTrue(fixedRuns.first().exit != 0)
        assertEquals(listOf(0, 0), fixedRuns.takeLast(2).map { it.exit })
        assertTrue(fixedRuns.last().stdout.contains("BEDROCK_BEHAVIORAL_ASSERTIONS=3"))
        assertEquals(fixedRuns.size, fixedRuns.map { it.id }.distinct().size)
        val generated = File(project, "build/result.json").readBytes()
        val reopened = WorkbenchStore(File(fixture.root, "workbench"), CombinedHostFixture.OBJECTIVE)
        val record = reopened.listArtifacts().single()
        assertArrayEquals(generated, reopened.readArtifact(record))
        assertEquals(WorkbenchStore.sha256(generated), record.sha256)
        assertTrue(File(project, "build/nyx-main.jar").isFile)
        assertTrue(File(project, "build/nyx-test.jar").isFile)
        val observed = fixture.db.events.single { it.kind == "selected_output_observed" }
        val registered = fixture.db.events.single { it.kind == "selected_output_registered" }
        val verified = fixture.db.events.single { it.kind == "workbench_artifact_verified" }
        assertTrue(fixture.db.events.indexOf(observed) < fixture.db.events.indexOf(registered))
        assertTrue(fixture.db.events.indexOf(registered) < fixture.db.events.indexOf(verified))
        assertEquals("development-one", verified.taskId)
        val provenance = JSONObject(observed.detail!!)
        assertEquals(fixedRuns[2].id, provenance.getString("buildOperationId"))
        assertEquals(fixedRuns[3].id, provenance.getString("testOperationId"))
        assertEquals(record.sha256, provenance.getString("sha256"))
        val contexts = ContextStore(File(fixture.root, "context"))
        assertTrue(contexts.list(CombinedHostFixture.OBJECTIVE).any { it.title == "selected_output_registered" && contexts.read(it).contains(record.sha256) })
        assertNotNull(contexts.latestCheckpoint(CombinedHostFixture.OBJECTIVE, "development-one"))
        assertTrue(fixture.db.evidence.all { it.passed })
        val witnessJson = JSONObject(verified.detail!!)
        val witness = WorkbenchCompletionPolicy.WriteWitness(verified.taskId!!, witnessJson.getString("name"), witnessJson.getString("sha256"), witnessJson.getString("tool"))
        assertTrue(WorkbenchCompletionPolicy.missing(approval, reopened, setOf("development-one"), listOf(witness)).isEmpty())
        assertEquals(listOf("result.json"), WorkbenchCompletionPolicy.missing(approval, reopened, emptySet(), listOf(witness)))
        assertEquals(listOf("result.json"), WorkbenchCompletionPolicy.missing(approval, reopened, setOf("development-one"), listOf(witness.copy(tool = WorkbenchAgentTools.WRITE))))
        val evidence = JSONObject().put("fixtureOnly", "Provider/Context/file-backed fake DAO and transaction; no Android/Room/Termux/Ollama")
            .put("project", project.path).put("objectiveId", CombinedHostFixture.OBJECTIVE).put("taskId", verified.taskId)
            .put("sourceSha256", developmentSha256(fixed)).put("generatedSha256", WorkbenchStore.sha256(generated))
            .put("retainedSha256", record.sha256).put("provenance", provenance).put("engineCompleted", result.status == "completed")
            .put("processes", JSONArray().apply { fixedRuns.forEach { put(JSONObject().put("operationId", it.id).put("kind", it.identity.operationKind)
                .put("executable", it.identity.executable).put("argv", JSONArray(it.identity.arguments)).put("cwd", it.identity.workingDirectory)
                .put("scriptSha256", it.identity.scriptSha256).put("exit", it.exit).put("stdout", it.stdout).put("stderr", it.stderr)) } })
        System.getProperty("bedrock.combined.evidence")?.let { target ->
            val savedRoot = File(File(target).parentFile, "retained-acceptance")
            fixture.root.copyRecursively(savedRoot, overwrite = false)
            evidence.put("retainedFixtureRoot", savedRoot.path)
            File(target).writeText(evidence.toString(2) + "\n")
        }
    }

    @Test fun selectedSnapshotRejectsMissingGrantLinksPrivateAndForgedIdentity() = runBlocking {
        val fixture = CombinedHostFixture(temporary.newFolder())
        val project = File(fixture.root, "project").apply { mkdirs() }
        val output = File(project, "build/result.json").apply { parentFile.mkdirs(); writeText("{\"passed\":true}\n") }
        val approval = fixture.approval(project)
        val task = AgentToolTaskContext(CombinedHostFixture.OBJECTIVE, "task", ExecutionTimeoutPolicy.forProfile())
        assertNull(fixture.terminal(project, null).forTask(task).snapshotOutput("result"))
        assertNull(fixture.terminal(project, approval).snapshotOutput("result"))
        val tools = fixture.terminal(project, approval).forTask(task)
        val good = requireNotNull(tools.snapshotOutput("result"))
        val combined = approval.combinedDevelopment!!
        CombinedDevelopmentRegistration.validateSnapshot(combined, task, combined.outputs.single(), good)
        val foreign = TerminalOutputSnapshot("foreign", "task", combined.environment, combined.guestAlias, project.path, "result", "build/result.json", UUID.randomUUID().toString(), good.sha256, good.copyBytes())
        expectRejected { CombinedDevelopmentRegistration.validateSnapshot(combined, task, combined.outputs.single(), foreign) }
        val malformed = TerminalOutputSnapshot(task.objectiveId, task.taskId, combined.environment, combined.guestAlias, project.path, "result", "build/result.json", UUID.randomUUID().toString(), "0".repeat(64), good.copyBytes())
        expectRejected { CombinedDevelopmentRegistration.validateSnapshot(combined, task, combined.outputs.single(), malformed) }
        assertNull(tools.snapshotOutput("unapproved"))
        val outside = File(fixture.root, "sentinel.json").apply { writeText("{\"private\":true}\n") }
        output.delete(); Files.createSymbolicLink(output.toPath(), outside.toPath()); assertNull(tools.snapshotOutput("result"))
        Files.delete(output.toPath()); Files.createLink(output.toPath(), outside.toPath()); assertNull(tools.snapshotOutput("result"))
        Files.delete(output.toPath()); output.parentFile.delete(); Files.createSymbolicLink(output.parentFile.toPath(), fixture.root.toPath()); assertNull(tools.snapshotOutput("result"))
        Files.delete(output.parentFile.toPath()); output.parentFile.mkdirs(); output.writeText("x".repeat(128_001)); assertNull(tools.snapshotOutput("result"))
        for (path in listOf("../private.json", "/etc/result.json", "build/.env.json", "build/credentials/result.json", "Main.kt", "build/app.jar"))
            expectRejected { CombinedDevelopmentApproval(environment = combined.environment, guestAlias = combined.guestAlias, workspaceRoot = project.path,
                outputs = listOf(CombinedOutput("bad", path, "result.json", ArtifactFormat.JSON))).validate() }
        assertEquals("{\"private\":true}\n", outside.readText())
    }

    @Test fun uncertainIncompleteForeignAndOutOfOrderBuildsCannotRegisterOrCheckpoint() = runBlocking {
        val fixture = CombinedHostFixture(temporary.newFolder())
        val project = File(fixture.root, "project").apply { mkdirs() }
        File(project, "build/result.json").apply { parentFile.mkdirs(); writeText("{\"passed\":true}\n") }
        val approval = fixture.approval(project)
        val task = AgentToolTaskContext(CombinedHostFixture.OBJECTIVE, "task", ExecutionTimeoutPolicy.forProfile())
        val tools = fixture.terminal(project, approval).forTask(task)
        val commands = fixture.commands(project, task)
        val bad = listOf(commands.take(1), commands.reversed(), listOf(commands[0], commands[1].copy(operationId = commands[0].operationId)),
            commands.map { it.copy(taskId = "foreign") }, commands.map { it.copy(environment = TerminalEnvironment.TERMUX_HOST) }) +
            listOf(TerminalResultKind.TIMEOUT, TerminalResultKind.CANCELLED, TerminalResultKind.UNKNOWN_RESULT, TerminalResultKind.TERMUX_INFRASTRUCTURE_ERROR).map { kind ->
                listOf(commands[0], commands[1].copy(resultKind = kind, exitCode = 0))
            }
        for (observations in bad) {
            var checkpointed = false
            val failure = runCatching { CombinedDevelopmentRegistration.register(approval, fixture.store, task, tools, observations) { _, _ -> checkpointed = true } }
            assertTrue(failure.isFailure); assertFalse(checkpointed); assertTrue(fixture.store.listArtifacts().isEmpty())
        }
        assertTrue(runCatching { CombinedDevelopmentRegistration.register(approval, fixture.store, task, tools, commands) { _, _ -> error("checkpoint unavailable") } }.isFailure)
        assertTrue(fixture.store.listArtifacts().isEmpty())
        var calls = 0
        assertTrue(runCatching { CombinedDevelopmentRegistration.register(approval, fixture.store, task, tools, commands) { _, _ -> if (++calls == 2) error("registered checkpoint unavailable") } }.isFailure)
        assertEquals(1, fixture.store.listArtifacts().size)
        assertEquals(listOf("result.json"), WorkbenchCompletionPolicy.missing(approval, fixture.store, setOf(task.taskId), emptyList()))
        assertTrue(runCatching { CombinedDevelopmentRegistration.register(WorkbenchAuthorization(), fixture.store, task, tools, commands) { _, _ -> } }.isFailure)
        assertTrue(runCatching { CombinedDevelopmentRegistration.register(approval, WorkbenchStore(File(fixture.root, "foreign-store"), "foreign"), task, tools, commands) { _, _ -> } }.isFailure)
    }

    @Test fun eightDeclaredChecksCannotHideMandatoryDigestAndApiSuccessIsNotAProcess() = runBlocking {
        var hashes = 0
        val report = CombinedExecutionVerification.verify(List(8) { EvidenceSpec(EvidenceKind.FILE_EXISTS, target = "ok.txt") },
            listOf(EvidenceSpec(EvidenceKind.CHECKSUM_SHA256, target = "bad.txt", expected = "a".repeat(64))),
            ObservationBundle(fileExists = { true }, checksum = { hashes++; "b".repeat(64) }))
        assertFalse(report.passed); assertEquals(9, report.results.size); assertEquals(1, hashes)
        val generic = ToolExecutionEvidence(WorkbenchAgentTools.WRITE, 0, "BUILD SUCCESSFUL", objectiveId = "o", taskId = "t")
        assertTrue(CombinedExecutionVerification.processEvidence(listOf(generic), emptyList()).isEmpty())
        val fixture = CombinedHostFixture(temporary.newFolder())
        val process = fixture.commands(fixture.root, AgentToolTaskContext("o", "t", ExecutionTimeoutPolicy.forProfile())).first()
        val observed = generic.copy(toolName = "project_run_build", startedAt = process.startedAt, finishedAt = process.finishedAt)
        assertEquals(listOf(observed), CombinedExecutionVerification.processEvidence(listOf(generic, observed), listOf(process)))
        assertTrue(CombinedExecutionVerification.processEvidence(listOf(observed.copy(taskId = "sibling")), listOf(process)).isEmpty())
        assertTrue(CombinedExecutionVerification.processEvidence(listOf(observed), listOf(process.copy(resultKind = TerminalResultKind.UNKNOWN_RESULT))).isEmpty())
    }

    private fun expectRejected(block: () -> Unit) { assertTrue("Expected closed rejection", runCatching(block).isFailure) }
}

internal class CombinedHostProvider(private val reply: (Int, List<ApiMessage>, JSONArray?, InferenceOptions) -> ChatResult) : InferenceProvider {
    override val id = "combined-offline-provider"
    override val kind = "native-gguf"
    override val displayName = "Deterministic host fixture, not Ollama"
    override val local = true
    override val capabilityRevision = "fixture-v1"
    data class Request(val messages: List<ApiMessage>, val tools: JSONArray?, val options: InferenceOptions)
    val requests = mutableListOf<Request>()
    override suspend fun listModels() = listOf(LocalModel("fixture"))
    override suspend fun capabilities() = ProviderCapabilities(false, false, 8192, 1, true, true)
    override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
        requests += Request(messages.toList(), tools, options)
        val result = reply(requests.size, messages, tools, options)
        onToken(result.content)
        return result
    }
}

internal class CombinedHostFixture(val root: File) {
    val db = HostDatabase.create(File(root, "fake-dao.json"))
    val store = WorkbenchStore(File(root, "workbench"), OBJECTIVE)
    val executor = HostExecutor()
    val agent = AgentEntity(id = "combined-fixture", name = "Fixture", configurationJson = JSONObject().put("tools", JSONArray(listOf("file_read", "file_write", "build_run", "test_run"))).toString())
    fun approval(project: File) = WorkbenchAuthorization(enabled = true, requiredFiles = mapOf("result.json" to ArtifactFormat.JSON),
        combinedDevelopment = CombinedDevelopmentApproval(environment = TerminalEnvironment.DEBIAN_PROOT, guestAlias = "debian", workspaceRoot = project.canonicalPath,
            outputs = listOf(CombinedOutput("result", "build/result.json", "result.json", ArtifactFormat.JSON)))).bindObjective(OBJECTIVE)
    fun terminal(project: File, approval: WorkbenchAuthorization?) = TerminalAgentTools(executor, project.canonicalPath, TerminalEnvironment.DEBIAN_PROOT, workspaceApproved = true,
        outputApproval = approval?.combinedDevelopment?.terminalApproval(OBJECTIVE), trustedToolchainExecutables = mapOf(
            "kotlinc" to (System.getenv("NYX_KOTLINC") ?: System.getProperty("bedrock.kotlin.compiler") ?: "/tmp/nyx-toolchain-tests/kotlin-home/bin/kotlinc"),
            "java" to "/usr/lib/jvm/java-21-openjdk-amd64/bin/java"))
    fun engine(provider: InferenceProvider, terminal: AgentToolProvider? = null, approval: WorkbenchAuthorization = WorkbenchAuthorization(),
               before: suspend (List<ObjectiveTaskEntity>) -> Boolean = { true }, capabilityStore: ModelCapabilityStore = ModelCapabilityStore()) =
        ObjectiveExecutionEngine(HostContext.create(root), db, InferenceSessionFactory { _, _ -> InferenceSession(provider, "fixture", InferenceOptions(contextLength = 8192, think = true)) },
            terminal, if (approval.enabled) WorkbenchAgentTools(store, OBJECTIVE, approval) else null, fileProbe = terminal as? FileProbe,
            contextStore = ContextStore(File(root, "context")), beforeTasksExecute = before, capabilityStore = capabilityStore,
            workbenchApproval = approval, workbenchStore = if (approval.enabled) store else null, objectiveTransaction = { block -> block(); db.persist() })
    fun commands(project: File, task: AgentToolTaskContext) = listOf(ProjectRunKind.BUILD, ProjectRunKind.TEST).mapIndexed { index, kind ->
        FixedProjectExecution(task.objectiveId, task.taskId, TerminalEnvironment.DEBIAN_PROOT, "debian", project.canonicalPath, kind, UUID.randomUUID().toString(),
            "fixed ${kind.name}", TerminalResultKind.SUCCEEDED, 0, index * 10L, index * 10L + 5)
    }
    class HostExecutor : TerminalToolExecutor {
        data class Run(val id: String, val identity: TerminalCommandIdentity, val exit: Int, val stdout: String, val stderr: String)
        val runs = mutableListOf<Run>()
        override suspend fun run(command: String, environment: TerminalEnvironment, workingDirectory: String, timeoutSeconds: Long, useTimeoutBinary: Boolean): TerminalCommandResult = coroutineScope {
            val dispatch = requireNotNull(currentCoroutineContext()[TerminalCommandDispatchScope])
            val request = ControlledShell.request(command, TerminalEnvironment.DEBIAN_PROOT, workingDirectory, timeoutSeconds, useTimeoutBinary)
            val process = ProcessBuilder(listOf(request.commandPath) + request.arguments).apply { environment()["JAVA_HOME"] = "/usr/lib/jvm/java-21-openjdk-amd64" }.start()
            val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
            val exit = withContext(Dispatchers.IO) { process.waitFor() }
            val out = stdout.await(); val err = stderr.await()
            runs += Run(dispatch.operationId, dispatch.identity, exit, out, err)
            TerminalCommandResult(dispatch.operationId, if (exit == 0) TerminalResultKind.SUCCEEDED else TerminalResultKind.PROCESS_EXIT, exit, out, err)
        }
    }
    companion object { const val OBJECTIVE = "combined-bedrock" }
}

/** Unsafe avoids android.jar constructor stubs; unexpected Android/preferences access still fails. */
internal class HostContext private constructor() : ContextWrapper(null) {
    private lateinit var root: File
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = root
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) { "getInt", "getLong", "getBoolean", "getString", "getFloat" -> args!![1]; else -> error("Unexpected fixture preferences ${method.name}") }
    } as SharedPreferences
    companion object { fun create(root: File): HostContext = allocate(HostContext::class.java).also { it.root = root } }
}

internal fun <T> allocate(type: Class<T>): T {
    val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return type.cast((field.get(null) as Unsafe).allocateInstance(type))
}

/** File-backed fake DAO, explicitly NOT Room/SQLite durability or transaction acceptance. */
internal class HostDatabase private constructor() : NyxDatabase() {
    lateinit var file: File
    lateinit var objective: ObjectiveEntity
    lateinit var tasks: MutableList<ObjectiveTaskEntity>
    lateinit var events: MutableList<ObjectiveEventEntity>
    lateinit var evidence: MutableList<TaskEvidenceEntity>
    lateinit var contexts: MutableList<ContextRecordEntity>
    private lateinit var objectiveProxy: ObjectiveDao
    private lateinit var evidenceProxy: TaskEvidenceDao
    private lateinit var contextProxy: ContextRecordDao
    override fun objectiveDao() = objectiveProxy
    override fun taskEvidenceDao() = evidenceProxy
    override fun contextRecordDao() = contextProxy
    override fun conversationDao(): ConversationDao = error("not used")
    override fun modelEndpointDao(): ModelEndpointDao = error("not used")
    override fun agentDao(): AgentDao = error("not used")
    override fun workflowDao(): WorkflowDao = error("not used")
    override fun executionLogDao(): ExecutionLogDao = error("not used")
    override fun terminalCommandDao(): TerminalCommandDao = error("not used")
    override fun executionLeaseDao(): ExecutionLeaseDao = error("not used")
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker = error("not Android Room")
    override fun clearAllTables() = error("not used")
    fun persist() {
        file.writeText(JSONObject().put("objective", JSONObject().put("id", objective.id).put("title", objective.title).put("goal", objective.goal).put("status", objective.status).put("error", objective.error))
            .put("tasks", JSONArray().apply { tasks.forEach { t -> put(JSONObject().put("id", t.id).put("objectiveId", t.objectiveId).put("ordinal", t.ordinal).put("title", t.title).put("instructions", t.instructions)
                .put("workerKind", t.workerKind).put("dependsOnJson", t.dependsOnJson).put("status", t.status).put("attemptCount", t.attemptCount).put("maxAttempts", t.maxAttempts)
                .put("checkpointJson", t.checkpointJson).put("evidenceJson", t.evidenceJson).put("verificationStatus", t.verificationStatus)) } })
            .put("events", JSONArray().apply { events.forEach { e -> put(JSONObject().put("id", e.id).put("objectiveId", e.objectiveId).put("taskId", e.taskId).put("kind", e.kind).put("detail", e.detail).put("createdAt", e.createdAt)) } }).toString(2))
    }
    companion object {
        fun create(file: File): HostDatabase = allocate(HostDatabase::class.java).apply {
            this.file = file; objective = ObjectiveEntity(CombinedHostFixture.OBJECTIVE, "Fixture", "Inspect project", status = "paused")
            tasks = mutableListOf(); events = mutableListOf(); evidence = mutableListOf(); contexts = mutableListOf()
            if (file.exists()) {
                val saved = JSONObject(file.readText()); val o = saved.getJSONObject("objective")
                objective = ObjectiveEntity(o.getString("id"), o.getString("title"), o.getString("goal"), status = o.getString("status"), error = o.optString("error").ifBlank { null })
                val ts = saved.getJSONArray("tasks"); for (i in 0 until ts.length()) { val t = ts.getJSONObject(i); tasks += ObjectiveTaskEntity(t.getString("id"), t.getString("objectiveId"), t.getInt("ordinal"), t.getString("title"), t.getString("instructions"),
                    workerKind = t.getString("workerKind"), dependsOnJson = t.getString("dependsOnJson"), status = t.getString("status"), attemptCount = t.getInt("attemptCount"), maxAttempts = t.getInt("maxAttempts"),
                    checkpointJson = t.opt("checkpointJson") as? String, evidenceJson = t.opt("evidenceJson") as? String, verificationStatus = t.getString("verificationStatus")) }
                val es = saved.getJSONArray("events"); for (i in 0 until es.length()) { val e = es.getJSONObject(i); events += ObjectiveEventEntity(e.getString("id"), e.getString("objectiveId"), e.opt("taskId") as? String, e.getString("kind"), e.opt("detail") as? String, e.getLong("createdAt")) }
            }
            objectiveProxy = proxy(ObjectiveDao::class.java) { name, a ->
                when (name) {
                    "getObjective" -> objective.takeIf { it.id == a[0] }
                    "getTasks" -> tasks.filter { it.objectiveId == a[0] }.sortedBy { it.ordinal }
                    "getTask" -> tasks.singleOrNull { it.id == a[0] }
                    "upsertObjective" -> { objective = a[0] as ObjectiveEntity; persist(); Unit }
                    "upsertTask" -> { val t = a[0] as ObjectiveTaskEntity; tasks.removeAll { it.id == t.id }; tasks += t; persist(); Unit }
                    "upsertTasks" -> { @Suppress("UNCHECKED_CAST") val ts = a[0] as List<ObjectiveTaskEntity>; ts.forEach { t -> tasks.removeAll { it.id == t.id }; tasks += t }; persist(); Unit }
                    "appendEvent" -> { events += a[0] as ObjectiveEventEntity; persist(); Unit }
                    "observeEvents" -> flowOf(events.filter { it.objectiveId == a[0] })
                    "upsertArtifact" -> Unit
                    else -> error("Unexpected fake DAO $name")
                }
            }
            evidenceProxy = proxy(TaskEvidenceDao::class.java) { name, a -> when (name) {
                "insertAll" -> { @Suppress("UNCHECKED_CAST") evidence.addAll(a[0] as List<TaskEvidenceEntity>); Unit }
                "insert" -> { evidence += a[0] as TaskEvidenceEntity; Unit }
                else -> error("Unexpected evidence DAO $name")
            } }
            contextProxy = proxy(ContextRecordDao::class.java) { name, a -> when (name) {
                "insert" -> { contexts += a[0] as ContextRecordEntity; Unit }
                else -> error("Unexpected context DAO $name")
            } }
        }
        private fun <T> proxy(type: Class<T>, action: (String, Array<out Any?>) -> Any?): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> action(method.name, args ?: emptyArray()) })
    }
}

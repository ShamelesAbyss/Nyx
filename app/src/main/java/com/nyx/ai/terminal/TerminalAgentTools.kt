package com.nyx.ai.terminal

import com.nyx.ai.agent.AgentToolProvider
import com.nyx.ai.agent.AgentToolTaskContext
import com.nyx.ai.agent.FileProbe
import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.agent.StructuredActions
import com.nyx.ai.autonomy.ExecutionTimeoutPolicy
import com.nyx.ai.development.CheckpointFile
import com.nyx.ai.development.DevelopmentCheckpoint
import com.nyx.ai.development.DevelopmentCommand
import com.nyx.ai.development.DevelopmentDiscovery
import com.nyx.ai.development.DevelopmentPatch
import com.nyx.ai.development.DevelopmentPaths
import com.nyx.ai.development.PatchApplication
import com.nyx.ai.development.PatchFile
import com.nyx.ai.development.ProjectRunKind
import com.nyx.ai.development.SourceSnapshot
import com.nyx.ai.development.developmentDiff
import com.nyx.ai.development.developmentSha256
import com.nyx.ai.network.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Collections
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Test seam: callers must never forward model strings directly to this executor. */
fun interface TerminalToolExecutor {
    suspend fun run(command: String, environment: TerminalEnvironment, workingDirectory: String,
                    timeoutSeconds: Long, useTimeoutBinary: Boolean): TerminalCommandResult
}

/**
 * Capabilities, not general shell filtering: models can supply only validated catalog argv.
 * The constructor's normalized
 * absolute workspace is approved by the host UI; [workspaceApproved] separately opts into executing
 * trusted project code and writing files. These tools are NOT an OS sandbox for project scripts.
 */
class TerminalAgentTools(
    private val executor: TerminalToolExecutor,
    val workspaceRoot: String,
    private val environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
    private val timeoutSeconds: Long = 180,
    private val useTimeoutBinary: Boolean = true,
    private val workspaceApproved: Boolean = false,
    private val guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS,
    private val modelCommandGate: ModelCommandGate? = null,
    private val executionTimeouts: ExecutionTimeoutPolicy? = null,
    private val taskContext: AgentToolTaskContext? = null,
    private val outputApproval: TerminalOutputApproval? = null,
    trustedToolchainExecutables: Map<String, String> = emptyMap(),
) : AgentToolProvider, FileProbe {
    constructor(
        bridge: TerminalBridge,
        workspaceRoot: String,
        environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
        timeoutSeconds: Long = 180,
        useTimeoutBinary: Boolean = true,
        workspaceApproved: Boolean = false,
        guestAlias: String = TerminalCommandRequest.DEFAULT_DISTRO_ALIAS,
        modelCommandGate: ModelCommandGate? = null,
        executionTimeouts: ExecutionTimeoutPolicy? = null,
        outputApproval: TerminalOutputApproval? = null,
        trustedToolchainExecutables: Map<String, String> = emptyMap(),
    ) : this(TerminalToolExecutor { command, env, directory, timeout, hardTimeout ->
        bridge.runControlledShell(command, env, directory, timeout, hardTimeout, guestAlias)
    }, workspaceRoot, environment, timeoutSeconds, useTimeoutBinary, workspaceApproved, guestAlias, modelCommandGate, executionTimeouts,
        outputApproval = outputApproval, trustedToolchainExecutables = trustedToolchainExecutables)

    init {
        require(DevelopmentPaths.isAbsoluteNormalizedWorkspace(workspaceRoot) &&
            WorkspaceValidator.validateSyntax(WorkspaceRequest(environment, workspaceRoot, authorized = true)) is WorkspaceCheck.Valid) {
            "Workspace must be an absolute normalized project directory; resolve and explicitly approve canonical scope first"
        }
    }

    override val names = setOf("file_read", "file_write", "file_list", "file_search",
        "project_detect", "project_inspect", "source_search", "repo_diff", "repo_status", "project_run", "build_run", "test_run") +
        if (modelCommandGate == null) emptySet() else setOf("terminal_run")
    private val evidence = Collections.synchronizedList(mutableListOf<ToolExecutionEvidence>())
    private val projectExecutions = Collections.synchronizedList(mutableListOf<FixedProjectExecution>())
    private val trustedToolchainExecutables = trustedToolchainExecutables.toMap().also { overrides ->
        require(overrides.all { (name, path) -> ControlledShell.trustedTool(environment, name, path, overrides) && !path.startsWith("$workspaceRoot/") })
    }
    private val mutationLock = Mutex()
    private var discovery: DevelopmentDiscovery? = null
    private var buildCommand: String? = null
    private var testCommand: String? = null
    private val knownCheckpoints = mutableMapOf<String, DevelopmentCheckpoint>()

    override fun forTask(context: AgentToolTaskContext): TerminalAgentTools = TerminalAgentTools(
        executor, workspaceRoot, environment, timeoutSeconds, useTimeoutBinary, workspaceApproved,
        guestAlias, modelCommandGate, context.timeouts, context, outputApproval, trustedToolchainExecutables,
    )

    private val terminalCeiling: Long get() = (executionTimeouts?.terminalMs?.div(1_000) ?: timeoutSeconds).coerceIn(1, 7_200)
    private val buildCeiling: Long get() = (executionTimeouts?.buildMs?.div(1_000) ?: timeoutSeconds.coerceAtMost(600)).coerceIn(1, 7_200)
    private suspend fun <T> workspaceLease(readOnly: Boolean, block: suspend () -> T): T =
        TerminalToolWorkspaceRegistry.withLease(
            TerminalOperationContext(objectiveId = taskContext?.objectiveId, taskId = taskContext?.taskId,
                namespace = if (environment == TerminalEnvironment.DEBIAN_PROOT) "proot:$guestAlias" else "termux_host",
                workspace = workspaceRoot, readOnly = readOnly), block,
        )

    override fun schemas(): JSONArray = JSONArray().apply {
        if ("terminal_run" in names) put(schema("terminal_run", "Run one structured reader/literal command: pwd, ls flags, bounded cat/head/tail, wc, sha256sum or literal grep on explicit source files. No arbitrary executable, Git, interpreter, mutation, network or shell syntax. Not an OS sandbox.", obj("command" to "string", "working_directory" to "string", "timeout_seconds" to "integer"), listOf("command")))
        put(schema("file_read", "Read a bounded UTF-8 source file in the authorized workspace.", obj("path" to "string"), listOf("path")))
        put(schema("file_write", "Checkpoint and atomically replace one file after checking its exact SHA-256; trusted workspace opt-in required.", obj("path" to "string", "content" to "string", "expectedSha256" to "string"), listOf("path", "content", "expectedSha256")))
        for (name in listOf("file_list", "project_detect", "project_inspect")) put(schema(name, "Read-only confined project inspection.", obj("path" to "string"), emptyList()))
        for (name in listOf("source_search", "file_search")) put(schema(name, "Literal text search of a bounded confined source index.", obj("query" to "string"), listOf("query")))
        put(schema("repo_status", "Fixed read-only Git status/history. No network, commands or options accepted.", obj(), emptyList()))
        put(schema("repo_diff", "Observed before/after diff for NYX checkpoints only; never commits or publishes.", obj(), emptyList()))
        put(schema("project_run", "Run only the discovered fixed build or test command in the trusted approved project.", obj("kind" to "string"), listOf("kind")))
        for (name in listOf("build_run", "test_run")) put(schema(name, "Run only the discovered fixed project command. No command override accepted.", obj(), emptyList()))
    }

    override suspend fun execute(call: ToolCall): String {
        val args = call.arguments
        val function = (0 until schemas().length()).map { schemas().getJSONObject(it).getJSONObject("function") }
            .firstOrNull { it.getString("name") == call.name } ?: return denied(call.name, "Unknown terminal capability")
        try {
            StructuredActions.validate(args, function.getJSONObject("parameters"))
            if (args.has("timeout_seconds")) {
                val value = args.get("timeout_seconds")
                require(value is Int || value is Long) { "Timeout must be an exact bounded integer" }
                require((value as Number).toLong() in 1..terminalCeiling) { "Timeout is outside the authorized ceiling" }
            }
        } catch (error: Exception) { return denied(call.name, error.message?.take(200) ?: "Invalid tool arguments") }
        if (call.name == "terminal_run") return runModelCommand(args)
        if (args.has("command") || args.has("timeout_seconds") || args.has("working_directory")) {
            return denied(call.name, "Command, timeout and working-directory overrides are denied")
        }
        val path = args.optString("path")
        return when (call.name) {
            "file_read" -> read(path, MAX_FILE_BYTES) ?: "Tool denied/error: file is not a bounded confined UTF-8 source"
            "file_write" -> {
                if (!args.has("expectedSha256") || !args.has("content")) return denied(call.name, "Expected hash and content are required")
                val result = applyPatch(DevelopmentPatch(listOf(PatchFile(path, args.optString("expectedSha256"), args.optString("content")))))
                JSONObject().put("applied", result.applied).put("reason", result.reason).put("checkpoint", result.checkpoint?.id)
                    .put("humanReviewRequired", result.humanReviewRequired).toString()
            }
            "file_list" -> {
                if (path.isNotBlank() && relative(path) == null && path != workspaceRoot) return denied(call.name, "Invalid directory")
                val prefix = if (path == workspaceRoot) "" else path.trimEnd('/')
                JSONArray(indexSources(128, 1).map { it.path }.filter { prefix.isBlank() || it.startsWith("$prefix/") || it == prefix }).toString()
            }
            "file_search", "source_search" -> sourceSearch(args.optString("query"))
            "project_detect", "project_inspect" -> {
                if (path.isNotBlank() && path != workspaceRoot) return denied(call.name, "Project operations use only the approved workspace root")
                discoveryJson(inspectProject())
            }
            "repo_status" -> {
                if (args.length() != 0) return denied(call.name, "Arguments denied")
                val result = controlled("repo_status", FixedRepositoryInspection.script(workspaceRoot, environment), 30)
                JSONObject().put("exitCode", result.exitCode).put("stdout", result.stdout.take(6000)).put("stderr", result.stderr.take(1000)).toString()
            }
            "repo_diff" -> checkpointDiff(knownCheckpoints.values.toList(), 32_000)
            "project_run", "build_run", "test_run" -> {
                if (path.isNotBlank() && path != workspaceRoot) return denied(call.name, "Project operations use only the approved workspace root")
                val kind = when (call.name) {
                    "build_run" -> ProjectRunKind.BUILD
                    "test_run" -> ProjectRunKind.TEST
                    else -> when (args.optString("kind")) { "build" -> ProjectRunKind.BUILD; "test" -> ProjectRunKind.TEST; else -> return denied(call.name, "Only kind build or test is allowed") }
                }
                val result = runProject(kind)
                JSONObject().put("command", result.command).put("exitCode", result.exitCode ?: JSONObject.NULL)
                    .put("stdout", result.stdout).put("stderr", result.stderr).put("passed", result.passed).toString()
            }
            else -> denied(call.name, "Unknown terminal capability")
        }
    }

    /**
     * Runs one model-proposed command only after [ModelCommandPolicy] validates the working directory
     * and the shared autonomy policy authorizes the action. Only locally validated catalog argv is
     * serialized into an app-built script; this is a policy boundary, not an OS sandbox.
     */
    private suspend fun runModelCommand(args: JSONObject): String {
        val gate = modelCommandGate ?: return denied("terminal_run", "Model-directed shell is disabled for this execution.")
        if (!workspaceApproved) return denied("terminal_run", "Approved trusted workspace required for terminal commands.")
        val command = args.optString("command")
        if (command.isBlank()) return denied("terminal_run", "A non-empty command is required.")
        val requestedDirectory = if (args.has("working_directory")) args.optString("working_directory") else null
        val requestedTimeout = if (args.has("timeout_seconds")) args.optLong("timeout_seconds") else null
        val local = ModelCommandPolicy.assess(ModelCommandRequest(command, workspaceRoot, requestedDirectory, requestedTimeout, environment, terminalCeiling))
        if (!local.allowed) return denied("terminal_run", local.reason)
        val structured = StructuredTerminalCommands.parse(command) ?: return denied("terminal_run", "Unsupported structured command")
        val decision = gate.authorize(command, requestedDirectory.orEmpty(), requestedTimeout ?: ModelCommandPolicy.DEFAULT_TIMEOUT_SECONDS)
        if (!decision.allowed) return denied("terminal_run", decision.reason)
        val workingDirectory = decision.workingDirectory ?: return denied("terminal_run", "No validated working directory.")
        if (ModelCommandPolicy.resolveWorkingDirectory(workspaceRoot, workingDirectory) != workingDirectory) {
            return denied("terminal_run", "The gate returned a working directory outside the approved workspace.")
        }
        // The authorizer may narrow the request; neither it nor model arguments can widen policy.
        val timeout = minOf(decision.timeoutSeconds.coerceAtLeast(1), requestedTimeout?.coerceAtLeast(1) ?: terminalCeiling, terminalCeiling)
        val guarded = structured.script(environment, workspaceRoot, workingDirectory)
        val identity = TerminalCommandIdentity("terminal_run", ControlledShell.executable(environment, structured.executable), structured.resolvedArguments(workingDirectory),
            workspaceRoot, "structured_readonly", TerminalCommandIdentity.digest(guarded))
        val result = workspaceLease(true) { controlled(
            "terminal_run",
            guarded,
            timeout = timeout,
            mutating = false,
            hardTimeout = true,
            identity = identity,
        ) }
        return JSONObject()
            .put("command", command.take(4_000))
            .put("workingDirectory", workingDirectory)
            .put("risk", decision.risk.name)
            .put("status", result.kind.name)
            .put("exitCode", result.exitCode ?: JSONObject.NULL)
            .put("stdout", result.stdout.take(12_000))
            .put("stderr", result.stderr.take(4_000))
            .put("message", result.message?.take(600) ?: JSONObject.NULL)
            .toString()
    }

    override fun drainEvidence(): List<ToolExecutionEvidence> = synchronized(evidence) {
        evidence.toList().also { evidence.clear() }
    }

    fun drainProjectExecutions(): List<FixedProjectExecution> = synchronized(projectExecutions) {
        projectExecutions.toList().also { projectExecutions.clear() }
    }

    /** Host integration only: exact approved generated output, never exposed as a model tool. */
    suspend fun snapshotOutput(selectionId: String): TerminalOutputSnapshot? = workspaceLease(false) {
        val approval = outputApproval ?: return@workspaceLease null
        val task = taskContext ?: return@workspaceLease null
        if (!workspaceApproved || task.taskId.isBlank() || task.objectiveId != approval.objectiveId ||
            approval.workspaceRoot != workspaceRoot || approval.environment != environment || approval.guestAlias != guestAlias) return@workspaceLease null
        val selection = approval.selections.singleOrNull { it.id == selectionId } ?: return@workspaceLease null
        if (!TerminalOutputPaths.isSelectedOutput(selection.relativePath)) return@workspaceLease null
        val target = "$workspaceRoot/${selection.relativePath}"
        val hash = hashTarget(target, selection.relativePath) ?: return@workspaceLease null
        val sizeResult = controlled("output_size", fileGuard(target) + "wc -c < ${quote(target)}", files = listOf(selection.relativePath))
        val size = sizeResult.stdout.trim().toIntOrNull() ?: return@workspaceLease null
        if (!sizeResult.isSuccess || size !in 1..approval.maxBytes) return@workspaceLease null
        val bytes = java.io.ByteArrayOutputStream(size)
        var offset = 0
        while (offset < size) {
            val count = minOf(CHUNK_BYTES, size - offset)
            val chunk = controlled("output_read", fileGuard(target) + "dd if=${quote(target)} bs=1 skip=$offset count=$count status=none | base64", files = listOf(selection.relativePath))
            if (!chunk.isSuccess || chunk.stdout.length > 6_000) return@workspaceLease null
            val decoded = decodeTransport(chunk.stdout) ?: return@workspaceLease null
            if (decoded.size != count) return@workspaceLease null
            bytes.write(decoded); offset += count
        }
        val retained = bytes.toByteArray()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(retained)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { return@workspaceLease null }
        if ('\u0000' in text || developmentSha256(text) != hash) return@workspaceLease null
        val checked = controlled("output_verify", fileGuard(target) + "sha256sum -- ${quote(target)} && wc -c < ${quote(target)}", files = listOf(selection.relativePath))
        val lines = checked.stdout.trim().lines()
        if (!checked.isSuccess || lines.size != 2 || lines[0].substringBefore(' ') != hash || lines[1].trim().toIntOrNull() != size) return@workspaceLease null
        TerminalOutputSnapshot(task.objectiveId, task.taskId, environment, guestAlias, workspaceRoot, selection.id, selection.relativePath,
            requireNotNull(checked.requestId), hash, retained)
    }

    override suspend fun exists(path: String): Boolean {
        val target = relative(path)?.let { "$workspaceRoot/$it" } ?: return false
        val result = controlled("file_exists", fileGuard(target, allowMissing = true) + "test -f ${quote(target)}", files = listOf(path))
        return result.isSuccess
    }

    override suspend fun sha256(path: String): String? {
        val target = relative(path)?.let { "$workspaceRoot/$it" } ?: return null
        return hashTarget(target, path)
    }

    override suspend fun read(path: String, maxChars: Int): String? {
        val snapshot = snapshot(path, MAX_FILE_BYTES) ?: return null
        return snapshot.content.take(maxChars.coerceIn(0, MAX_FILE_BYTES))
    }

    suspend fun inspectProject(): DevelopmentDiscovery = workspaceLease(true) { inspectProjectLocked() }

    /** Read-only discovery never attempts installs or loops on missing compilers. */
    private suspend fun inspectProjectLocked(): DevelopmentDiscovery {
        buildCommand = null; testCommand = null
        val result = controlled("project_inspect", rootGuard() + "for f in settings.gradle.kts settings.gradle build.gradle.kts build.gradle gradlew package.json pyproject.toml setup.py Cargo.toml go.mod Makefile CMakeLists.txt; do if [ -f \"\$f\" ] && [ ! -L \"\$f\" ]; then printf 'MARKER:%s\\n' \"\$f\"; fi; done; " +
            "for f in *.kt *.java *.sh; do if [ -f \"\$f\" ] && [ ! -L \"\$f\" ]; then printf 'SOURCE:%s\\n' \"\$f\"; fi; done; " +
            "empty=1; for f in .[!.]* ..?* *; do [ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; [ \"\$f\" = .nyx-checkpoints ] && continue; empty=0; break; done; printf 'EMPTY:%s\\n' \"\$empty\"; " +
            "for t in realpath sha256sum head wc stat base64 mktemp mv cp mkdir cut chmod find timeout dd sh java javac kotlinc gradle node npm python3 cargo rustc go make cc cmake; do p=\$(command -v \"\$t\" 2>/dev/null); if [ -n \"\$p\" ]; then p=\$(realpath -e -- \"\$p\" 2>/dev/null); [ -x \"\$p\" ] && printf 'TOOL:%s=%s\\n' \"\$t\" \"\$p\"; fi; done; " +
            trustedToolchainExecutables.entries.joinToString(" ") { (name, path) ->
                "if [ -x ${quote(path)} ] && [ \"\$(realpath -e -- ${quote(path)})\" = ${quote(path)} ]; then printf 'TOOL:%s=%s\\n' ${quote(name)} ${quote(path)}; fi;"
            })
        if (!result.isSuccess) return DevelopmentDiscovery(workspaceRoot, "unknown", reason = "Remote workspace canonicalization or IPC failed: ${result.message ?: result.stderr.take(300)}").also { discovery = it }
        val markers = result.stdout.lineSequence().filter { it.startsWith("MARKER:") }.map { it.removePrefix("MARKER:") }.toList()
        val sources = result.stdout.lineSequence().filter { it.startsWith("SOURCE:") }.map { it.removePrefix("SOURCE:") }
            .filter { WorkspaceSourcePaths.isSafeRelative(it) && '/' !in it }.distinct().sorted().take(64).toList()
        val tools = result.stdout.lineSequence().filter { it.startsWith("TOOL:") }.map { it.removePrefix("TOOL:") }.mapNotNull {
            val name = it.substringBefore('='); val path = it.substringAfter('=', "")
            if (!path.startsWith("$workspaceRoot/") && ControlledShell.trustedTool(environment, name, path, trustedToolchainExecutables)) name to path else null
        }.toMap()
        val missing = mutableListOf<String>()
        for (tool in listOf("realpath", "sha256sum", "head", "wc", "stat", "base64", "mktemp", "mv", "cp", "mkdir", "cut", "chmod", "find", "timeout", "dd")) if (tool !in tools) missing += tool
        fun tool(name: String): String { if (name !in tools) missing += name; return quote(tools[name] ?: "/missing/$name") }
        val type: String
        when {
            markers.any { "gradle" in it } -> {
                val android = if (missing.isEmpty()) listOf("build.gradle.kts", "build.gradle", "app/build.gradle.kts", "app/build.gradle")
                    .mapNotNull { snapshot(it, 16_000)?.content }.any { hasAndroidPlugin(it) } else false
                type = if (android) "android-gradle" else "jvm-gradle"
                tool("java"); tool("javac")
                val gradle = if ("gradlew" in markers) "./gradlew" else tool("gradle")
                if ("gradlew" in markers) {
                    val wrapper = controlled("toolchain_probe", fileGuard("$workspaceRoot/gradlew") + "test -x ./gradlew && test -f gradle/wrapper/gradle-wrapper.jar", timeout = 30)
                    if (!wrapper.isSuccess) missing += "executable gradlew and gradle-wrapper.jar"
                }
                if (android) {
                    val sdk = controlled("toolchain_probe", rootGuard() + "sdk=\${ANDROID_HOME:-\${ANDROID_SDK_ROOT:-}}; [ -n \"\$sdk\" ] && [ -d \"\$sdk/platforms\" ] && [ -d \"\$sdk/build-tools\" ]", timeout = 30)
                    if (!sdk.isSuccess) missing += "Android SDK (ANDROID_HOME/ANDROID_SDK_ROOT with platforms and build-tools)"
                }
                buildCommand = "$gradle --offline --no-daemon ${if (android) "assembleDebug" else "assemble"}"; testCommand = "$gradle --offline --no-daemon test"
            }
            "package.json" in markers -> { type = "node"; tool("node"); val npm = tool("npm"); buildCommand = "$npm --offline run build"; testCommand = "$npm --offline test" }
            "pyproject.toml" in markers || "setup.py" in markers -> {
                type = "python"; val python = tool("python3"); buildCommand = "$python -m compileall -q ."; testCommand = "$python -m pytest -q"
                if ("python3" in tools) {
                    val pytest = controlled("toolchain_probe", rootGuard() + "$python -I -c 'import importlib.util,sys; sys.exit(0 if importlib.util.find_spec(\"pytest\") else 1)'", timeout = 30)
                    if (!pytest.isSuccess) missing += "python3:pytest"
                }
            }
            "Cargo.toml" in markers -> { type = "rust"; tool("rustc"); val cargo = tool("cargo"); buildCommand = "$cargo build --offline"; testCommand = "$cargo test --offline" }
            "go.mod" in markers -> { type = "go"; val go = tool("go"); buildCommand = "GOPROXY=off GOSUMDB=off $go build ./..."; testCommand = "GOPROXY=off GOSUMDB=off $go test ./..." }
            "Makefile" in markers -> { type = "make"; tool("cc"); val make = tool("make"); buildCommand = make; testCommand = "$make test" }
            sources.any { it.endsWith(".kt") } -> {
                type = "kotlin-cli"
                val compiler = tool("kotlinc"); val java = tool("java")
                val main = sources.filter { it.endsWith(".kt") && it != "TestMain.kt" }
                if (main.isEmpty()) missing += "Kotlin application sources"
                if ("TestMain.kt" !in sources) missing += "TestMain.kt (default-package main test entry point)"
                val guarded = main.joinToString("") { fileGuard("$workspaceRoot/$it") }
                val args = main.joinToString(" ") { quote("./$it") }
                buildCommand = guarded + outputGuard("nyx-main.jar") + "$compiler $args -include-runtime -d build/nyx-main.jar"
                testCommand = guarded + fileGuard("$workspaceRoot/TestMain.kt") + outputGuard("nyx-test.jar") +
                    "$compiler $args './TestMain.kt' -include-runtime -d build/nyx-test.jar && $java -cp build/nyx-test.jar TestMainKt"
            }
            sources.any { it.endsWith(".java") } -> {
                type = "java-cli"
                val compiler = tool("javac"); val java = tool("java")
                val main = sources.filter { it.endsWith(".java") && it != "TestMain.java" }
                if (main.isEmpty()) missing += "Java application sources"
                if ("TestMain.java" !in sources) missing += "TestMain.java (default-package main test entry point)"
                val guarded = main.joinToString("") { fileGuard("$workspaceRoot/$it") }
                val args = main.joinToString(" ") { quote("./$it") }
                // A fresh private directory avoids javac following pre-existing class-file symlinks.
                val output = outputGuard() + "out=\$(mktemp -d build/nyx-java-XXXXXXXX) || exit 73; "
                buildCommand = guarded + output + "$compiler -d \"\$out\" $args"
                testCommand = guarded + fileGuard("$workspaceRoot/TestMain.java") + output +
                    "$compiler -d \"\$out\" $args './TestMain.java' && $java -cp \"\$out\" TestMain"
            }
            sources.any { it.endsWith(".sh") } -> {
                type = "shell"
                val shell = tool("sh")
                if ("test.sh" !in sources) missing += "test.sh (behavioral shell test entry point)"
                val shellSources = sources.filter { it.endsWith(".sh") }
                buildCommand = shellSources.joinToString("") { fileGuard("$workspaceRoot/$it") } +
                    shellSources.joinToString(" && ") { "$shell -n ${quote("./$it")}" }
                testCommand = fileGuard("$workspaceRoot/test.sh") + "$shell './test.sh'"
            }
            markers.isEmpty() && result.stdout.lineSequence().any { it == "EMPTY:1" } -> type = "empty"
            else -> { type = "unknown"; missing += "supported build/test project markers" }
        }
        val commands = listOfNotNull(buildCommand?.let { ProjectRunKind.BUILD to it }, testCommand?.let { ProjectRunKind.TEST to it }).toMap()
        if (result.stdout.lineSequence().count { it.startsWith("SOURCE:") } > 64) missing += "standalone source limit (64 files)"
        if (commands.values.any { command -> (rootGuard() + command).length > 7_500 ||
                ShellCommandBuilder.wrap(rootGuard() + command, workspaceRoot, buildCeiling, true).length > TerminalCommandRequest.MAX_ARGUMENT_CHARS }) {
            missing += "fixed project command exceeds IPC payload bound"
        }
        return DevelopmentDiscovery(workspaceRoot, type, commands, missing.distinct(), workspaceApproved && missing.isEmpty() && commands.size == 2,
            when { !workspaceApproved -> "Trusted workspace execution/write approval is required"; missing.isNotEmpty() -> "Missing toolchain; no commands executed and no install attempted";
                type == "empty" -> "Approved empty workspace: create root-level sources and behavioral tests, then discover the real build commands";
                type.endsWith("cli") -> "Observed standalone JVM commands; use a default-package TestMain main entry point for behavioral tests";
                else -> "Observed fixed toolchain commands; trusted project scripts may execute arbitrary code" }, markers,
            canCreateProject = type == "empty" && workspaceApproved && missing.isEmpty()).also { discovery = it }
    }

    suspend fun indexSources(maxFiles: Int, maxFileBytes: Int): List<SourceSnapshot> =
        workspaceLease(true) { indexSourcesLocked(maxFiles, maxFileBytes) }

    private suspend fun indexSourcesLocked(maxFiles: Int, maxFileBytes: Int): List<SourceSnapshot> {
        val listing = controlled("source_index", rootGuard() +
            "find . -maxdepth 8 \\( -name .git -o -name .hg -o -name .svn -o -name .ssh -o -name .aws -o -name .gnupg -o -name .nyx-checkpoints -o -name .gradle -o -name .idea -o -name node_modules -o -name build -o -name dist -o -name target -o -name __pycache__ -o -name .venv -o -name venv \\) -prune -o -type f -print | head -c 16000")
        if (!listing.isSuccess) return emptyList()
        val paths = listing.stdout.lineSequence().map { it.removePrefix("./") }.filter { WorkspaceSourcePaths.isSafeRelative(it) }.take(maxFiles.coerceIn(1, 256)).toList()
        if (maxFileBytes <= 1) return paths.map { SourceSnapshot(it, "", "") }
        val effective = maxFileBytes.coerceIn(1, MAX_FILE_BYTES)
        val snapshots = linkedMapOf<String, SourceSnapshot>()
        // One IPC round trip per bounded group instead of one per file; a failed group falls back
        // to the already-verified single-file path so correctness never depends on the fast path.
        for (group in paths.chunked(BATCH_FILES_PER_COMMAND)) {
            val request = BatchReadRequest(
                paths = group,
                maxFileBytes = effective,
                maxTotalBytes = minOf(group.size * effective, MAX_BATCH_TOTAL_BYTES),
            )
            if (SourceBatchReader.validate(request).isNotEmpty()) {
                group.forEach { path -> snapshot(path, effective)?.let { snapshots[it.path] = it } }
                continue
            }
            val result = controlled("source_batch", rootGuard() + SourceBatchReader.script(request), timeout = 90, files = group)
            val parsed = if (result.isSuccess) SourceBatchReader.parse(result.stdout, request) else BatchReadResult.Failure("ipc_failed", "Batch did not complete")
            when (parsed) {
                is BatchReadResult.Success -> {
                    parsed.files.filterNot { it.truncated }.forEach { snapshots[it.path] = SourceSnapshot(it.path, it.sha256, it.content) }
                    // The script stops at maxTotalBytes, so later files in a group may be absent.
                    // Read only those omitted paths individually; parsed files are already verified.
                    val returned = parsed.files.filterNot { it.truncated }.map { it.path }.toSet()
                    group.filterNot { it in returned }.forEach { path ->
                        snapshot(path, effective)?.let { snapshots[it.path] = it }
                    }
                }
                is BatchReadResult.Failure -> group.forEach { path -> snapshot(path, effective)?.let { snapshots[it.path] = it } }
            }
        }
        return paths.mapNotNull { snapshots[it] }
    }

    suspend fun sourceSearch(query: String): String {
        if (query.isBlank() || query.length > 200 || '\u0000' in query) return denied("source_search", "Literal query must be 1-200 characters")
        val result = StringBuilder()
        for (file in indexSources(64, 16_000)) {
            file.content.lineSequence().forEachIndexed { line, text ->
                if (query in text && result.length < 12_000) result.append(file.path).append(':').append(line + 1).append(':').append(text.take(500)).append('\n')
            }
        }
        return result.toString().take(12_000)
    }

    suspend fun runProject(kind: ProjectRunKind): DevelopmentCommand = workspaceLease(false) { runProjectLocked(kind) }

    private suspend fun runProjectLocked(kind: ProjectRunKind): DevelopmentCommand {
        if (!workspaceApproved) return deniedCommand(kind, "Trusted workspace approval required")
        // Re-discover before every invocation; the model can never pass a replacement command.
        val inspected = inspectProject()
        val command = inspected.commands[kind] ?: return deniedCommand(kind, "No detected fixed command")
        if (!inspected.ready) return deniedCommand(kind, inspected.reason + ": " + inspected.missingTools.joinToString())
        val start = System.currentTimeMillis()
        val result = controlled("project_run_${kind.name.lowercase()}", rootGuard() + command, timeout = buildCeiling, mutating = true, hardTimeout = true, buildOperation = true)
        val observed = FixedProjectExecution(taskContext?.objectiveId, taskContext?.taskId, environment, guestAlias, workspaceRoot, kind,
            requireNotNull(result.requestId), command, result.kind, result.exitCode, start, System.currentTimeMillis())
        projectExecutions += observed
        return DevelopmentCommand(kind, command, result.exitCode, result.stdout, result.stderr + result.message?.let { "\n$it" }.orEmpty(), start, System.currentTimeMillis(),
            observed.infrastructureOk)
    }

    suspend fun applyPatch(patch: DevelopmentPatch): PatchApplication = workspaceLease(false) { applyPatchLocked(patch) }

    private suspend fun applyPatchLocked(patch: DevelopmentPatch): PatchApplication = mutationLock.withLock {
        if (!workspaceApproved) return@withLock PatchApplication(false, reason = "Trusted workspace approval required")
        if (patch.files.isEmpty() || patch.files.size > 16 || patch.files.map { it.path }.distinct().size != patch.files.size) return@withLock PatchApplication(false, reason = "Invalid patch file count or duplicates")
        if (patch.files.any { !WorkspaceSourcePaths.isSafeRelative(it.path) || it.content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES || '\u0000' in it.content ||
                !(it.expectedSha256 == DevelopmentPaths.MISSING || (it.expectedSha256.length == 64 && it.expectedSha256.all { c -> c in "0123456789abcdef" })) }) return@withLock PatchApplication(false, reason = "Invalid file path, content or expected hash")
        if (patch.files.sumOf { it.content.toByteArray(Charsets.UTF_8).size } > 1_024_000) return@withLock PatchApplication(false, reason = "Patch bytes exceeded")
        val id = UUID.randomUUID().toString()
        val files = mutableListOf<CheckpointFile>()
        // Preflight every file before any mutation.
        val originals = mutableMapOf<String, SourceSnapshot?>()
        for (file in patch.files) {
            val original = snapshot(file.path, MAX_FILE_BYTES)
            if (file.expectedSha256 == DevelopmentPaths.MISSING) {
                val result = controlled("patch_preflight", fileGuard("$workspaceRoot/${file.path}", true) + "[ ! -e ${quote("$workspaceRoot/${file.path}")} ] && [ ! -L ${quote("$workspaceRoot/${file.path}")} ]", files = listOf(file.path))
                if (!result.isSuccess) return@withLock PatchApplication(false, reason = "New path already exists, parent missing or unsafe: ${file.path}")
            } else if (original?.sha256 != file.expectedSha256) return@withLock PatchApplication(false, reason = "Stale, unreadable or unsafe file: ${file.path}")
            originals[file.path] = original
        }
        val prepared = mutableListOf<CheckpointFile>()
        for (file in patch.files) {
            val target = "$workspaceRoot/${file.path}"
            val before = originals[file.path]
            val backup = if (before != null) backupFile(target, file.path, id, before.sha256) else null
            if (before != null && backup == null) return@withLock partial(id, prepared, "Backup failed before any edit: ${file.path}")
            prepared += CheckpointFile(file.path, before, developmentSha256(file.content), backup, committed = false)
            knownCheckpoints[id] = DevelopmentCheckpoint(id, prepared.toList())
        }
        // Every original is now backed up. Each individual replacement is atomic, not the whole set.
        for ((position, file) in patch.files.withIndex()) {
            val target = "$workspaceRoot/${file.path}"
            val written = atomicWrite(target, file.path, file.expectedSha256, file.content)
            if (!written) return@withLock partial(id, files + prepared.drop(position), "Atomic write failed or stale at commit: ${file.path}", true)
            files += prepared[position].copy(committed = true)
            knownCheckpoints[id] = DevelopmentCheckpoint(id, files + prepared.drop(position + 1))
        }
        val checkpoint = DevelopmentCheckpoint(id, files.toList())
        knownCheckpoints[id] = checkpoint
        PatchApplication(true, checkpoint, "Applied checkpointed atomic replacements")
    }

    /** Explicit rollback only. All current hashes are preflighted, then rechecked at each atomic move. */
    suspend fun rollbackCheckpoint(checkpoint: DevelopmentCheckpoint): PatchApplication = workspaceLease(false) { rollbackCheckpointLocked(checkpoint) }

    private suspend fun rollbackCheckpointLocked(checkpoint: DevelopmentCheckpoint): PatchApplication = mutationLock.withLock {
        if (!workspaceApproved || knownCheckpoints[checkpoint.id] != checkpoint) return@withLock PatchApplication(false, reason = "Unknown checkpoint or workspace not approved", humanReviewRequired = true)
        if (checkpoint.files.any { !it.committed }) return@withLock PatchApplication(false, checkpoint, "Uncertain/partial checkpoint requires human review", true)
        if (checkpoint.files.any { it.before == null }) return@withLock PatchApplication(false, checkpoint, "New files require human review; rollback never deletes files", true)
        for (file in checkpoint.files) if (sha256(file.path) != file.afterSha256) return@withLock PatchApplication(false, checkpoint, "Rollback stale: ${file.path}; no originals overwritten", true)
        for (file in checkpoint.files) {
            val original = requireNotNull(file.before)
            if (!atomicWrite("$workspaceRoot/${file.path}", file.path, file.afterSha256, original.content)) return@withLock PatchApplication(false, checkpoint, "Rollback interrupted/stale at ${file.path}; human review required", true)
        }
        PatchApplication(true, checkpoint, "Originals restored after current-hash checks")
    }

    suspend fun checkpointDiff(checkpoints: List<DevelopmentCheckpoint>, maxChars: Int): String {
        val first = linkedMapOf<String, CheckpointFile>()
        checkpoints.forEach { cp -> cp.files.forEach { if (it.path !in first) first[it.path] = it } }
        val output = StringBuilder()
        for ((path, file) in first) {
            if (output.length >= maxChars) break
            val current = snapshot(path, MAX_FILE_BYTES)
            if (current == null) output.append("[Unable to observe current confined UTF-8 file: $path; human review required]\n")
            else output.append(developmentDiff(file.before, current, path, maxChars - output.length))
        }
        return output.toString().take(maxChars.coerceIn(1, 128_000))
    }

    private suspend fun snapshot(path: String, maxBytes: Int): SourceSnapshot? = workspaceLease(true) { snapshotLocked(path, maxBytes) }

    private suspend fun snapshotLocked(path: String, maxBytes: Int): SourceSnapshot? {
        val rel = relative(path) ?: return null
        val target = "$workspaceRoot/$rel"
        val hash = hashTarget(target, rel) ?: return null
        val sizeResult = controlled("file_size", fileGuard(target) + "wc -c < ${quote(target)}", files = listOf(rel))
        val size = sizeResult.stdout.trim().toIntOrNull() ?: return null
        if (!sizeResult.isSuccess || size !in 0..maxBytes) return null
        val bytes = java.io.ByteArrayOutputStream(size)
        var offset = 0
        while (offset < size) {
            val count = minOf(CHUNK_BYTES, size - offset)
            val chunk = controlled("file_read", fileGuard(target) + "dd if=${quote(target)} bs=1 skip=$offset count=$count status=none | base64", files = listOf(rel))
            if (!chunk.isSuccess || chunk.stdout.length > 6_000) return null
            val decoded = decodeTransport(chunk.stdout) ?: return null
            if (decoded.size != count) return null
            bytes.write(decoded); offset += count
        }
        val content = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString() } catch (_: java.nio.charset.CharacterCodingException) { return null }
        if ('\u0000' in content || developmentSha256(content) != hash || hashTarget(target, rel) != hash) return null
        return SourceSnapshot(rel, hash, content)
    }

    private suspend fun hashTarget(target: String, path: String): String? {
        val result = controlled("file_sha256", fileGuard(target) + "sha256sum -- ${quote(target)}", files = listOf(path))
        val hash = result.stdout.substringBefore(' ').trim()
        return hash.takeIf { result.isSuccess && it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }

    private suspend fun backupFile(target: String, path: String, id: String, expectedHash: String): String? {
        val directory = "$workspaceRoot/.nyx-checkpoints"
        val backup = "$directory/$id-${UUID.randomUUID()}.bak"
        val command = fileGuard(target) + "if [ ! -e ${quote(directory)} ] && [ ! -L ${quote(directory)} ]; then mkdir -- ${quote(directory)} || exit 73; fi; " +
            "[ ! -L ${quote(directory)} ] && [ \"\$(realpath -e -- ${quote(directory)})\" = ${quote(directory)} ] || exit 73; " +
            "(umask 077; cp -- ${quote(target)} ${quote(backup)}) && sha256sum -- ${quote(backup)}"
        val result = controlled("file_backup", command, mutating = true, files = listOf(path, backup))
        return backup.takeIf { result.isSuccess && result.stdout.substringBefore(' ').trim() == expectedHash }
    }

    private suspend fun atomicWrite(target: String, path: String, expected: String, content: String): Boolean {
        val parent = target.substringBeforeLast('/')
        val prepared = controlled("file_prepare", fileGuard(target, true) + "(umask 077; mktemp ${quote("$parent/.nyx-write-XXXXXXXX")})", mutating = true, files = listOf(path))
        val temporary = prepared.stdout.trim()
        if (!prepared.isSuccess || !temporary.startsWith("$parent/.nyx-write-") || temporary.substringAfterLast('/').any { !it.isLetterOrDigit() && it !in ".-" }) return false
        val bytes = content.toByteArray(Charsets.UTF_8)
        for (chunk in bytes.asList().chunked(3_000)) {
            val encoded = Base64.getEncoder().encodeToString(chunk.toByteArray()) // Each encoded payload is <=4000 characters.
            val result = controlled("file_write_chunk", fileGuard(temporary) + "printf '%s' ${quote(encoded)} | base64 -d >> ${quote(temporary)}", mutating = true, files = listOf(path))
            if (!result.isSuccess) return false
        }
        val hash = developmentSha256(content)
        val staleCheck = if (expected == DevelopmentPaths.MISSING) "[ ! -e ${quote(target)} ] && [ ! -L ${quote(target)} ]" else "[ \"\$(sha256sum -- ${quote(target)} | cut -d ' ' -f 1)\" = ${quote(expected)} ]"
        val command = fileGuard(target, true) + fileGuard(temporary) +
            "[ \"\$(sha256sum -- ${quote(temporary)} | cut -d ' ' -f 1)\" = ${quote(hash)} ] || exit 74; " +
            "$staleCheck || exit 75; " +
            (if (expected == DevelopmentPaths.MISSING) "" else "chmod --reference=${quote(target)} -- ${quote(temporary)} || exit 76; ") +
            "mv -f -- ${quote(temporary)} ${quote(target)} && sha256sum -- ${quote(target)}"
        val committed = controlled("file_commit", command, mutating = true, files = listOf(path))
        return committed.isSuccess && committed.stdout.substringBefore(' ').trim() == hash
    }

    private fun partial(id: String, files: List<CheckpointFile>, reason: String, uncertain: Boolean = false): PatchApplication {
        val checkpoint = files.takeIf { it.isNotEmpty() }?.let { DevelopmentCheckpoint(id, it.toList()) }
        checkpoint?.let { knownCheckpoints[id] = it }
        return PatchApplication(false, checkpoint, reason, uncertain || files.isNotEmpty())
    }

    private fun outputGuard(file: String? = null): String {
        val directory = "$workspaceRoot/build"
        // Check immediately before the compiler opens an existing output. Trusted project code
        // and same-UID races remain outside this app-level guard; this is not an OS sandbox.
        val target = file?.let { "$directory/$it" }
        return "if [ ! -e ${quote(directory)} ] && [ ! -L ${quote(directory)} ]; then mkdir -- ${quote(directory)} || exit 73; fi; " +
            "[ ! -L ${quote(directory)} ] && [ -d ${quote(directory)} ] && [ \"\$(realpath -e -- ${quote(directory)})\" = ${quote(directory)} ] || exit 73; " +
            if (target == null) "" else "if [ -e ${quote(target)} ] || [ -L ${quote(target)} ]; then " +
                "[ ! -L ${quote(target)} ] && [ -f ${quote(target)} ] && [ \"\$(realpath -e -- ${quote(target)})\" = ${quote(target)} ] && " +
                "[ \"\$(stat -c %h -- ${quote(target)})\" = 1 ] || exit 73; fi; "
    }

    /** Comments and runtime Android mentions do not turn a JVM Gradle project into an SDK build. */
    private fun hasAndroidPlugin(content: String): Boolean {
        val code = Regex("(?s)/\\*.*?\\*/|(?m)//[^\\r\\n]*").replace(content, "")
        return Regex("(?:id\\s*\\(?\\s*[\"']com\\.android\\.(?:application|library|test|dynamic-feature)[\"']|apply\\s+plugin\\s*:\\s*[\"']com\\.android\\.(?:application|library|test|dynamic-feature)[\"']|alias\\s*\\(\\s*libs\\.plugins\\.android\\.(?:application|library)\\s*\\))").containsMatchIn(code)
    }

    private fun rootGuard(): String = WorkspacePathGuard.directory(workspaceRoot)
    private fun fileGuard(target: String, allowMissing: Boolean = false): String = WorkspacePathGuard.regularFile(workspaceRoot, target, allowMissing)
    private fun relative(path: String): String? {
        val rel = if (path.startsWith("$workspaceRoot/")) path.removePrefix("$workspaceRoot/") else path
        return rel.takeIf { WorkspaceSourcePaths.isSafeRelative(it) }
    }
    private suspend fun controlled(tool: String, command: String, timeout: Long = 45, mutating: Boolean = false, files: List<String> = emptyList(), hardTimeout: Boolean = useTimeoutBinary, buildOperation: Boolean = false,
                                   identity: TerminalCommandIdentity? = null): TerminalCommandResult =
        workspaceLease(!mutating) { controlledLocked(tool, command, minOf(timeout.coerceAtLeast(1), if (buildOperation) buildCeiling else terminalCeiling), mutating, files, hardTimeout, identity) }

    private suspend fun controlledLocked(tool: String, command: String, timeout: Long, mutating: Boolean, files: List<String>, hardTimeout: Boolean,
                                         identity: TerminalCommandIdentity?): TerminalCommandResult {
        val operationId = UUID.randomUUID().toString()
        // Bound the fully wrapped IPC argv too; do not depend on CommandPolicy regex for confinement.
        if (!workspaceApproved) return TerminalCommandResult(operationId, TerminalResultKind.DENIED, message = "Approved trusted workspace required for terminal IPC").also {
            record(tool, it, mutating, files, System.currentTimeMillis())
        }
        val request = ControlledShell.request(command, environment, workspaceRoot, timeout, hardTimeout, guestAlias)
        if (command.length > 7_500 || TerminalRequestValidator.validate(request).isNotEmpty()) {
            return TerminalCommandResult(operationId, TerminalResultKind.INVALID_REQUEST, message = "Controlled command exceeds IPC payload bound").also {
                record(tool, it, mutating, files, System.currentTimeMillis())
            }
        }
        val start = System.currentTimeMillis()
        val dispatchIdentity = identity ?: TerminalCommandIdentity(tool, ControlledShell.executable(environment, "bash"),
            if (tool.startsWith("project_run_")) listOf("--noprofile", "--norc", "-c", command) else emptyList(),
            workspaceRoot, if (mutating) "trusted_project" else "structured_readonly", TerminalCommandIdentity.digest(command))
        val result = try { withContext(TerminalCommandDispatchScope(dispatchIdentity, operationId)) {
            executor.run(command, environment, workspaceRoot, timeout, hardTimeout).copy(requestId = operationId)
        } }
        catch (cancel: CancellationException) {
            record(tool, TerminalCommandResult(operationId, TerminalResultKind.CANCELLED, message = "IPC wait cancelled; underlying process status is uncertain"), mutating, files, start)
            throw cancel
        }
        catch (error: Exception) { TerminalCommandResult(operationId, TerminalResultKind.DISPATCH_ERROR, message = error.message?.take(300)) }
        record(tool, result, mutating, files, start)
        return result
    }
    private fun record(tool: String, result: TerminalCommandResult, mutating: Boolean, files: List<String>, start: Long) {
        evidence += ToolExecutionEvidence(tool, result.exitCode, result.stdout.take(32_768),
            result.stderr.take(32_000) + result.message?.let { "\n$it" }.orEmpty(), files, mutating, start, System.currentTimeMillis(), taskContext?.objectiveId, taskContext?.taskId)
    }
    private fun denied(tool: String, reason: String): String {
        record(tool, TerminalCommandResult(null, TerminalResultKind.DENIED, message = reason), false, emptyList(), System.currentTimeMillis())
        return "Tool denied: $reason"
    }
    private fun deniedCommand(kind: ProjectRunKind, reason: String): DevelopmentCommand {
        denied("project_run", reason)
        return DevelopmentCommand(kind, "[denied]", null, stderr = reason, infrastructureOk = false)
    }
    private fun discoveryJson(value: DevelopmentDiscovery): String = JSONObject().put("path", value.workspace).put("projectType", value.projectType)
        .put("markers", JSONArray(value.markers)).put("buildCommand", value.commands[ProjectRunKind.BUILD])
        .put("testCommand", value.commands[ProjectRunKind.TEST]).put("ready", value.ready).put("canCreateProject", value.canCreateProject)
        .put("missingTools", JSONArray(value.missingTools)).put("reason", value.reason).toString()
    private fun decodeTransport(output: String): ByteArray? {
        if (output.any { it !in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\r\n" }) return null
        val encoded = output.replace("\r", "").replace("\n", "")
        return try { Base64.getDecoder().decode(encoded).takeIf { Base64.getEncoder().encodeToString(it) == encoded } }
        catch (_: IllegalArgumentException) { null }
    }
    private fun quote(value: String) = ShellCommandBuilder.quote(value)
    private fun schema(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)))
    private fun obj(vararg fields: Pair<String, String>) = JSONObject().apply { fields.forEach { (key, type) -> put(key, JSONObject().put("type", type)) } }
    companion object {
        const val MAX_FILE_BYTES = 128_000
        private const val CHUNK_BYTES = 4_096
        // Termux returns results through a Binder Bundle. Twelve large files produced a base64
        // payload close to the transaction limit and could fail with a truncated callback. Four
        // files and a 96 KB payload keep the encoded result well below that bound.
        private const val BATCH_FILES_PER_COMMAND = 4
        private const val MAX_BATCH_TOTAL_BYTES = 96_000
    }
}

/** In-process multi-IPC transactions; durable bridge reservations still own uncertain processes. */
internal object TerminalToolWorkspaceRegistry {
    private val registry = TerminalOperationRegistry()
    private class Lease(val context: TerminalOperationContext) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }
    suspend fun <T> withLease(context: TerminalOperationContext, block: suspend () -> T): T {
        val held = currentCoroutineContext()[Lease]?.context
        if (held != null && held.namespace == context.namespace && held.workspace == context.workspace) {
            check(!held.readOnly || context.readOnly) { "Cannot upgrade a workspace read lease" }
            return block()
        }
        registry.enqueue(context)
        try {
            while (!registry.tryStart(context.operationId, TerminalOperationRegistry.MAX_CONTEXTS)) delay(10)
            return withContext(Lease(context)) { block() }
        } finally {
            // This releases only the transaction lease, not the bridge's durable process ownership.
            registry.finish(context.operationId, "TRANSACTION_FINISHED", null, "", "", null, uncertain = false)
        }
    }
}

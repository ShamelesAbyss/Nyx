package com.nyx.ai.terminal

import android.util.Base64
import com.nyx.ai.agent.AgentToolProvider
import com.nyx.ai.agent.FileProbe
import com.nyx.ai.agent.ToolExecutionEvidence
import com.nyx.ai.network.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Terminal-backed agent tools. Every operation is confined to [workspaceRoot] on the Termux side and
 * passes through [CommandPolicy]; destructive and privileged commands remain blocked even when the
 * user has enabled autonomous terminal use.
 */
class TerminalAgentTools(
    private val bridge: TerminalBridge,
    private val workspaceRoot: String,
    private val environment: TerminalEnvironment = TerminalEnvironment.TERMUX_HOST,
    private val timeoutSeconds: Long = 180,
    private val useTimeoutBinary: Boolean = true,
) : AgentToolProvider, FileProbe {

    override val names: Set<String> = setOf(
        "terminal_run", "file_read", "file_write", "file_list", "file_search",
        "project_detect", "build_run", "test_run",
    )

    private val evidence = Collections.synchronizedList(mutableListOf<ToolExecutionEvidence>())

    override fun schemas(): JSONArray = JSONArray().apply {
        put(schema("terminal_run", "Run a shell command in the authorized Termux workspace. Destructive and privileged commands are blocked.", obj("command" to "string", "timeout_seconds" to "number"), listOf("command")))
        put(schema("file_read", "Read a UTF-8 text file inside the authorized workspace.", obj("path" to "string"), listOf("path")))
        put(schema("file_write", "Create or replace a UTF-8 text file inside the authorized workspace.", obj("path" to "string", "content" to "string"), listOf("path", "content")))
        put(schema("file_list", "List files under a directory in the authorized workspace.", obj("path" to "string"), listOf("path")))
        put(schema("file_search", "Search file contents under the workspace with grep.", obj("query" to "string", "path" to "string"), listOf("query")))
        put(schema("project_detect", "Detect the project type and build/test commands in a workspace directory.", obj("path" to "string"), emptyList()))
        put(schema("build_run", "Run the detected or supplied build command and return its exit code and output.", obj("path" to "string", "command" to "string"), emptyList()))
        put(schema("test_run", "Run the detected or supplied test command and return its exit code and output.", obj("path" to "string", "command" to "string"), emptyList()))
    }

    override fun drainEvidence(): List<ToolExecutionEvidence> {
        synchronized(evidence) {
            val copy = evidence.toList()
            evidence.clear()
            return copy
        }
    }

    override suspend fun execute(call: ToolCall): String = when (call.name) {
        "terminal_run" -> runCommand(call.arguments.optString("command"), call.arguments.optString("path"), call.arguments.optLong("timeout_seconds", timeoutSeconds))
        "file_read" -> readFile(call.arguments.optString("path"))
        "file_write" -> writeFile(call.arguments.optString("path"), call.arguments.optString("content"))
        "file_list" -> listFiles(call.arguments.optString("path"))
        "file_search" -> searchFiles(call.arguments.optString("query"), call.arguments.optString("path"))
        "project_detect" -> detectProject(call.arguments.optString("path"))
        "build_run" -> build(call.arguments.optString("path"), call.arguments.optString("command"))
        "test_run" -> test(call.arguments.optString("path"), call.arguments.optString("command"))
        else -> "Tool denied: '${call.name}' is not a terminal tool."
    }

    override suspend fun exists(path: String): Boolean {
        val target = resolve(path) ?: return false
        val result = bridge.runShell("test -e ${quote(target)} && echo NYX_EXISTS", environment, workspaceRoot, 30, useTimeoutBinary, approved = true)
        return result.stdout.contains("NYX_EXISTS")
    }

    override suspend fun read(path: String, maxChars: Int): String? {
        val target = resolve(path) ?: return null
        val result = bridge.runShell("cat -- ${quote(target)}", environment, workspaceRoot, 60, useTimeoutBinary, approved = true)
        if (result.kind != TerminalResultKind.SUCCEEDED) return null
        return result.stdout.take(maxChars)
    }

    override suspend fun sha256(path: String): String? {
        val target = resolve(path) ?: return null
        val result = bridge.runShell("sha256sum -- ${quote(target)}", environment, workspaceRoot, 60, useTimeoutBinary, approved = true)
        return result.stdout.trim().substringBefore(' ').takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
    }

    private suspend fun runCommand(command: String, path: String?, timeout: Long): String {
        if (command.isBlank()) return "Tool error: command is empty."
        val working = resolveDirectory(path) ?: return "Tool error: path is outside the authorized workspace."
        val assessment = CommandPolicy.assess(command)
        if (assessment.blocked) {
            record("terminal_run", null, "", assessment.reasons.joinToString("; "), mutating = true)
            return "Tool denied by policy: ${assessment.reasons.joinToString("; ").ifBlank { "unsafe command" }}"
        }
        val result = bridge.runShell(command, environment, working, timeout.coerceIn(1, 600), useTimeoutBinary, approved = true)
        record("terminal_run", result.exitCode, result.stdout, result.stderr, mutating = assessment.requiresConfirmation)
        return format(result)
    }

    private suspend fun readFile(path: String): String {
        val target = resolve(path) ?: return "Tool error: path is outside the authorized workspace."
        val result = bridge.runShell("cat -- ${quote(target)}", environment, workspaceRoot, 60, useTimeoutBinary, approved = true)
        record("file_read", result.exitCode, result.stdout, result.stderr, mutating = false, files = listOf(target))
        return if (result.kind == TerminalResultKind.SUCCEEDED) result.stdout.take(48_000) else format(result)
    }

    private suspend fun writeFile(path: String, content: String): String {
        val target = resolve(path) ?: return "Tool error: path is outside the authorized workspace."
        if (content.length > 256_000) return "Tool error: content exceeds 256000 characters."
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val parent = target.substringBeforeLast('/', workspaceRoot)
        val command = "mkdir -p ${quote(parent)} && printf '%s' '$encoded' | base64 -d > ${quote(target)} && wc -c < ${quote(target)}"
        val result = bridge.runShell(command, environment, workspaceRoot, 120, useTimeoutBinary, approved = true)
        record("file_write", result.exitCode, result.stdout, result.stderr, mutating = true, files = listOf(target))
        return if (result.kind == TerminalResultKind.SUCCEEDED) "Wrote ${result.stdout.trim()} bytes to $target" else format(result)
    }

    private suspend fun listFiles(path: String): String {
        val directory = resolveDirectory(path) ?: return "Tool error: path is outside the authorized workspace."
        val result = bridge.runShell("find ${quote(directory)} -maxdepth 2 -type f -printf '%p\n' 2>/dev/null | head -200", environment, workspaceRoot, 60, useTimeoutBinary, approved = true)
        record("file_list", result.exitCode, result.stdout, result.stderr, mutating = false)
        return format(result)
    }

    private suspend fun searchFiles(query: String, path: String?): String {
        if (query.isBlank() || query.length > 200) return "Tool error: search text must be 1-200 characters."
        val directory = resolveDirectory(path) ?: return "Tool error: path is outside the authorized workspace."
        val result = bridge.runShell("grep -rIl -- ${quote(query)} ${quote(directory)} 2>/dev/null | head -50", environment, workspaceRoot, 90, useTimeoutBinary, approved = true)
        record("file_search", result.exitCode, result.stdout, result.stderr, mutating = false)
        return format(result)
    }

    private suspend fun detectProject(path: String?): String {
        val directory = resolveDirectory(path) ?: return "Tool error: path is outside the authorized workspace."
        val script = buildString {
            append("cd -- ${quote(directory)} || exit 1; ")
            append("for f in settings.gradle.kts settings.gradle build.gradle.kts build.gradle gradlew package.json pyproject.toml setup.py Cargo.toml go.mod Makefile CMakeLists.txt; do ")
            append("if [ -e \"\$f\" ]; then echo \"FOUND:\$f\"; fi; done; ")
            append("if [ -d .git ]; then echo 'FOUND:.git'; fi")
        }
        val result = bridge.runShell(script, environment, workspaceRoot, 45, useTimeoutBinary, approved = true)
        record("project_detect", result.exitCode, result.stdout, result.stderr, mutating = false)
        if (result.kind != TerminalResultKind.SUCCEEDED) return format(result)
        val found = result.stdout.lineSequence().filter { it.startsWith("FOUND:") }.map { it.removePrefix("FOUND:") }.toList()
        val kind = when {
            found.any { it.contains("gradle") } -> "android-or-jvm-gradle"
            "package.json" in found -> "node"
            "pyproject.toml" in found || "setup.py" in found -> "python"
            "Cargo.toml" in found -> "rust"
            "go.mod" in found -> "go"
            "CMakeLists.txt" in found || "Makefile" in found -> "native-make"
            else -> "unknown"
        }
        val build = when (kind) {
            "android-or-jvm-gradle" -> if ("gradlew" in found) "./gradlew assembleDebug" else "gradle assembleDebug"
            "node" -> "npm run build"
            "python" -> "python3 -m compileall ."
            "rust" -> "cargo build"
            "go" -> "go build ./..."
            "native-make" -> "make"
            else -> null
        }
        val test = when (kind) {
            "android-or-jvm-gradle" -> if ("gradlew" in found) "./gradlew test" else "gradle test"
            "node" -> "npm test"
            "python" -> "python3 -m pytest -q"
            "rust" -> "cargo test"
            "go" -> "go test ./..."
            "native-make" -> "make test"
            else -> null
        }
        return JSONObject()
            .put("path", directory)
            .put("projectType", kind)
            .put("markers", JSONArray(found))
            .put("buildCommand", build)
            .put("testCommand", test)
            .toString(2)
    }

    private suspend fun build(path: String?, command: String?): String {
        val directory = resolveDirectory(path) ?: return "Tool error: path is outside the authorized workspace."
        val actual = command?.takeIf { it.isNotBlank() } ?: defaultCommand(directory, build = true)
            ?: return "Tool error: no build command could be detected; supply one explicitly."
        return runProjectCommand("build_run", actual, directory)
    }

    private suspend fun test(path: String?, command: String?): String {
        val directory = resolveDirectory(path) ?: return "Tool error: path is outside the authorized workspace."
        val actual = command?.takeIf { it.isNotBlank() } ?: defaultCommand(directory, build = false)
            ?: return "Tool error: no test command could be detected; supply one explicitly."
        return runProjectCommand("test_run", actual, directory)
    }

    private suspend fun defaultCommand(directory: String, build: Boolean): String? {
        val probe = bridge.runShell("cd -- ${quote(directory)} && ls -1", environment, workspaceRoot, 30, useTimeoutBinary, approved = true)
        val entries = probe.stdout.lineSequence().map { it.trim() }.toSet()
        return when {
            "gradlew" in entries -> if (build) "./gradlew assembleDebug" else "./gradlew test"
            "build.gradle.kts" in entries || "build.gradle" in entries -> if (build) "gradle assembleDebug" else "gradle test"
            "package.json" in entries -> if (build) "npm run build" else "npm test"
            "Cargo.toml" in entries -> if (build) "cargo build" else "cargo test"
            "go.mod" in entries -> if (build) "go build ./..." else "go test ./..."
            "pyproject.toml" in entries || "setup.py" in entries -> if (build) "python3 -m compileall ." else "python3 -m pytest -q"
            "Makefile" in entries -> if (build) "make" else "make test"
            else -> null
        }
    }

    private suspend fun runProjectCommand(tool: String, command: String, directory: String): String {
        val assessment = CommandPolicy.assess(command)
        if (assessment.blocked) return "Tool denied by policy: ${assessment.reasons.joinToString("; ")}"
        val result = bridge.runShell(command, environment, directory, timeoutSeconds.coerceIn(30, 900), useTimeoutBinary, approved = true)
        record(tool, result.exitCode, result.stdout, result.stderr, mutating = true)
        return format(result)
    }

    private fun format(result: TerminalCommandResult): String = buildString {
        append("exit=")
        append(result.exitCode?.toString() ?: "none")
        append(" (").append(result.kind.name.lowercase()).append(")\n")
        if (result.stdout.isNotBlank()) {
            append("stdout:\n")
            append(result.stdout.take(12_000))
            if (result.stdout.length > 12_000) append("\n[stdout truncated at 12000 characters]")
        }
        if (result.stderr.isNotBlank()) {
            append("\nstderr:\n")
            append(result.stderr.take(4_000))
            if (result.stderr.length > 4_000) append("\n[stderr truncated at 4000 characters]")
        }
        if (result.message != null && result.kind != TerminalResultKind.SUCCEEDED) {
            append("\nmessage: ").append(result.message.take(400))
        }
    }

    private fun record(tool: String, exitCode: Int?, stdout: String, stderr: String, mutating: Boolean, files: List<String> = emptyList()) {
        val now = System.currentTimeMillis()
        evidence += ToolExecutionEvidence(tool, exitCode, stdout.take(8_000), stderr.take(2_000), files, mutating, now, now)
    }

    /** Resolves a path inside the workspace; rejects traversal and absolute paths outside it. */
    private fun resolve(raw: String): String? {
        if (raw.isBlank()) return null
        if ('\u0000' in raw) return null
        val normalizedRoot = workspaceRoot.trimEnd('/')
        val candidate = when {
            raw.startsWith("/") -> raw
            else -> "$normalizedRoot/${raw.removePrefix("./")}"
        }
        val segments = candidate.split('/').toMutableList()
        val stack = ArrayDeque<String>()
        for (segment in segments) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        val resolved = "/" + stack.joinToString("/")
        return resolved.takeIf { it == normalizedRoot || it.startsWith("$normalizedRoot/") }
    }

    private fun resolveDirectory(raw: String?): String? {
        if (raw.isNullOrBlank()) return workspaceRoot
        return resolve(raw)
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun schema(name: String, description: String, properties: JSONObject, required: List<String>) = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)))
        )

    private fun obj(vararg fields: Pair<String, String>) = JSONObject().apply {
        fields.forEach { (key, type) ->
            put(key, JSONObject().put("type", type))
        }
    }
}

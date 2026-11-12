package com.nyx.ai.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable gateway catalogue and policy enforcement point.
 *
 * Everything the model can reach goes through [callTool]: the server must exist, be explicitly
 * enabled, satisfy its credential requirement, stay inside its tool allowlist and per-run call
 * budget, and finish inside its timeout. Every attempt is appended to `gateway-logs.jsonl`, capped at
 * [GatewayLimits.LOG_CAP] lines, with bounded and redacted argument/result summaries.
 *
 * Configs live in `gateways.json` under [root]. Credentials never do.
 */
class GatewayRepository(
    private val root: File,
    private val tokenProvider: GatewayTokenProvider,
    private val githubBaseUrl: String = GitHubGateway.DEFAULT_BASE_URL,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val configs = mutableListOf<GatewayServerConfig>()
    private val executors = ConcurrentHashMap<String, CachedExecutor>()
    private val runCalls = mutableMapOf<String, Int>()

    @Volatile private var closed = false

    private val configFile = File(root, CONFIG_FILE_NAME)
    private val logFile = File(root, LOG_FILE_NAME)

    init {
        if (!root.exists() && !root.mkdirs()) throw GatewayException("Cannot create gateway directory.")
        reload()
    }

    // ------------------------------------------------------------------ configuration

    /** Re-reads `gateways.json`; malformed entries are skipped rather than trusted. */
    fun reload(): List<GatewayServerConfig> = synchronized(lock) {
        configs.clear()
        if (configFile.exists()) {
            val parsed = runCatching { JSONObject(configFile.readText()) }.getOrNull()
            val array = parsed?.optJSONArray("servers") ?: runCatching { JSONArray(configFile.readText()) }.getOrNull()
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val config = GatewayServerConfig.fromJson(item) ?: continue
                    if (config.validate().isEmpty() && configs.none { it.id == config.id }) configs.add(config)
                }
            }
        }
        // A manual reload must not leave an executor bound to a definition that just changed.
        val stale = executors.values.toList()
        executors.clear()
        stale.forEach { cached -> runCatching { cached.executor.close() } }
        configs.sortedBy { it.id }
    }

    fun list(): List<GatewayServerConfig> = synchronized(lock) { configs.sortedBy { it.id } }

    fun get(id: String): GatewayServerConfig? = synchronized(lock) { configs.firstOrNull { it.id == id } }

    /** Creates or replaces a gateway definition after structural validation. */
    fun upsert(config: GatewayServerConfig): GatewayServerConfig {
        val issues = config.validate()
        if (issues.isNotEmpty()) throw GatewayException("Invalid gateway config: ${issues.joinToString("; ")}")
        if (config.hasEmbeddedCredentials()) throw GatewayException("Gateway endpoints must not embed credentials.")
        if (config.kind == GatewayKind.MCP_HTTP) McpEndpointPolicy.validate(config.endpoint)
        synchronized(lock) {
            val index = configs.indexOfFirst { it.id == config.id }
            if (index >= 0) configs[index] = config else configs.add(config)
            persistLocked()
            invalidateLocked(config.id)
        }
        return config
    }

    fun setEnabled(id: String, enabled: Boolean): GatewayServerConfig {
        synchronized(lock) {
            val index = configs.indexOfFirst { it.id == id }
            if (index < 0) throw GatewayException("Gateway '$id' is not configured.")
            val updated = configs[index].copy(enabled = enabled)
            configs[index] = updated
            persistLocked()
            if (!enabled) invalidateLocked(id)
            return updated
        }
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val removed = configs.removeAll { it.id == id }
            if (removed) {
                persistLocked()
                invalidateLocked(id)
            }
            return removed
        }
    }

    // ------------------------------------------------------------------ tools

    /**
     * Tools this gateway may actually call. Disabled servers, GitHub servers without a resolvable
     * credential, and tools outside the allowlist are never returned; no network call is made when
     * the gateway is not runnable.
     */
    suspend fun listTools(id: String): List<GatewayTool> {
        if (closed) return emptyList()
        val config = get(id) ?: throw GatewayException("Gateway '$id' is not configured.")
        if (!config.enabled) return emptyList()
        if (!credentialReady(config)) return emptyList()
        val tools = try {
            withTimeout(config.timeoutSeconds * 1_000L) { executorFor(config).listTools() }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            return emptyList()
        } catch (failure: Exception) {
            return emptyList()
        }
        return if (config.allowedTools.isEmpty()) tools else tools.filter { it.name in config.allowedTools }
    }

    suspend fun health(id: String): GatewayHealth {
        if (closed) return GatewayHealth(GatewayHealthState.MISCONFIGURED, "Gateway repository is closed.", 0)
        val config = get(id) ?: return GatewayHealth(GatewayHealthState.MISCONFIGURED, "Gateway '$id' is not configured.", 0)
        if (!config.enabled) return GatewayHealth.disabled()
        if (config.kind == GatewayKind.GITHUB && config.credentialRef.isNullOrBlank()) {
            return GatewayHealth(GatewayHealthState.MISCONFIGURED, "GitHub credential reference is missing.", 0)
        }
        if (!credentialReady(config)) {
            return GatewayHealth(GatewayHealthState.MISCONFIGURED, "Credential could not be resolved; nothing was sent.", 0)
        }
        return try {
            val health = withTimeout(config.timeoutSeconds * 1_000L) { executorFor(config).health() }
            health.copy(checkedAt = clock())
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            GatewayHealth(GatewayHealthState.UNREACHABLE, "Health check timed out after ${config.timeoutSeconds}s.", 0, clock())
        } catch (failure: Exception) {
            GatewayHealth(
                GatewayHealthState.UNREACHABLE,
                GatewayLimits.redact(failure.message ?: "Gateway unreachable.").take(300),
                0,
                clock(),
            )
        }
    }

    /**
     * Calls one tool with every policy check applied. Failures are returned as results, not thrown,
     * except when the surrounding coroutine is cancelled.
     */
    suspend fun callTool(
        id: String,
        toolName: String,
        argumentsJson: String = "{}",
        runId: String = DEFAULT_RUN_ID,
    ): GatewayToolResult {
        if (closed) return deny(id, toolName, "Gateway repository is closed.")
        val config = get(id) ?: return deny(id, toolName, "Gateway '$id' is not configured.")
        if (!config.enabled) return deny(id, toolName, "Gateway '$id' is disabled.")
        if (argumentsJson.length > GatewayLimits.MAX_ARGUMENT_CHARS) {
            return deny(id, toolName, "Arguments exceed ${GatewayLimits.MAX_ARGUMENT_CHARS} characters.")
        }
        if (config.allowedTools.isNotEmpty() && toolName !in config.allowedTools) {
            return deny(id, toolName, "Tool '$toolName' is not allowed by gateway '$id'.")
        }
        if (!credentialReady(config)) {
            val reason = if (config.kind == GatewayKind.GITHUB) {
                "GitHub gateway '$id' has no usable credential; refusing to publish."
            } else {
                "Gateway '$id' has a configured credential that could not be resolved; nothing was sent."
            }
            return deny(id, toolName, reason)
        }
        val budgetKey = runKey(runId, config.id)
        val overBudget = synchronized(lock) {
            val used = runCalls[budgetKey] ?: 0
            if (used >= config.maxCallsPerRun) {
                true
            } else {
                runCalls[budgetKey] = used + 1
                false
            }
        }
        if (overBudget) {
            return deny(id, toolName, "Gateway '$id' exhausted its ${config.maxCallsPerRun} calls for run '$runId'.")
        }

        val started = System.nanoTime()
        val result = try {
            withTimeout(config.timeoutSeconds * 1_000L) {
                executorFor(config).callTool(toolName, argumentsJson)
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            GatewayToolResult.failure("Tool call timed out after ${config.timeoutSeconds}s.")
        } catch (failure: Exception) {
            GatewayToolResult.failure(GatewayLimits.redact(failure.message ?: "Tool call failed.").take(500))
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val boundedContent = GatewayLimits.truncate(result.content, config.maxResponseChars)
        val boundedRaw = GatewayLimits.truncate(result.boundedRaw, config.maxResponseChars)
        val bounded = result.copy(
            content = boundedContent,
            boundedRaw = boundedRaw,
            latencyMs = if (result.latencyMs > 0) result.latencyMs else latencyMs,
        )
        record(config, toolName, argumentsJson, bounded)
        return bounded
    }

    /** Starts (or resets) every per-server call budget in a run scope. */
    fun beginRun(runId: String) {
        synchronized(lock) {
            runCalls.keys.filter { it.startsWith("$runId::") }.forEach { runCalls.remove(it) }
        }
    }

    fun endRun(runId: String) {
        synchronized(lock) {
            runCalls.keys.filter { it.startsWith("$runId::") }.forEach { runCalls.remove(it) }
        }
    }

    /** Calls consumed by one gateway in a run. */
    fun callsUsed(runId: String, serverId: String): Int = synchronized(lock) { runCalls[runKey(runId, serverId)] ?: 0 }

    /** Calls consumed by every gateway in a run. */
    fun callsUsed(runId: String): Int = synchronized(lock) {
        runCalls.entries.filter { it.key.startsWith("$runId::") }.sumOf { it.value }
    }

    private fun runKey(runId: String, serverId: String): String = "$runId::$serverId"

    // ------------------------------------------------------------------ logs

    fun logs(limit: Int = 100): List<GatewayLogEntry> {
        if (!logFile.exists()) return emptyList()
        return synchronized(lock) {
            logFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { GatewayLogEntry.fromJson(JSONObject(line)) }.getOrNull() }
                .takeLast(limit.coerceIn(1, GatewayLimits.LOG_CAP))
        }
    }

    fun logCount(): Int = if (!logFile.exists()) 0 else synchronized(lock) {
        logFile.readLines().count { it.isNotBlank() }
    }

    fun close() {
        closed = true
        val snapshot = executors.values.toList()
        executors.clear()
        snapshot.forEach { cached -> runCatching { cached.executor.close() } }
    }

    // ------------------------------------------------------------------ internals

    /**
     * True only when the gateway may transmit anything at all. GitHub always needs a resolvable
     * credential; MCP needs one only when the operator configured a reference.
     */
    private suspend fun credentialReady(config: GatewayServerConfig): Boolean {
        val reference = config.credentialRef?.takeIf { it.isNotBlank() }
        if (reference == null) return config.kind != GatewayKind.GITHUB
        val token = runCatching { tokenProvider(reference) }.getOrNull()?.trim()
        return !token.isNullOrEmpty()
    }

    private fun executorFor(config: GatewayServerConfig): GatewayToolExecutor {
        val fingerprint = config.hashCode()
        val existing = executors[config.id]
        if (existing != null && existing.fingerprint == fingerprint) return existing.executor
        existing?.let { runCatching { it.executor.close() } }
        val created = when (config.kind) {
            GatewayKind.MCP_HTTP -> McpClient(config, tokenProvider)
            GatewayKind.GITHUB -> GitHubGateway(config, tokenProvider, githubBaseUrl)
        }
        executors[config.id] = CachedExecutor(fingerprint, created)
        return created
    }

    private fun invalidateLocked(id: String) {
        executors.remove(id)?.let { runCatching { it.executor.close() } }
    }

    private fun persistLocked() {
        val payload = JSONObject()
            .put("version", 1)
            .put("servers", JSONArray(configs.map { it.toJson() }))
        writeAtomically(configFile, payload.toString(2) + "\n")
    }

    private fun deny(id: String, toolName: String, message: String): GatewayToolResult {
        recordDenial(id, toolName, message)
        return GatewayToolResult.failure(message)
    }

    private fun record(config: GatewayServerConfig, toolName: String, argumentsJson: String, result: GatewayToolResult) {
        val entry = GatewayLogEntry(
            at = clock(),
            serverId = config.id,
            tool = toolName,
            success = result.success && !result.isError,
            durationMs = result.latencyMs,
            argsSummary = summariseArguments(argumentsJson),
            resultSummary = summarise(result.content),
            error = if (result.isError) GatewayLimits.hardCap(result.content, GatewayLimits.MAX_LOG_RESULT_CHARS) else null,
        )
        append(entry)
    }

    private fun recordDenial(id: String, toolName: String, message: String) {
        append(
            GatewayLogEntry(
                at = clock(),
                serverId = id,
                tool = toolName,
                success = false,
                durationMs = 0,
                argsSummary = "",
                resultSummary = "",
                error = GatewayLimits.hardCap(GatewayLimits.redact(message), GatewayLimits.MAX_LOG_RESULT_CHARS),
            ),
        )
    }

    private fun append(entry: GatewayLogEntry) {
        synchronized(lock) {
            val existing = if (logFile.exists()) {
                logFile.readLines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            val updated = (existing + entry.toJson().toString()).takeLast(GatewayLimits.LOG_CAP)
            writeAtomically(logFile, updated.joinToString("\n", postfix = "\n"))
        }
    }

    private fun summariseArguments(argumentsJson: String): String {
        if (argumentsJson.isBlank()) return ""
        val redacted = GatewayLimits.redactJson(argumentsJson)
        return GatewayLimits.hardCap(redacted.replace(Regex("\\s+"), " ").trim(), GatewayLimits.MAX_LOG_ARGS_CHARS)
    }

    private fun summarise(content: String): String {
        if (content.isBlank()) return ""
        return GatewayLimits.hardCap(content.replace(Regex("\\s+"), " ").trim(), GatewayLimits.MAX_LOG_RESULT_CHARS)
    }

    private fun writeAtomically(target: File, text: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw GatewayException("Cannot create gateway directory.")
        val temp = File(parent, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    private data class CachedExecutor(val fingerprint: Int, val executor: GatewayToolExecutor)

    companion object {
        const val CONFIG_FILE_NAME = "gateways.json"
        const val LOG_FILE_NAME = "gateway-logs.jsonl"
        const val DEFAULT_RUN_ID = "default"
    }
}

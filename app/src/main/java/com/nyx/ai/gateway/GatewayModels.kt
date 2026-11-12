package com.nyx.ai.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** The two gateway transports NYX understands today. */
enum class GatewayKind {
    MCP_HTTP,
    GITHUB,
    ;

    companion object {
        fun parse(value: String?): GatewayKind? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

enum class GatewayHealthState { UNKNOWN, HEALTHY, DEGRADED, UNREACHABLE, DISABLED, MISCONFIGURED }

/** Resolves an opaque credential reference (for example a [CredentialVault] key) to a token. */
typealias GatewayTokenProvider = suspend (credentialRef: String) -> String?

/**
 * A persisted gateway definition. The credential itself is never stored here: only the reference
 * the host resolves through [GatewayTokenProvider] at call time.
 */
data class GatewayServerConfig(
    val id: String,
    val name: String,
    val kind: GatewayKind,
    val endpoint: String,
    val credentialRef: String? = null,
    val enabled: Boolean = false,
    val allowedTools: Set<String> = emptySet(),
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val maxCallsPerRun: Int = DEFAULT_MAX_CALLS_PER_RUN,
    val maxResponseChars: Int = DEFAULT_MAX_RESPONSE_CHARS,
) {
    /** Structural validation only; it does not check that the endpoint is reachable. */
    fun validate(): List<String> = buildList {
        if (!ID_PATTERN.matches(id)) add("id must match ${ID_PATTERN.pattern}")
        if (name.isBlank() || name.length > 80) add("name must be 1..80 characters")
        if (endpoint.isBlank() || endpoint.length > 2048) add("endpoint must be 1..2048 characters")
        if (timeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            add("timeoutSeconds must be in $MIN_TIMEOUT_SECONDS..$MAX_TIMEOUT_SECONDS")
        }
        if (maxCallsPerRun !in 1..MAX_CALLS_PER_RUN_LIMIT) add("maxCallsPerRun must be in 1..$MAX_CALLS_PER_RUN_LIMIT")
        if (maxResponseChars !in MIN_RESPONSE_CHARS..MAX_RESPONSE_CHARS_LIMIT) {
            add("maxResponseChars must be in $MIN_RESPONSE_CHARS..$MAX_RESPONSE_CHARS_LIMIT")
        }
        if (kind == GatewayKind.GITHUB && credentialRef.isNullOrBlank()) add("GitHub gateways require a credentialRef")
        if (allowedTools.size > MAX_ALLOWED_TOOLS) add("allowedTools must contain at most $MAX_ALLOWED_TOOLS entries")
        allowedTools.filter { it.isBlank() || it.length > 120 }.takeIf { it.isNotEmpty() }?.let {
            add("allowedTools entries must be 1..120 characters")
        }
    }

    fun isValid(): Boolean = validate().isEmpty()

    /** Credentials must never ride in a URL; the token provider supplies them per call. */
    fun hasEmbeddedCredentials(): Boolean = runCatching {
        !java.net.URI(endpoint.trim()).userInfo.isNullOrEmpty()
    }.getOrDefault(false)

    fun withEnabled(value: Boolean): GatewayServerConfig = copy(enabled = value)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("endpoint", endpoint)
        put("credentialRef", credentialRef ?: JSONObject.NULL)
        put("enabled", enabled)
        put("allowedTools", JSONArray(allowedTools.sorted()))
        put("timeoutSeconds", timeoutSeconds)
        put("maxCallsPerRun", maxCallsPerRun)
        put("maxResponseChars", maxResponseChars)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 20
        const val DEFAULT_MAX_CALLS_PER_RUN = 20
        const val DEFAULT_MAX_RESPONSE_CHARS = 20_000
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 120
        const val MAX_CALLS_PER_RUN_LIMIT = 200
        const val MIN_RESPONSE_CHARS = 200
        const val MAX_RESPONSE_CHARS_LIMIT = 200_000
        const val MAX_ALLOWED_TOOLS = 64
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        /** Returns null when the object is not a usable config rather than inventing defaults. */
        fun fromJson(json: JSONObject): GatewayServerConfig? {
            val id = json.optString("id").trim()
            val kind = GatewayKind.parse(json.optString("kind")) ?: return null
            val endpoint = json.optString("endpoint").trim()
            if (id.isEmpty() || endpoint.isEmpty()) return null
            val tools = json.optJSONArray("allowedTools")?.let { array ->
                buildSet { for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add) }
            } ?: emptySet()
            return GatewayServerConfig(
                id = id,
                name = json.optString("name", id).ifBlank { id },
                kind = kind,
                endpoint = endpoint,
                credentialRef = json.optString("credentialRef").takeIf { it.isNotBlank() && it != "null" },
                enabled = json.optBoolean("enabled", false),
                allowedTools = tools,
                timeoutSeconds = json.optInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
                    .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
                maxCallsPerRun = json.optInt("maxCallsPerRun", DEFAULT_MAX_CALLS_PER_RUN)
                    .coerceIn(1, MAX_CALLS_PER_RUN_LIMIT),
                maxResponseChars = json.optInt("maxResponseChars", DEFAULT_MAX_RESPONSE_CHARS)
                    .coerceIn(MIN_RESPONSE_CHARS, MAX_RESPONSE_CHARS_LIMIT),
            )
        }
    }
}

/** One remote tool. [inputSchemaJson] is always a JSON object; it is passed through as data. */
data class GatewayTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchemaJson", inputSchemaJson)
    }

    companion object {
        fun fromJson(json: JSONObject): GatewayTool = GatewayTool(
            name = json.optString("name"),
            description = json.optString("description"),
            inputSchemaJson = json.optString("inputSchemaJson", "{}"),
        )
    }
}

/** Result of a single tool call. [boundedRaw] is the already-capped transport payload. */
data class GatewayToolResult(
    val success: Boolean,
    val content: String,
    val isError: Boolean,
    val latencyMs: Long,
    val boundedRaw: String = "",
) {
    val truncated: Boolean get() = content.endsWith(TRUNCATION_MARKER)

    companion object {
        const val TRUNCATION_MARKER = "\n…[truncated]"

        fun failure(message: String, latencyMs: Long = 0L): GatewayToolResult =
            GatewayToolResult(false, message, true, latencyMs, "")
    }
}

data class GatewayHealth(
    val state: GatewayHealthState,
    val detail: String,
    val toolCount: Int = 0,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun disabled(): GatewayHealth = GatewayHealth(GatewayHealthState.DISABLED, "Gateway is disabled.", 0)
        fun unknown(): GatewayHealth = GatewayHealth(GatewayHealthState.UNKNOWN, "No health check has run yet.", 0)
    }
}

/**
 * The single contract every gateway transport implements, so MCP servers and the native GitHub
 * integration are interchangeable to the agent, the UI, and the policy layer.
 *
 * Implementations bound their own request size and time, cancel in-flight calls when the caller's
 * coroutine is cancelled, and stop accepting work after [close].
 */
interface GatewayToolExecutor {
    suspend fun listTools(): List<GatewayTool>
    suspend fun callTool(name: String, argumentsJson: String = "{}"): GatewayToolResult
    suspend fun health(): GatewayHealth
    fun close()
}

/** Append-only audit record. Arguments and results are summaries, never raw secrets. */
data class GatewayLogEntry(
    val at: Long,
    val serverId: String,
    val tool: String,
    val success: Boolean,
    val durationMs: Long,
    val argsSummary: String,
    val resultSummary: String,
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at)
        put("serverId", serverId)
        put("tool", tool)
        put("success", success)
        put("durationMs", durationMs)
        put("argsSummary", argsSummary)
        put("resultSummary", resultSummary)
        if (error != null) put("error", error)
    }

    companion object {
        fun fromJson(json: JSONObject): GatewayLogEntry? {
            val serverId = json.optString("serverId")
            val tool = json.optString("tool")
            if (serverId.isEmpty() && tool.isEmpty()) return null
            return GatewayLogEntry(
                at = json.optLong("at"),
                serverId = serverId,
                tool = tool,
                success = json.optBoolean("success"),
                durationMs = json.optLong("durationMs"),
                argsSummary = json.optString("argsSummary"),
                resultSummary = json.optString("resultSummary"),
                error = json.optString("error").takeIf { it.isNotBlank() },
            )
        }
    }
}

/** A refused configuration or policy violation. Messages never contain credential material. */
class GatewayException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Shared bounds and sanitisation used by every gateway transport. */
object GatewayLimits {
    /** Hard ceiling for a single HTTP response body, mirroring the MCP client requirement. */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024
    const val MAX_ARGUMENT_CHARS = 8_000
    const val MAX_LOG_ARGS_CHARS = 300
    const val MAX_LOG_RESULT_CHARS = 200
    const val LOG_CAP = 300

    fun truncate(value: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (value.length <= maxChars) return value
        return value.take(maxChars) + GatewayToolResult.TRUNCATION_MARKER
    }

    /** Removes known secrets and obvious bearer material from free text before it is surfaced. */
    /** Cuts to a hard limit with no marker; used where a strict byte budget is audited. */
    fun hardCap(value: String, maxChars: Int): String = if (value.length <= maxChars) value else value.take(maxChars)

    fun redact(value: String, secrets: Collection<String> = emptyList()): String {
        var output = value
        for (secret in secrets) {
            if (secret.length >= 8) output = output.replace(secret, "[redacted]")
        }
        return BEARER_PATTERN.replace(output) { match ->
            "${match.groupValues[1]}[redacted]"
        }
    }

    fun redactJson(raw: String, secrets: Collection<String> = emptyList()): String {
        val redacted = redact(raw, secrets)
        return redacted.replace(SECRET_FIELD_PATTERN) { match ->
            "${match.groupValues[1]}\"[redacted]\""
        }
    }

    private val BEARER_PATTERN = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}")
    private val SECRET_FIELD_PATTERN = Regex(
        "(?i)\"(access_token|refresh_token|token|authorization|api_key|apikey|secret|password)\"\\s*:\\s*\"[^\"]*\"",
    )
}

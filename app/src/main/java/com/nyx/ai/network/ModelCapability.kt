package com.nyx.ai.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Tri-state because a connection or a missing probe never proves a capability. */
enum class CapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/**
 * Strategy is a workload decision, never an authorization. Every strategy still passes through the
 * same workspace, command, evidence and recovery gates.
 */
enum class PlanningStrategy { DIRECT, INCREMENTAL, FULL, GUIDED }

/** Runtime identity whose change invalidates learned capability results. */
data class ModelIdentity(
    val providerId: String,
    val providerKind: String,
    val model: String,
    val contextLength: Int,
    val cpuThreads: Int,
) {
    fun fingerprint(): String = listOf(providerId, providerKind, model, contextLength, cpuThreads).joinToString("|")
}

data class ModelCapabilityProfile(
    val identity: ModelIdentity,
    val structuredOutput: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val nativeTools: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val textActions: CapabilitySupport = CapabilitySupport.SUPPORTED,
    val reasoningStream: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val stats: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val observedOutputTokensPerSecond: Double? = null,
    val observedFirstTextMillis: Long? = null,
    val observedAtEpochMs: Long = 0,
    val source: String = "unverified",
    val detail: String? = null,
) {
    fun summary(): String = buildString {
        append("Model capabilities (${source}): schema output ${structuredOutput.label()}, ")
        append("native tools ${nativeTools.label()}, validated text actions ${textActions.label()}, ")
        append("reasoning stream ${reasoningStream.label()}, provider statistics ${stats.label()}.\n")
        append("Observed first text: ${observedFirstTextMillis?.let { "$it ms" } ?: "unknown"}; ")
        append("generation throughput: ${observedOutputTokensPerSecond?.let { String.format(Locale.ROOT, "%.2f tokens/s", it) } ?: "unknown"} (short observation, not a benchmark).\n")
        append("Planning strategy is selected per objective from these capabilities, goal shape and enabled tools.")
        detail?.let { append("\n$it") }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("providerId", identity.providerId)
        .put("providerKind", identity.providerKind)
        .put("model", identity.model)
        .put("contextLength", identity.contextLength)
        .put("cpuThreads", identity.cpuThreads)
        .put("structuredOutput", structuredOutput.name)
        .put("nativeTools", nativeTools.name)
        .put("textActions", textActions.name)
        .put("reasoningStream", reasoningStream.name)
        .put("stats", stats.name)
        .put("throughput", observedOutputTokensPerSecond ?: JSONObject.NULL)
        .put("firstTextMillis", observedFirstTextMillis ?: JSONObject.NULL)
        .put("observedAt", observedAtEpochMs)
        .put("source", source)
        .put("detail", detail ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): ModelCapabilityProfile? = runCatching {
            val identity = ModelIdentity(
                providerId = value.getString("providerId"),
                providerKind = value.getString("providerKind"),
                model = value.getString("model"),
                contextLength = value.getInt("contextLength"),
                cpuThreads = value.getInt("cpuThreads"),
            )
            ModelCapabilityProfile(
                identity = identity,
                structuredOutput = value.support("structuredOutput"),
                nativeTools = value.support("nativeTools"),
                textActions = value.support("textActions"),
                reasoningStream = value.support("reasoningStream"),
                stats = value.support("stats"),
                observedOutputTokensPerSecond = value.optDouble("throughput").takeUnless { it.isNaN() },
                observedFirstTextMillis = value.optLong("firstTextMillis").takeIf { value.has("firstTextMillis") && !value.isNull("firstTextMillis") },
                observedAtEpochMs = value.optLong("observedAt", 0),
                source = value.optString("source", "stored"),
                detail = value.optString("detail").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}

private fun CapabilitySupport.label(): String = when (this) {
    CapabilitySupport.SUPPORTED -> "yes"
    CapabilitySupport.UNSUPPORTED -> "no"
    CapabilitySupport.UNKNOWN -> "unverified"
}

private fun JSONObject.support(name: String): CapabilitySupport =
    runCatching { CapabilitySupport.valueOf(optString(name, CapabilitySupport.UNKNOWN.name)) }
        .getOrDefault(CapabilitySupport.UNKNOWN)

data class PlanningDecision(
    val strategy: PlanningStrategy,
    val reason: String,
    val maxInitialSteps: Int,
    val allowExpansion: Boolean,
    val disableThinking: Boolean,
)

/** Pure capability and strategy policy; no I/O, permissions or model-name special cases. */
object ModelCapabilityPolicy {
    const val CACHE_TTL_MS = 24 * 60 * 60 * 1_000L
    const val FULL_STRATEGY_MIN_TOKENS_PER_SECOND = 6.0
    const val FULL_STRATEGY_MIN_CONTEXT = 4_096

    private val textGoal = Regex(
        "(?i)^\\s*(?:explain|summari[sz]e|translate|paraphrase|define|compare|describe|answer|outline|" +
            "brainstorm|draft|compose|rewrite|proofread|review|critique|analy[sz]e|" +
            "(?:write|tell)\\s+(?:me\\s+)?(?:a|an|the)?\\s*(?:short\\s+|brief\\s+|simple\\s+)?(?:poem|haiku|story|paragraph|essay|message|reply|list))\\b",
    )
    private val resourceIntent = Regex(
        "(?i)\\b(?:file|files|folder|directory|workspace|repo|repository|project|save|write to|create|delete|edit|" +
            "modify|rename|move|copy|run|execute|command|shell|terminal|compile|build|test|install|uninstall|" +
            "download|fetch|browse|open|read|inspect|check|verify|commit|push|publish|deploy)\\b|[/\\\\]",
    )

    fun decide(
        profile: ModelCapabilityProfile,
        goal: String,
        toolsAvailable: Boolean,
    ): PlanningDecision {
        val compactGoal = goal.length <= 400 && goal.lineSequence().count { it.isNotBlank() } <= 2
        if (compactGoal && textGoal.containsMatchIn(goal) && !resourceIntent.containsMatchIn(goal)) {
            return PlanningDecision(
                PlanningStrategy.DIRECT,
                "Simple text-only objective; a deterministic single inference task starts without a planning call.",
                maxInitialSteps = 1,
                allowExpansion = false,
                disableThinking = false,
            )
        }
        if (profile.structuredOutput == CapabilitySupport.UNSUPPORTED || profile.textActions == CapabilitySupport.UNSUPPORTED) {
            return PlanningDecision(
                PlanningStrategy.GUIDED,
                "This model has not demonstrated constrained JSON output; one bounded plain-text decision is requested at a time.",
                maxInitialSteps = 1,
                allowExpansion = true,
                disableThinking = profile.reasoningStream == CapabilitySupport.SUPPORTED,
            )
        }
        val complex = ComplexGoal.heuristic(goal)
        val fastEnough = (profile.observedOutputTokensPerSecond ?: 0.0) >= FULL_STRATEGY_MIN_TOKENS_PER_SECOND
        val contextEnough = (profile.identity.contextLength) >= FULL_STRATEGY_MIN_CONTEXT
        if (complex && toolsAvailable && profile.structuredOutput == CapabilitySupport.SUPPORTED && fastEnough && contextEnough) {
            return PlanningDecision(
                PlanningStrategy.FULL,
                "Complex objective, demonstrated schema output and measured throughput justify one full dependency graph.",
                maxInitialSteps = 12,
                allowExpansion = false,
                disableThinking = false,
            )
        }
        return PlanningDecision(
            PlanningStrategy.INCREMENTAL,
            buildString {
                append("Incremental planning starts with a small validated plan and expands only at verified checkpoints")
                when {
                    complex -> append("; this goal is complex")
                    profile.structuredOutput != CapabilitySupport.SUPPORTED -> append("; schema output is unverified")
                    else -> append("; the objective is tool-oriented")
                }
                if (!fastEnough) append("; measured or known throughput is below the full-graph threshold")
                append(".")
            },
            maxInitialSteps = 2,
            allowExpansion = true,
            disableThinking = profile.reasoningStream == CapabilitySupport.SUPPORTED,
        )
    }

    fun fromDiagnostic(
        identity: ModelIdentity,
        result: ModelDiagnosticResult,
        now: Long = System.currentTimeMillis(),
    ): ModelCapabilityProfile {
        val schema = when (result.formatCompatibility) {
            DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED -> CapabilitySupport.SUPPORTED
            DiagnosticFormatCompatibility.INVALID_RESPONSE -> CapabilitySupport.UNSUPPORTED
            // Prompt-only success does not prove constrained decoding.
            DiagnosticFormatCompatibility.PROMPT_JSON_SAMPLE_PASSED,
            DiagnosticFormatCompatibility.NOT_TESTED,
            -> CapabilitySupport.UNKNOWN
        }
        val reachable = result.providerReachable == DiagnosticCheck.PASSED && result.modelAccessible == DiagnosticCheck.PASSED
        return ModelCapabilityProfile(
            identity = identity,
            structuredOutput = schema,
            nativeTools = CapabilitySupport.UNKNOWN,
            textActions = CapabilitySupport.SUPPORTED,
            reasoningStream = CapabilitySupport.UNKNOWN,
            stats = if (result.stats?.promptTokens != null || result.stats?.outputTokens != null) {
                CapabilitySupport.SUPPORTED
            } else {
                CapabilitySupport.UNKNOWN
            },
            observedOutputTokensPerSecond = throughput(result.stats),
            observedFirstTextMillis = result.firstResponseLatencyMillis,
            observedAtEpochMs = now,
            source = if (reachable) "bounded probe" else "bounded probe (unreachable)",
            detail = if (reachable) {
                "A successful connection or tiny JSON sample is not proof of autonomous capability."
            } else {
                "Provider or model was not reachable during the probe; capabilities remain unverified."
            },
        )
    }

    fun throughput(stats: InferenceTokenStats?): Double? {
        val tokens = stats?.outputTokens ?: return null
        val duration = stats.generationDurationNanos ?: return null
        if (duration <= 0) return null
        return tokens.toDouble() * 1_000_000_000.0 / duration
    }

    /** Learns only from provider-observable behaviour; never guesses from a model name. */
    fun withObservation(
        profile: ModelCapabilityProfile,
        thinkingObserved: Boolean,
        stats: InferenceTokenStats?,
        firstTextMillis: Long?,
        now: Long = System.currentTimeMillis(),
    ): ModelCapabilityProfile = profile.copy(
        reasoningStream = if (thinkingObserved) CapabilitySupport.SUPPORTED else profile.reasoningStream,
        stats = if (stats?.promptTokens != null || stats?.outputTokens != null) CapabilitySupport.SUPPORTED else profile.stats,
        observedOutputTokensPerSecond = throughput(stats) ?: profile.observedOutputTokensPerSecond,
        observedFirstTextMillis = firstTextMillis ?: profile.observedFirstTextMillis,
        observedAtEpochMs = now,
        source = "observed request",
    )

    fun isStale(profile: ModelCapabilityProfile, identity: ModelIdentity, now: Long = System.currentTimeMillis()): Boolean =
        profile.identity != identity || now - profile.observedAtEpochMs > CACHE_TTL_MS || profile.observedAtEpochMs <= 0
}

/** Coarse structural heuristic only; it selects workload and never grants authority. */
internal object ComplexGoal {
    private val markers = Regex(
        "(?i)\\b(?:architecture|refactor|migration|integrat(?:e|ion)|deploy|production|full[- ]stack|" +
            "end[- ]to[- ]end|multi[- ](?:agent|stage|step)|(?:build|implement|develop|create)\\s+" +
            "(?:an?\\s+)?(?:(?:android|mobile|web|desktop|kotlin|java)\\s+)?(?:app|application|system|website|game|project))\\b",
    )
    private val negation = Regex("(?i)\\b(?:do not|don't|does not|never|not|no|without|instead of|rather than)\\b")
    private val boundary = Regex("(?i)[.!?;,\\n]|\\b(?:but|then|however|and)\\b")

    fun heuristic(goal: String): Boolean = markers.findAll(goal).any { match ->
        val prefix = goal.substring(0, match.range.first)
        val clauseStart = boundary.findAll(prefix).lastOrNull()?.range?.last?.plus(1) ?: 0
        !negation.containsMatchIn(prefix.substring(clauseStart))
    }
}

interface CapabilityPersistence {
    fun read(): String?
    fun write(value: String)
}

class FileCapabilityPersistence(private val file: File) : CapabilityPersistence {
    override fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    override fun write(value: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(value)
            if (!temp.renameTo(file)) {
                file.writeText(value)
                temp.delete()
            }
        }
    }
}

/** Bounded, fingerprint-keyed cache. Stale entries are evicted rather than reused. */
class ModelCapabilityStore(
    private val persistence: CapabilityPersistence? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, ModelCapabilityProfile>()

    init {
        runCatching {
            val raw = persistence?.read() ?: return@runCatching
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val profile = array.optJSONObject(index)?.let(ModelCapabilityProfile::fromJson) ?: continue
                entries[profile.identity.fingerprint()] = profile
            }
        }
    }

    @Synchronized fun get(identity: ModelIdentity): ModelCapabilityProfile? {
        val profile = entries[identity.fingerprint()] ?: return null
        if (ModelCapabilityPolicy.isStale(profile, identity, clock())) {
            entries.remove(identity.fingerprint())
            persist()
            return null
        }
        return profile
    }

    @Synchronized fun put(profile: ModelCapabilityProfile) {
        entries[profile.identity.fingerprint()] = profile
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.observedAtEpochMs }?.key ?: break
            entries.remove(oldest)
        }
        persist()
    }

    @Synchronized fun recordObservation(
        identity: ModelIdentity,
        thinkingObserved: Boolean,
        stats: InferenceTokenStats?,
        firstTextMillis: Long?,
    ): ModelCapabilityProfile {
        val current = get(identity) ?: ModelCapabilityProfile(identity)
        val updated = ModelCapabilityPolicy.withObservation(current, thinkingObserved, stats, firstTextMillis, clock())
        put(updated)
        return updated
    }

    @Synchronized fun all(): List<ModelCapabilityProfile> = entries.values.sortedByDescending { it.observedAtEpochMs }

    @Synchronized fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        entries.values.forEach { array.put(it.toJson()) }
        persistence?.write(array.toString())
    }

    companion object {
        const val MAX_ENTRIES = 32
    }
}

/** Process-wide store initialized once with app-private storage. */
object ModelCapabilities {
    @Volatile private var instance: ModelCapabilityStore? = null

    fun initialize(file: File): ModelCapabilityStore {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: ModelCapabilityStore(FileCapabilityPersistence(file)).also { instance = it }
        }
    }

    fun store(): ModelCapabilityStore = instance ?: synchronized(this) {
        instance ?: ModelCapabilityStore().also { instance = it }
    }
}

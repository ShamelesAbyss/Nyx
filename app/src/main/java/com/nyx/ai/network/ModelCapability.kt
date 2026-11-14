package com.nyx.ai.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Adapter availability and model evidence are separate. A connection never proves a capability. */
enum class CapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class PlanningStrategy { DIRECT, INCREMENTAL, FULL, GUIDED }
enum class ObservedCapability { STRUCTURED_OUTPUT, NATIVE_TOOLS, TEXT_ACTIONS, REASONING_STREAM, STATISTICS, THINKING_CONTROL }

/** A typed adapter rejection, not a generic HTTP/transport failure or guessed model-name rule. */
class InferenceFeatureUnsupportedException(val feature: ObservedCapability) : java.io.IOException("Requested inference feature is explicitly unsupported")

/**
 * Non-secret runtime identity PLUS the effective options actually sent. runtimeRevision must change
 * when endpoint/model/runtime/configuration is replaced. Never put URLs, keys or user data in it.
 * Unknown server-side revisions cannot be discovered by this key; callers must invalidate/rotate.
 */
data class ModelIdentity(
    val providerId: String, val providerKind: String, val model: String,
    val contextLength: Int, val cpuThreads: Int,
    val runtimeRevision: String = "", val temperature: Double = 0.7,
    val maxOutputTokens: Int? = null, val responseSchemaHash: String? = null,
    val think: Boolean? = null, val toolSchemaHash: String? = null,
) {
    fun fingerprint(): String = digest(JSONArray(listOf(providerId, providerKind, model, contextLength, cpuThreads,
        runtimeRevision, temperature, maxOutputTokens, responseSchemaHash, think, toolSchemaHash)).toString())

    companion object {
        fun fromRequest(provider: InferenceProvider, model: String, options: InferenceOptions, tools: JSONArray? = null): ModelIdentity =
            ModelIdentity(provider.id, provider.kind, model, options.contextLength, options.cpuThreads,
                provider.capabilityRevision, options.temperature, options.maxOutputTokens,
                options.responseSchema?.let(::digest), options.think, tools?.toString()?.let(::digest))
    }
}

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class ModelCapabilityProfile(
    val identity: ModelIdentity,
    val structuredOutput: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val nativeTools: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val textActions: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val reasoningStream: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val stats: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val observedOutputTokensPerSecond: Double? = null,
    val observedFirstTextMillis: Long? = null,
    val observedAtEpochMs: Long = 0,
    val source: String = "unverified", val detail: String? = null,
    val thinkingControl: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val evidenceAtEpochMs: Map<ObservedCapability, Long> = emptyMap(),
    val throughputObservedAtEpochMs: Long = 0, val firstTextObservedAtEpochMs: Long = 0,
    /** Locally validated full-plan sample, NOT proof of universal constrained decoding/competence. */
    val fullPlanObservedAtEpochMs: Long = 0,
) {
    fun summary(): String = buildString {
        append("Model capabilities ($source): schema-requested compatible output ${structuredOutput.label()}, ")
        append("native tools ${nativeTools.label()}, validated text actions ${textActions.label()}, ")
        append("reasoning stream ${reasoningStream.label()}, provider statistics ${stats.label()}; thinking control ${thinkingControl.label()}.\n")
        append("Schema sample compatibility is not proof of general constraint enforcement or autonomous competence.\n")
        append("Observed first text: ${observedFirstTextMillis?.let { "$it ms" } ?: "unknown"}; ")
        append("generation throughput: ${observedOutputTokensPerSecond?.let { String.format(Locale.ROOT, "%.2f tokens/s", it) } ?: "unknown"} (short observation, not a benchmark).\n")
        append("Planning strategy is workload, never tool/workspace/artifact authorization.")
        detail?.let { append("\n$it") }
    }

    fun toJson(): JSONObject = JSONObject().put("cacheVersion", 2)
        .put("providerId", identity.providerId).put("providerKind", identity.providerKind).put("model", identity.model)
        .put("contextLength", identity.contextLength).put("cpuThreads", identity.cpuThreads)
        .put("runtimeRevision", identity.runtimeRevision).put("temperature", identity.temperature)
        .put("maxOutputTokens", identity.maxOutputTokens ?: JSONObject.NULL)
        .put("responseSchemaHash", identity.responseSchemaHash ?: JSONObject.NULL)
        .put("think", identity.think ?: JSONObject.NULL).put("toolSchemaHash", identity.toolSchemaHash ?: JSONObject.NULL)
        .put("structuredOutput", structuredOutput.name).put("nativeTools", nativeTools.name)
        .put("textActions", textActions.name).put("reasoningStream", reasoningStream.name).put("stats", stats.name)
        .put("thinkingControl", thinkingControl.name).put("throughput", observedOutputTokensPerSecond ?: JSONObject.NULL)
        .put("firstTextMillis", observedFirstTextMillis ?: JSONObject.NULL).put("observedAt", observedAtEpochMs)
        .put("source", source).put("detail", detail ?: JSONObject.NULL)
        .put("evidenceAt", JSONObject().also { obj -> evidenceAtEpochMs.forEach { (feature, time) -> obj.put(feature.name, time) } })
        .put("throughputAt", throughputObservedAtEpochMs).put("firstTextAt", firstTextObservedAtEpochMs)
        .put("fullPlanAt", fullPlanObservedAtEpochMs)

    companion object {
        fun fromJson(value: JSONObject): ModelCapabilityProfile? = runCatching {
            val identity = ModelIdentity(value.getString("providerId"), value.getString("providerKind"), value.getString("model"),
                value.getInt("contextLength"), value.getInt("cpuThreads"), value.optString("runtimeRevision", ""),
                value.optDouble("temperature", 0.7), value.optionalLong("maxOutputTokens")?.toInt(),
                value.optionalString("responseSchemaHash"), if (value.opt("think") is Boolean) value.getBoolean("think") else null,
                value.optionalString("toolSchemaHash"))
            // Old caches contain assumption-only text support and conflated schema failures. Re-probe
            // conservatively rather than treating undocumented historical fields as demonstrated proof.
            if (value.optInt("cacheVersion", 0) != 2) return@runCatching ModelCapabilityProfile(identity)
            val evidence = value.optJSONObject("evidenceAt")
            ModelCapabilityProfile(identity,
                structuredOutput = value.support("structuredOutput"), nativeTools = value.support("nativeTools"),
                textActions = value.support("textActions"), reasoningStream = value.support("reasoningStream"), stats = value.support("stats"),
                observedOutputTokensPerSecond = value.optDouble("throughput").takeIf { it.isFinite() && it >= 0 },
                observedFirstTextMillis = value.optionalLong("firstTextMillis")?.takeIf { it >= 0 },
                observedAtEpochMs = value.optLong("observedAt", 0), source = value.optString("source", "stored"),
                detail = value.optionalString("detail"), thinkingControl = value.support("thinkingControl"),
                evidenceAtEpochMs = ObservedCapability.entries.mapNotNull { feature ->
                    evidence?.optionalLong(feature.name)?.let { feature to it }
                }.toMap(), throughputObservedAtEpochMs = value.optLong("throughputAt"),
                firstTextObservedAtEpochMs = value.optLong("firstTextAt"), fullPlanObservedAtEpochMs = value.optLong("fullPlanAt"))
        }.getOrNull()
    }
}

private fun JSONObject.optionalString(name: String): String? = (opt(name) as? String)?.takeIf { it.isNotBlank() }
private fun JSONObject.optionalLong(name: String): Long? = (opt(name) as? Number)?.toLong()
private fun JSONObject.support(name: String): CapabilitySupport = runCatching {
    CapabilitySupport.valueOf(optString(name, CapabilitySupport.UNKNOWN.name))
}.getOrDefault(CapabilitySupport.UNKNOWN)
private fun CapabilitySupport.label(): String = when (this) {
    CapabilitySupport.SUPPORTED -> "yes"; CapabilitySupport.UNSUPPORTED -> "no"; CapabilitySupport.UNKNOWN -> "unverified"
}

private fun ModelCapabilityProfile.state(feature: ObservedCapability): CapabilitySupport = when (feature) {
    ObservedCapability.STRUCTURED_OUTPUT -> structuredOutput; ObservedCapability.NATIVE_TOOLS -> nativeTools
    ObservedCapability.TEXT_ACTIONS -> textActions; ObservedCapability.REASONING_STREAM -> reasoningStream
    ObservedCapability.STATISTICS -> stats; ObservedCapability.THINKING_CONTROL -> thinkingControl
}
private fun ModelCapabilityProfile.withState(feature: ObservedCapability, support: CapabilitySupport): ModelCapabilityProfile = when (feature) {
    ObservedCapability.STRUCTURED_OUTPUT -> copy(structuredOutput = support); ObservedCapability.NATIVE_TOOLS -> copy(nativeTools = support)
    ObservedCapability.TEXT_ACTIONS -> copy(textActions = support); ObservedCapability.REASONING_STREAM -> copy(reasoningStream = support)
    ObservedCapability.STATISTICS -> copy(stats = support); ObservedCapability.THINKING_CONTROL -> copy(thinkingControl = support)
}

data class PlanningDecision(val strategy: PlanningStrategy, val reason: String, val maxInitialSteps: Int,
    val allowExpansion: Boolean, val disableThinking: Boolean)

/** Pure evidence/workload policy. It never adds authority or picks a model by name. */
object ModelCapabilityPolicy {
    const val CACHE_TTL_MS = 24 * 60 * 60 * 1_000L
    const val FULL_STRATEGY_MIN_TOKENS_PER_SECOND = 6.0
    const val FULL_STRATEGY_MIN_CONTEXT = 4_096
    private val textGoal = Regex("(?i)^\\s*(?:explain|summari[sz]e|translate|paraphrase|define|compare|describe|answer|outline|brainstorm|draft|compose|rewrite|proofread|review|critique|analy[sz]e|(?:write|tell)\\s+(?:me\\s+)?(?:a|an|the)?\\s*(?:short\\s+|brief\\s+|simple\\s+)?(?:poem|haiku|story|paragraph|essay|message|reply|list))\\b")
    private val resourceIntent = Regex("(?i)\\b(?:file|files|folder|directory|workspace|repo|repository|project|save|write to|create|delete|edit|modify|rename|move|copy|run|execute|command|shell|terminal|compile|build|test|install|uninstall|download|fetch|browse|open|read|inspect|check|verify|commit|push|publish|deploy)\\b|[/\\\\]")

    fun decide(profile: ModelCapabilityProfile, goal: String, toolsAvailable: Boolean): PlanningDecision {
        if (goal.length <= 400 && goal.lineSequence().count { it.isNotBlank() } <= 2 && textGoal.containsMatchIn(goal) && !resourceIntent.containsMatchIn(goal))
            return PlanningDecision(PlanningStrategy.DIRECT, "Simple text-only objective; one inference task needs no planning call.", 1, false, false)
        if (profile.structuredOutput == CapabilitySupport.UNSUPPORTED)
            return PlanningDecision(PlanningStrategy.GUIDED, "Explicit schema rejection: request one bounded inference-only decision; JSON/tool authority is not inferred.", 1, true, false)
        val complex = ComplexGoal.heuristic(goal)
        val fast = profile.observedOutputTokensPerSecond?.let { it.isFinite() && it >= FULL_STRATEGY_MIN_TOKENS_PER_SECOND } == true
        if (complex && toolsAvailable && profile.structuredOutput == CapabilitySupport.SUPPORTED &&
            profile.fullPlanObservedAtEpochMs > 0 && fast && profile.identity.contextLength >= FULL_STRATEGY_MIN_CONTEXT)
            return PlanningDecision(PlanningStrategy.FULL, "Complex objective, locally validated full-plan sample and measured throughput justify a bounded full graph (not a competence guarantee).", 12, false, false)
        return PlanningDecision(PlanningStrategy.INCREMENTAL,
            "Incremental planning requests a small locally validated JSON plan; ${if (complex) "complex objective" else "tool-oriented objective"}; " +
                "${if (profile.structuredOutput == CapabilitySupport.SUPPORTED) "bounded schema sample demonstrated" else "schema output unverified"}; " +
                "${if (!fast) "measured throughput is unknown or below the full-graph threshold" else "full-graph behavior not yet demonstrated"}. No thinking control is inferred from reasoning output.",
            2, true, false)
    }

    fun fromDiagnostic(identity: ModelIdentity, result: ModelDiagnosticResult, now: Long = System.currentTimeMillis()): ModelCapabilityProfile {
        require(identity.providerId == result.providerId && identity.model == result.model) { "Diagnostic result belongs to another provider/model." }
        val actual = result.requestIdentity ?: identity
        require(actual.providerId == identity.providerId && actual.providerKind == identity.providerKind && actual.model == identity.model) { "Diagnostic request identity mismatch." }
        var profile = ModelCapabilityProfile(actual, observedAtEpochMs = now, source = "bounded probe", detail =
            "A tiny sample is not full planning, validated text-action behavior or proof of general schema enforcement. Transport/incomplete samples leave capability unverified.")
        when (result.formatCompatibility) {
            DiagnosticFormatCompatibility.OLLAMA_SCHEMA_SAMPLE_PASSED -> profile = evidence(profile, ObservedCapability.STRUCTURED_OUTPUT, CapabilitySupport.SUPPORTED, now)
            DiagnosticFormatCompatibility.SCHEMA_EXPLICITLY_REJECTED -> profile = evidence(profile, ObservedCapability.STRUCTURED_OUTPUT, CapabilitySupport.UNSUPPORTED, now)
            else -> Unit // Prompt-only/invalid/truncated/size/HTTP failures are not constrained-decoding evidence.
        }
        return withObservation(profile, result.thinkingCharacters > 0, result.stats, result.firstResponseLatencyMillis, now)
            .copy(source = "bounded probe")
    }

    fun throughput(stats: InferenceTokenStats?): Double? {
        val tokens = stats?.outputTokens?.takeIf { it >= 0 } ?: return null
        val duration = stats.generationDurationNanos?.takeIf { it > 0 } ?: return null
        return (tokens.toDouble() * 1_000_000_000.0 / duration).takeIf { it.isFinite() }
    }

    fun withObservation(profile: ModelCapabilityProfile, thinkingObserved: Boolean, stats: InferenceTokenStats?,
        firstTextMillis: Long?, now: Long = System.currentTimeMillis()): ModelCapabilityProfile {
        var updated = expireEvidence(profile, now)
        if (thinkingObserved) updated = evidence(updated, ObservedCapability.REASONING_STREAM, CapabilitySupport.SUPPORTED, now)
        if (listOf(stats?.promptTokens, stats?.outputTokens, stats?.generationDurationNanos, stats?.totalDurationNanos, stats?.loadDurationNanos).any { it != null && it >= 0 })
            updated = evidence(updated, ObservedCapability.STATISTICS, CapabilitySupport.SUPPORTED, now)
        val speed = throughput(stats)
        return updated.copy(observedOutputTokensPerSecond = speed ?: updated.observedOutputTokensPerSecond,
            throughputObservedAtEpochMs = if (speed != null) now else updated.throughputObservedAtEpochMs,
            observedFirstTextMillis = firstTextMillis?.takeIf { it >= 0 } ?: updated.observedFirstTextMillis,
            firstTextObservedAtEpochMs = if (firstTextMillis != null && firstTextMillis >= 0) now else updated.firstTextObservedAtEpochMs,
            observedAtEpochMs = now, source = "observed request")
    }

    internal fun evidence(profile: ModelCapabilityProfile, feature: ObservedCapability, support: CapabilitySupport, now: Long): ModelCapabilityProfile =
        profile.withState(feature, support).copy(evidenceAtEpochMs = profile.evidenceAtEpochMs + (feature to now), observedAtEpochMs = now)

    private fun fresh(at: Long, now: Long): Boolean = at > 0 && now >= at && now - at <= CACHE_TTL_MS
    fun expireEvidence(profile: ModelCapabilityProfile, now: Long = System.currentTimeMillis()): ModelCapabilityProfile {
        var pruned = profile
        ObservedCapability.entries.forEach { feature -> if (!fresh(profile.evidenceAtEpochMs[feature] ?: 0, now)) pruned = pruned.withState(feature, CapabilitySupport.UNKNOWN) }
        return pruned.copy(evidenceAtEpochMs = profile.evidenceAtEpochMs.filterValues { fresh(it, now) },
            observedOutputTokensPerSecond = profile.observedOutputTokensPerSecond.takeIf { fresh(profile.throughputObservedAtEpochMs, now) },
            observedFirstTextMillis = profile.observedFirstTextMillis.takeIf { fresh(profile.firstTextObservedAtEpochMs, now) },
            throughputObservedAtEpochMs = profile.throughputObservedAtEpochMs.takeIf { fresh(it, now) } ?: 0,
            firstTextObservedAtEpochMs = profile.firstTextObservedAtEpochMs.takeIf { fresh(it, now) } ?: 0,
            fullPlanObservedAtEpochMs = profile.fullPlanObservedAtEpochMs.takeIf { fresh(it, now) && pruned.structuredOutput == CapabilitySupport.SUPPORTED } ?: 0)
    }
    fun isStale(profile: ModelCapabilityProfile, identity: ModelIdentity, now: Long = System.currentTimeMillis()): Boolean =
        profile.identity != identity || !fresh(profile.observedAtEpochMs, now)

    /** Unknown/failing diagnostics do not erase independent still-fresh observations. */
    internal fun merge(current: ModelCapabilityProfile?, incoming: ModelCapabilityProfile, now: Long): ModelCapabilityProfile {
        val next = expireEvidence(incoming, now)
        if (current == null || current.identity != incoming.identity) return next
        var merged = expireEvidence(current, now)
        ObservedCapability.entries.forEach { feature ->
            val time = next.evidenceAtEpochMs[feature] ?: 0
            if (next.state(feature) != CapabilitySupport.UNKNOWN && time >= (merged.evidenceAtEpochMs[feature] ?: 0))
                merged = evidence(merged, feature, next.state(feature), time)
        }
        val speedNew = next.observedOutputTokensPerSecond != null && next.throughputObservedAtEpochMs >= merged.throughputObservedAtEpochMs
        val textNew = next.observedFirstTextMillis != null && next.firstTextObservedAtEpochMs >= merged.firstTextObservedAtEpochMs
        return merged.copy(observedAtEpochMs = maxOf(current.observedAtEpochMs, incoming.observedAtEpochMs), source = incoming.source, detail = incoming.detail,
            observedOutputTokensPerSecond = if (speedNew) next.observedOutputTokensPerSecond else merged.observedOutputTokensPerSecond,
            throughputObservedAtEpochMs = if (speedNew) next.throughputObservedAtEpochMs else merged.throughputObservedAtEpochMs,
            observedFirstTextMillis = if (textNew) next.observedFirstTextMillis else merged.observedFirstTextMillis,
            firstTextObservedAtEpochMs = if (textNew) next.firstTextObservedAtEpochMs else merged.firstTextObservedAtEpochMs,
            fullPlanObservedAtEpochMs = if (merged.structuredOutput == CapabilitySupport.SUPPORTED) maxOf(next.fullPlanObservedAtEpochMs, merged.fullPlanObservedAtEpochMs) else 0)
    }
}

/** Coarse workload heuristic; no natural-language interpretation grants authority. */
internal object ComplexGoal {
    private val markers = Regex("(?i)\\b(?:architecture|refactor|migration|integrat(?:e|ion)|deploy|production|full[- ]stack|end[- ]to[- ]end|multi[- ](?:agent|stage|step)|(?:build|implement|develop|create)\\s+(?:an?\\s+)?(?:(?:android|mobile|web|desktop|kotlin|java)\\s+)?(?:app|application|system|website|game|project))\\b")
    private val negation = Regex("(?i)\\b(?:do not|don't|does not|never|not|no|without|instead of|rather than)\\b")
    private val boundary = Regex("(?i)[.!?;,\\n]|\\b(?:but|then|however|and)\\b")
    fun heuristic(goal: String): Boolean = markers.findAll(goal).any { match ->
        val prefix = goal.substring(0, match.range.first)
        val start = boundary.findAll(prefix).lastOrNull()?.range?.last?.plus(1) ?: 0
        !negation.containsMatchIn(prefix.substring(start))
    }
}

interface CapabilityPersistence { fun read(): String?; fun write(value: String) }
class FileCapabilityPersistence(private val file: File) : CapabilityPersistence {
    override fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    override fun write(value: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(value)
            if (!temp.renameTo(file)) { file.writeText(value); temp.delete() }
        }
    }
}

/** Bounded exact-request cache. Each feature and metric expires independently, including on reload. */
class ModelCapabilityStore(private val persistence: CapabilityPersistence? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = LinkedHashMap<String, ModelCapabilityProfile>()
    init {
        runCatching {
            val raw = persistence?.read() ?: return@runCatching
            val array = JSONArray(raw)
            for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val profile = array.optJSONObject(index)?.let(ModelCapabilityProfile::fromJson) ?: continue
                if (!ModelCapabilityPolicy.isStale(profile, profile.identity, clock())) entries[profile.identity.fingerprint()] = ModelCapabilityPolicy.expireEvidence(profile, clock())
            }
            persist() // Persist expired/legacy pruning, not only the in-memory eviction.
        }
    }
    @Synchronized fun get(identity: ModelIdentity): ModelCapabilityProfile? {
        val key = identity.fingerprint()
        val profile = entries[key] ?: return null
        if (ModelCapabilityPolicy.isStale(profile, identity, clock())) { entries.remove(key); persist(); return null }
        return ModelCapabilityPolicy.expireEvidence(profile, clock()).also { if (it != profile) { entries[key] = it; persist() } }
    }
    @Synchronized fun put(profile: ModelCapabilityProfile) {
        val key = profile.identity.fingerprint()
        entries[key] = ModelCapabilityPolicy.merge(get(profile.identity), profile, clock())
        while (entries.size > MAX_ENTRIES) { entries.remove(entries.entries.minByOrNull { it.value.observedAtEpochMs }?.key ?: break) }
        persist()
    }
    @Synchronized fun recordObservation(identity: ModelIdentity, thinkingObserved: Boolean, stats: InferenceTokenStats?, firstTextMillis: Long?): ModelCapabilityProfile {
        val updated = ModelCapabilityPolicy.withObservation(get(identity) ?: ModelCapabilityProfile(identity), thinkingObserved, stats, firstTextMillis, clock())
        put(updated); return requireNotNull(get(identity))
    }
    /** Call ONLY after a completed response passes the actual local schema/allowlist/action validator. No execution privilege is granted. */
    @Synchronized fun recordValidatedAction(identity: ModelIdentity, nativeTool: Boolean): ModelCapabilityProfile = recordEvidence(identity,
        if (nativeTool) ObservedCapability.NATIVE_TOOLS else ObservedCapability.TEXT_ACTIONS, CapabilitySupport.SUPPORTED)
    /** Adapter must establish explicit feature rejection. Transport, HTTP status alone, malformed action and denial of authority do not qualify. */
    @Synchronized fun recordExplicitRejection(identity: ModelIdentity, feature: ObservedCapability): ModelCapabilityProfile = recordEvidence(identity, feature, CapabilitySupport.UNSUPPORTED)
    /** Completed request using a schema, locally validated WHOLE DAG and profile budget; not a tiny JSON probe. */
    @Synchronized fun recordValidatedFullPlan(identity: ModelIdentity): ModelCapabilityProfile {
        require(identity.responseSchemaHash != null) { "A prompt-only plan does not demonstrate schema-requested planning." }
        val profile = ModelCapabilityPolicy.evidence(get(identity) ?: ModelCapabilityProfile(identity), ObservedCapability.STRUCTURED_OUTPUT, CapabilitySupport.SUPPORTED, clock())
            .copy(fullPlanObservedAtEpochMs = clock(), source = "locally validated full-plan sample")
        put(profile); return requireNotNull(get(identity))
    }
    private fun recordEvidence(identity: ModelIdentity, feature: ObservedCapability, state: CapabilitySupport): ModelCapabilityProfile {
        val profile = ModelCapabilityPolicy.evidence(get(identity) ?: ModelCapabilityProfile(identity), feature, state, clock()).copy(source = "validated feature observation")
        put(profile); return requireNotNull(get(identity))
    }
    @Synchronized fun all(): List<ModelCapabilityProfile> = entries.values.toList().mapNotNull { get(it.identity) }.sortedByDescending { it.observedAtEpochMs }
    @Synchronized fun clear() { entries.clear(); persist() }
    @Synchronized fun invalidateProvider(providerId: String) { entries.entries.removeAll { it.value.identity.providerId == providerId }; persist() }
    private fun persist() { persistence?.write(JSONArray().also { a -> entries.values.forEach { a.put(it.toJson()) } }.toString()) }
    companion object { const val MAX_ENTRIES = 32 }
}

object ModelCapabilities {
    @Volatile private var instance: ModelCapabilityStore? = null
    fun initialize(file: File): ModelCapabilityStore = instance ?: synchronized(this) {
        instance ?: ModelCapabilityStore(FileCapabilityPersistence(file)).also { instance = it }
    }
    fun store(): ModelCapabilityStore = instance ?: synchronized(this) { instance ?: ModelCapabilityStore().also { instance = it } }
}

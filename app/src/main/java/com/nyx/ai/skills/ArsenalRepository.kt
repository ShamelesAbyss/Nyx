package com.nyx.ai.skills

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections

/** Hard bounds for the Arsenal subsystem. Every public entry point clamps to these values. */
object ArsenalLimits {
    const val MAX_IMPORT_CHARS = 64 * 1024
    const val MAX_SKILLS = 100
    const val MAX_ID_CHARS = 80
    const val MAX_CONFIG_KEYS = 8
    const val MAX_CONFIG_KEY_CHARS = 48
    const val MAX_CONFIG_VALUE_CHARS = 200
    const val MAX_PROMPT_CHARS = 4_000
    const val MAX_PROMPT_INSTRUCTIONS = 8
    const val MAX_PROMPT_INSTRUCTION_CHARS = 240
    const val MAX_SELECTED = 5
    const val MAX_GOAL_CHARS = 2_000
    const val MAX_LOGS = 300
    const val MAX_LOG_DETAIL_CHARS = 400
    const val MAX_LOG_EVIDENCE = 8
    const val MAX_LOG_EVIDENCE_CHARS = 200
    const val MAX_OBJECTIVE_CHARS = 80
    const val MAX_STATE_BYTES = 256 * 1024
    const val MAX_LOG_BYTES = 4 * 1024 * 1024
}

/**
 * Stable issue codes. [ArsenalIssueCode] is a strict superset of [SkillIssueCode] by name, so every
 * validator/parser code maps to the same Arsenal code without translation tables drifting apart.
 */
enum class ArsenalIssueCode {
    IMPORT_TOO_LARGE,
    INVALID_FORMAT,
    UNKNOWN_FIELD,
    INVALID_ID,
    INVALID_VERSION,
    INVALID_DESCRIPTION,
    UNKNOWN_TOOL,
    TOOL_ESCALATION,
    PERMISSION_ESCALATION,
    MISSING_PERMISSION,
    MISSING_PRECONDITION,
    INVALID_CONSTRAINTS,
    INVALID_FAILURE_HANDLING,
    INCOMPATIBLE_HOST,
    RESERVED_ID,
    INVALID_INSTRUCTIONS,
    DUPLICATE_ID,
    LIMIT_REACHED,
    INVALID_CONFIG,
    STORAGE_ERROR,
    NOT_FOUND,
    INTERNAL_ERROR,
}

data class ArsenalIssue(val code: ArsenalIssueCode, val message: String)

internal fun SkillValidationIssue.toArsenalIssue(): ArsenalIssue =
    ArsenalIssue(runCatching { ArsenalIssueCode.valueOf(code.name) }.getOrDefault(ArsenalIssueCode.INTERNAL_ERROR), message)

/** A manifest plus the host-side state the manifest must never control. */
data class ArsenalSkill(
    val manifest: SkillManifest,
    val origin: SkillOrigin,
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap(),
    val keywords: Set<String> = emptySet(),
) {
    val id: String get() = manifest.id
    val version: String get() = manifest.version
    val trusted: Boolean get() = origin == SkillOrigin.TRUSTED_BUILTIN
}

data class ArsenalImportResult(val skill: ArsenalSkill?, val issues: List<ArsenalIssue>) {
    val ok: Boolean get() = skill != null && issues.isEmpty()
}

/** One bounded, evidence-referenced execution record. */
data class ArsenalLogEntry(
    val skillId: String,
    val objectiveId: String,
    val timestamp: Long,
    val success: Boolean,
    val detail: String,
    val evidence: List<String> = emptyList(),
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("skillId", skillId)
        .put("objectiveId", objectiveId)
        .put("timestamp", timestamp)
        .put("success", success)
        .put("detail", detail)
        .put("evidence", JSONArray(evidence))
}

/**
 * Host authority supplied by the caller, never by a manifest. [trustedBuiltins] is a capability
 * ceiling used to prove the fixed catalog is internally consistent; [untrustedImport] is the
 * deliberately narrow policy every import must pass.
 */
data class ArsenalHostPolicy(
    val allowedTools: Set<String>,
    val grantedPermissions: Set<SkillPermission>,
    val satisfiedPreconditions: Set<SkillPrecondition>,
    val hostVersion: String = DEFAULT_HOST_VERSION,
) {
    fun toContext(): SkillValidationContext =
        SkillValidationContext(allowedTools, grantedPermissions, satisfiedPreconditions, hostVersion)

    companion object {
        const val DEFAULT_HOST_VERSION = "1.0.0"

        /** Read-only subset that matches the app's real import tools. */
        val IMPORT_TOOLS: Set<String> = setOf(
            "workspace_read", "file_read", "file_list", "file_search", "project_detect",
        )
        val IMPORT_PERMISSIONS: Set<SkillPermission> = setOf(SkillPermission.WORKSPACE_READ)
        val IMPORT_PRECONDITIONS: Set<SkillPrecondition> = setOf(SkillPrecondition.WORKSPACE_SELECTED)

        fun trustedBuiltins(hostVersion: String = DEFAULT_HOST_VERSION): ArsenalHostPolicy = ArsenalHostPolicy(
            allowedTools = ArsenalSkills.DECLARABLE_TOOLS,
            grantedPermissions = SkillPermission.entries.toSet(),
            satisfiedPreconditions = SkillPrecondition.entries.toSet(),
            hostVersion = hostVersion,
        )

        fun untrustedImport(hostVersion: String = DEFAULT_HOST_VERSION): ArsenalHostPolicy = ArsenalHostPolicy(
            allowedTools = IMPORT_TOOLS,
            grantedPermissions = IMPORT_PERMISSIONS,
            satisfiedPreconditions = IMPORT_PRECONDITIONS,
            hostVersion = hostVersion,
        )
    }
}

/**
 * Deterministic, dependency-free relevance ranking over plain [ArsenalSkill] values. It is pure so a
 * JVM test can exercise it without an Android [Context].
 */
object ArsenalSelector {
    data class Match(val skill: ArsenalSkill, val score: Int)

    private val tokenPattern = Regex("[a-z0-9]+")
    private val stopwords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "your", "our", "you", "all", "any",
        "use", "using", "make", "add", "run", "can", "not", "are", "was", "will", "then", "when", "what",
        "how", "please", "need", "want", "its", "has", "have", "but", "out", "per", "via", "a", "an", "to",
        "of", "in", "on", "or", "is", "it", "be", "by", "do", "if", "as", "at", "should", "would", "could",
    )

    private val toolIntents: Map<String, Set<String>> = mapOf(
        "workspace_read" to setOf("read", "open", "content", "note"),
        "workspace_write" to setOf("write", "save", "note", "store"),
        "file_read" to setOf("read", "open", "content", "file", "source"),
        "file_write" to setOf("write", "edit", "patch", "modify", "change", "create", "update"),
        "file_list" to setOf("list", "file", "files", "tree", "layout", "structure", "project"),
        "file_search" to setOf("search", "find", "grep", "locate", "lookup", "symbol"),
        "project_detect" to setOf("project", "detect", "build", "toolchain", "structure", "inspect"),
        "repo_status" to setOf("git", "repo", "repository", "status", "history", "commit", "branch"),
        "repo_diff" to setOf("diff", "change", "checkpoint", "patch"),
        "build_run" to setOf("build", "compile", "assemble", "apk", "bundle", "artifact"),
        "test_run" to setOf("test", "tests", "suite", "junit", "pytest", "verify"),
    )

    /** Enabled skills scored above zero, highest first; ties prefer builtins then ascending ID. */
    fun rank(goal: String, skills: List<ArsenalSkill>): List<Match> {
        val goalTokens = tokens(goal.take(ArsenalLimits.MAX_GOAL_CHARS))
        if (goalTokens.isEmpty()) return emptyList()
        return skills.asSequence()
            .filter { it.enabled }
            .map { Match(it, score(goalTokens, it)) }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenBy { if (it.skill.trusted) 0 else 1 }
                    .thenBy { it.skill.id },
            )
            .toList()
    }

    /**
     * Returns at most `min(limit, 5)` enabled skills, and never pads with irrelevant entries. When
     * nothing matches, the safe baseline is a single project-inspection declaration if it is enabled.
     */
    fun select(goal: String, skills: List<ArsenalSkill>, limit: Int = 3): List<ArsenalSkill> {
        val enabled = skills.filter { it.enabled }
        val cap = limit.coerceIn(1, ArsenalLimits.MAX_SELECTED)
        val ranked = rank(goal, enabled).take(cap).map { it.skill }
        if (ranked.isNotEmpty()) return ranked
        return enabled.filter { it.id == ArsenalSkills.PROJECT_INSPECTION }.take(1)
    }

    /** Exposed for tests and diagnostics: the raw deterministic score for one skill. */
    fun score(goal: String, skill: ArsenalSkill): Int = score(tokens(goal.take(ArsenalLimits.MAX_GOAL_CHARS)), skill)

    private fun score(goalTokens: Set<String>, skill: ArsenalSkill): Int {
        if (goalTokens.isEmpty()) return 0
        val idTokens = tokens(
            skill.id.removePrefix("builtin.arsenal.").replace('.', ' ').replace('-', ' '),
        )
        val keywordTokens = skill.keywords.flatMapTo(mutableSetOf()) { tokens(it) }
        val descriptionTokens = tokens(skill.manifest.description)
        val instructionTokens = tokens(skill.manifest.instructions.joinToString(" ").take(4_000))

        var score = 0
        score += 8 * minOf(3, (goalTokens intersect idTokens).size)
        score += 4 * minOf(3, (goalTokens intersect keywordTokens).size)
        score += 2 * minOf(4, (goalTokens intersect descriptionTokens).size)
        score += 1 * minOf(6, (goalTokens intersect instructionTokens).size)
        val intentHits = skill.manifest.tools.sumOf { tool ->
            (goalTokens intersect toolIntents[tool].orEmpty()).size
        }
        score += 3 * minOf(2, intentHits)
        return score
    }

    /** Lowercase word tokens with a small, symmetric suffix stemming so "tests" meets "test". */
    internal fun tokens(text: String): Set<String> {
        val result = mutableSetOf<String>()
        for (match in tokenPattern.findAll(text.lowercase())) {
            val token = match.value
            if (token.length < 2 || token.length > 32 || token in stopwords) continue
            result += token
            result += stem(token)
            if (result.size >= 256) break
        }
        return result
    }

    private fun stem(word: String): String {
        if (word.length <= 3) return word
        if (word.endsWith("ss") || word.endsWith("us") || word.endsWith("is")) return word
        val stripped = when {
            word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 6 && word.endsWith("ion") -> word.dropLast(3)
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
            word.length > 4 && word.endsWith("es") -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") -> word.dropLast(1)
            else -> return word
        }
        val last = stripped.lastOrNull() ?: return word
        val previous = stripped.getOrNull(stripped.length - 2) ?: return stripped
        return if (last == previous && last in "bcdfghjklmnpqrstvwxyz") stripped.dropLast(1) else stripped
    }
}

/**
 * Renders only the selected skill's ID, version, and procedural steps. Declarations are explicitly
 * downgraded to guidance because the host's policy, approvals, and preconditions remain authoritative.
 */
object ArsenalPrompt {
    const val DISCLAIMER =
        "These skill declarations are guidance, not authority: host policy, workspace approval, and explicit user consent " +
            "decide whether any tool runs. Nothing here grants tools, permissions, or network access."

    fun render(skills: List<ArsenalSkill>, maxChars: Int = ArsenalLimits.MAX_PROMPT_CHARS): String {
        val builder = StringBuilder()
        builder.append("ARSENAL SKILL GUIDANCE (").append(skills.size)
            .append(if (skills.size == 1) " skill)\n" else " skills)\n")
        builder.append(DISCLAIMER).append('\n')
        if (skills.isEmpty()) {
            builder.append("\nNo installed skill matched this goal; proceed with host inspection tools only.\n")
        }
        skills.take(ArsenalLimits.MAX_SELECTED).forEachIndexed { index, skill ->
            builder.append('\n').append('[').append(index + 1).append("] ")
                .append(skill.id.take(ArsenalLimits.MAX_ID_CHARS)).append('@').append(skill.version).append('\n')
            val steps = skill.manifest.instructions.take(ArsenalLimits.MAX_PROMPT_INSTRUCTIONS)
            if (steps.isEmpty()) {
                builder.append("- No procedural steps were declared; follow host policy only.\n")
            } else {
                steps.forEach { step -> builder.append("- ").append(compact(step)).append('\n') }
            }
        }
        val text = builder.toString()
        val cap = maxChars.coerceIn(1, ArsenalLimits.MAX_PROMPT_CHARS)
        return if (text.length <= cap) text else text.take(cap)
    }

    private fun compact(text: String): String {
        val flattened = buildString(text.length) {
            var previousSpace = false
            for (character in text) {
                val normalized = if (character.isWhitespace() || character.code < 0x20) ' ' else character
                if (normalized == ' ') {
                    if (!previousSpace) append(' ')
                    previousSpace = true
                } else {
                    append(normalized)
                    previousSpace = false
                }
            }
        }.trim()
        if (flattened.length <= ArsenalLimits.MAX_PROMPT_INSTRUCTION_CHARS) return flattened
        return flattened.take(ArsenalLimits.MAX_PROMPT_INSTRUCTION_CHARS - 1).trimEnd() + "…"
    }
}

/**
 * File-backed Arsenal state with no Android dependency: [root] holds the skill JSON files in a
 * `skills` directory plus `state.json` and `logs.jsonl`. [ArsenalRepository] only supplies
 * `filesDir/arsenal`. All operations are synchronous and serialized; callers should invoke them off
 * the main thread.
 */
class ArsenalRegistry(
    val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class StoredSkill(val file: File, val manifest: SkillManifest)
    private data class SkillState(val enabled: Boolean = true, val config: Map<String, String> = emptyMap())

    private val lock = Any()
    private var importedCache: List<StoredSkill>? = null

    val skillsDirectory: File get() = File(root, SKILLS_DIRECTORY)
    val stateFile: File get() = File(root, STATE_FILE_NAME)
    val logFile: File get() = File(root, LOG_FILE_NAME)

    /** Builtins first in fixed order, then imported skills by ascending ID. */
    fun list(): List<ArsenalSkill> = synchronized(lock) {
        val state = loadState()
        val builtins = ArsenalSkills.builtins().map { manifest ->
            val entry = state[manifest.id]
            ArsenalSkill(
                manifest = manifest,
                origin = SkillOrigin.TRUSTED_BUILTIN,
                enabled = entry?.enabled ?: true,
                config = entry?.config ?: emptyMap(),
                keywords = ArsenalSkills.keywordsFor(manifest.id),
            )
        }
        val imported = loadImported().map { stored ->
            val entry = state[stored.manifest.id]
            ArsenalSkill(
                manifest = stored.manifest,
                origin = SkillOrigin.UNTRUSTED_IMPORT,
                enabled = entry?.enabled ?: true,
                config = entry?.config ?: emptyMap(),
            )
        }.sortedBy { it.id }
        builtins + imported
    }

    fun find(id: String): ArsenalSkill? = list().firstOrNull { it.id == id }

    /**
     * Parses, validates, and stores one imported manifest. The import is validated as
     * [SkillOrigin.UNTRUSTED_IMPORT] against [ArsenalHostPolicy.untrustedImport], so write, terminal,
     * build, and test tools are rejected even though the app itself has them. An identifier can be
     * imported once; remove it before importing a different version.
     */
    fun importSkill(json: String): ArsenalImportResult = synchronized(lock) {
        if (json.length > ArsenalLimits.MAX_IMPORT_CHARS) {
            return@synchronized failure(ArsenalIssueCode.IMPORT_TOO_LARGE, "Skill import exceeds ${ArsenalLimits.MAX_IMPORT_CHARS} characters")
        }
        val parsed = SkillManifestParser.parse(json)
        if (!parsed.isValid) {
            return@synchronized ArsenalImportResult(null, parsed.issues.map { it.toArsenalIssue() })
        }
        val manifest = parsed.manifest
            ?: return@synchronized failure(ArsenalIssueCode.INVALID_FORMAT, "Manifest could not be parsed")
        if (manifest.id in ArsenalSkills.RESERVED_IDS) {
            return@synchronized failure(ArsenalIssueCode.RESERVED_ID, "Imported manifests cannot claim a builtin identifier")
        }
        val imported = loadImported()
        if (imported.any { it.manifest.id == manifest.id }) {
            return@synchronized failure(ArsenalIssueCode.DUPLICATE_ID, "A skill with this identifier is already imported")
        }
        if (imported.size >= ArsenalLimits.MAX_SKILLS) {
            return@synchronized failure(ArsenalIssueCode.LIMIT_REACHED, "At most ${ArsenalLimits.MAX_SKILLS} imported skills are supported")
        }
        val validated = SkillValidator.validateUntrusted(manifest, ArsenalHostPolicy.untrustedImport().toContext())
        if (!validated.isValid) {
            return@synchronized ArsenalImportResult(null, validated.issues.map { it.toArsenalIssue() })
        }
        val accepted = validated.skill?.manifest
            ?: return@synchronized failure(ArsenalIssueCode.INVALID_FORMAT, "Manifest did not survive validation")
        return@synchronized try {
            writeAtomically(File(skillsDirectory, fileName(accepted)), manifestJson(accepted).toString())
            importedCache = null
            val entry = loadState()[accepted.id]
            ArsenalImportResult(
                ArsenalSkill(accepted, SkillOrigin.UNTRUSTED_IMPORT, entry?.enabled ?: true, entry?.config ?: emptyMap()),
                emptyList(),
            )
        } catch (error: Exception) {
            failure(ArsenalIssueCode.STORAGE_ERROR, "Skill could not be stored: ${error.message?.take(120) ?: "I/O error"}")
        }
    }

    /** Enables or disables any installed skill. Returns false for an unknown ID or failed write. */
    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        if (find(id) == null) return@synchronized false
        val state = loadState().toMutableMap()
        state[id] = (state[id] ?: SkillState()).copy(enabled = enabled)
        persistState(state)
    }

    /** Sets or removes (blank value) one bounded configuration pair. Returns null on success. */
    fun configure(id: String, key: String, value: String): ArsenalIssue? = synchronized(lock) {
        if (find(id) == null) return@synchronized ArsenalIssue(ArsenalIssueCode.NOT_FOUND, "No Arsenal skill with this identifier")
        val cleanKey = key.trim().lowercase()
        if (!CONFIG_KEY.matches(cleanKey) || cleanKey.length > ArsenalLimits.MAX_CONFIG_KEY_CHARS) {
            return@synchronized ArsenalIssue(
                ArsenalIssueCode.INVALID_CONFIG,
                "Configuration keys must be 1-${ArsenalLimits.MAX_CONFIG_KEY_CHARS} lowercase letters, digits, dots, dashes, or underscores",
            )
        }
        if (value.length > ArsenalLimits.MAX_CONFIG_VALUE_CHARS) {
            return@synchronized ArsenalIssue(ArsenalIssueCode.INVALID_CONFIG, "Configuration values are limited to ${ArsenalLimits.MAX_CONFIG_VALUE_CHARS} characters")
        }
        val cleanValue = sanitize(value).trim()
        if ('\u0000' in value || cleanValue.length > ArsenalLimits.MAX_CONFIG_VALUE_CHARS) {
            return@synchronized ArsenalIssue(ArsenalIssueCode.INVALID_CONFIG, "Configuration values reject control characters")
        }
        val state = loadState().toMutableMap()
        val current = state[id] ?: SkillState()
        val config = LinkedHashMap(current.config)
        if (cleanValue.isEmpty()) config.remove(cleanKey) else config[cleanKey] = cleanValue
        if (config.size > ArsenalLimits.MAX_CONFIG_KEYS) {
            return@synchronized ArsenalIssue(ArsenalIssueCode.INVALID_CONFIG, "At most ${ArsenalLimits.MAX_CONFIG_KEYS} configuration entries are allowed")
        }
        state[id] = current.copy(config = Collections.unmodifiableMap(config))
        if (!persistState(state)) {
            ArsenalIssue(ArsenalIssueCode.STORAGE_ERROR, "Configuration could not be persisted")
        } else {
            null
        }
    }

    /** Removes an imported skill. Builtin declarations can be disabled but never deleted. */
    fun remove(id: String): Boolean = synchronized(lock) {
        if (id in ArsenalSkills.IDS) return@synchronized false
        val stored = loadImported().firstOrNull { it.manifest.id == id } ?: return@synchronized false
        val deleted = stored.file.delete()
        if (deleted) {
            importedCache = null
            val state = loadState().toMutableMap()
            if (state.remove(id) != null) persistState(state)
        }
        deleted
    }

    /** Relevance selection only. Call [validate] with the current host policy before executing a skill. */
    fun selectForGoal(goal: String, limit: Int = 3): List<ArsenalSkill> = ArsenalSelector.select(goal, list(), limit)

    fun promptBlock(goal: String, limit: Int = 3): String = ArsenalPrompt.render(selectForGoal(goal, limit))

    /**
     * Revalidates an installed skill against explicit host authority. Unknown IDs return INVALID_ID.
     * The default policy is the catalog's capability ceiling, not a runtime grant: execution callers
     * must pass the live host policy.
     */
    fun validate(id: String, policy: ArsenalHostPolicy = ArsenalHostPolicy.trustedBuiltins()): SkillValidationResult {
        val skill = find(id) ?: return SkillValidationResult(
            null,
            listOf(SkillValidationIssue(SkillIssueCode.INVALID_ID, "No Arsenal skill with this identifier")),
        )
        val context = policy.toContext()
        return if (skill.trusted && id in ArsenalSkills.IDS) {
            SkillValidator.validateBuiltin(skill.manifest, context)
        } else {
            SkillValidator.validateUntrusted(skill.manifest, context)
        }
    }

    /** Appends one bounded execution record and trims the file to the newest [ArsenalLimits.MAX_LOGS]. */
    fun recordExecution(
        skillId: String,
        objectiveId: String,
        success: Boolean,
        detail: String,
        evidence: List<String> = emptyList(),
    ): Boolean = synchronized(lock) {
        val cleanId = sanitize(skillId).trim()
        if (cleanId.isEmpty() || cleanId.length > 120) return@synchronized false
        val entry = ArsenalLogEntry(
            skillId = cleanId,
            objectiveId = sanitize(objectiveId).trim().take(ArsenalLimits.MAX_OBJECTIVE_CHARS),
            timestamp = clock(),
            success = success,
            detail = sanitize(detail).trim().take(ArsenalLimits.MAX_LOG_DETAIL_CHARS),
            evidence = evidence.asSequence()
                .map { sanitize(it).trim() }
                .filter { it.isNotEmpty() }
                .map { it.take(ArsenalLimits.MAX_LOG_EVIDENCE_CHARS) }
                .take(ArsenalLimits.MAX_LOG_EVIDENCE)
                .toList(),
        )
        return@synchronized try {
            val lines = readLogLines().toMutableList()
            lines += entry.toJson().toString()
            val bounded = if (lines.size > ArsenalLimits.MAX_LOGS) lines.takeLast(ArsenalLimits.MAX_LOGS) else lines
            writeAtomically(logFile, bounded.joinToString(separator = "\n", postfix = "\n"))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Newest-first bounded history; malformed lines are skipped instead of failing the read. */
    fun recentLogs(limit: Int = 20): List<ArsenalLogEntry> = synchronized(lock) {
        if (limit <= 0) return@synchronized emptyList()
        val entries = readLogLines().mapNotNull { parseLog(it) }
        entries.takeLast(limit.coerceAtMost(ArsenalLimits.MAX_LOGS)).reversed()
    }

    private fun loadImported(): List<StoredSkill> {
        importedCache?.let { return it }
        val directory = skillsDirectory
        val files = if (directory.isDirectory) {
            directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<StoredSkill>()
        for (file in files) {
            if (result.size >= ArsenalLimits.MAX_SKILLS) break
            if (file.length() > ArsenalLimits.MAX_IMPORT_CHARS) continue
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (text.length > ArsenalLimits.MAX_IMPORT_CHARS) continue
            val parsed = SkillManifestParser.parse(text)
            if (!parsed.isValid) continue
            val manifest = parsed.manifest ?: continue
            if (manifest.id in ArsenalSkills.RESERVED_IDS || !seen.add(manifest.id)) continue
            if (!SkillValidator.validateUntrusted(manifest, ArsenalHostPolicy.untrustedImport().toContext()).isValid) continue
            result += StoredSkill(file, manifest)
        }
        importedCache = result
        return result
    }

    private fun loadState(): Map<String, SkillState> {
        val file = stateFile
        if (!file.isFile || file.length() > ArsenalLimits.MAX_STATE_BYTES) return emptyMap()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        return try {
            val skills = JSONObject(text).optJSONObject("skills") ?: return emptyMap()
            val result = linkedMapOf<String, SkillState>()
            val ids = skills.keys()
            while (ids.hasNext() && result.size < ArsenalLimits.MAX_SKILLS + 32) {
                val id = ids.next()
                if (id.isBlank() || id.length > 120) continue
                val entry = skills.optJSONObject(id) ?: continue
                val config = linkedMapOf<String, String>()
                entry.optJSONObject("config")?.let { values ->
                    val keys = values.keys()
                    while (keys.hasNext() && config.size < ArsenalLimits.MAX_CONFIG_KEYS) {
                        val key = keys.next()
                        val value = values.optString(key)
                        if (CONFIG_KEY.matches(key) && value.isNotEmpty() &&
                            value.length <= ArsenalLimits.MAX_CONFIG_VALUE_CHARS && '\u0000' !in value
                        ) {
                            config[key] = value
                        }
                    }
                }
                result[id] = SkillState(entry.optBoolean("enabled", true), Collections.unmodifiableMap(config))
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistState(state: Map<String, SkillState>): Boolean = try {
        val skills = JSONObject()
        for ((id, entry) in state.toSortedMap()) {
            if (id.isBlank() || id.length > 120) continue
            skills.put(
                id,
                JSONObject().put("enabled", entry.enabled).put("config", JSONObject(entry.config.toSortedMap())),
            )
        }
        writeAtomically(stateFile, JSONObject().put("version", 1).put("skills", skills).toString())
        true
    } catch (_: Exception) {
        false
    }

    private fun readLogLines(): List<String> {
        val file = logFile
        if (!file.isFile || file.length() == 0L || file.length() > ArsenalLimits.MAX_LOG_BYTES) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return text.lineSequence().filter { it.isNotBlank() }.toList()
    }

    private fun parseLog(line: String): ArsenalLogEntry? = try {
        val json = JSONObject(line)
        val skillId = json.optString("skillId").take(120)
        if (skillId.isEmpty()) null else ArsenalLogEntry(
            skillId = skillId,
            objectiveId = json.optString("objectiveId").take(ArsenalLimits.MAX_OBJECTIVE_CHARS),
            timestamp = json.optLong("timestamp", 0L),
            success = json.optBoolean("success", false),
            detail = json.optString("detail").take(ArsenalLimits.MAX_LOG_DETAIL_CHARS),
            evidence = json.optJSONArray("evidence")?.let { array ->
                (0 until minOf(array.length(), ArsenalLimits.MAX_LOG_EVIDENCE)).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotEmpty() }?.take(ArsenalLimits.MAX_LOG_EVIDENCE_CHARS)
                }
            } ?: emptyList(),
        )
    } catch (_: Exception) {
        null
    }

    private fun manifestJson(manifest: SkillManifest): JSONObject {
        val constraints = manifest.constraints
        val failure = manifest.failureHandling
        val compatibility = manifest.compatibility
        return JSONObject()
            .put("id", manifest.id)
            .put("version", manifest.version)
            .put("description", manifest.description)
            .put("tools", JSONArray(manifest.tools.sorted()))
            .put("requiredPermissions", JSONArray(manifest.requiredPermissions.sortedBy { it.name }.map { it.name }))
            .put("preconditions", JSONArray(manifest.preconditions.sortedBy { it.name }.map { it.name }))
            .put(
                "constraints",
                JSONObject()
                    .put("workspaceOnly", constraints.workspaceOnly)
                    .put("allowNetwork", constraints.allowNetwork)
                    .put("allowDestructive", constraints.allowDestructive)
                    .put("allowAutoCommit", constraints.allowAutoCommit)
                    .put("requireWriteApproval", constraints.requireWriteApproval)
                    .put("maxSteps", constraints.maxSteps)
                    .put("maxOutputChars", constraints.maxOutputChars),
            )
            .put(
                "failureHandling",
                JSONObject()
                    .put("mode", failure.mode.name)
                    .put("maxRetries", failure.maxRetries)
                    .put("preserveEvidence", failure.preserveEvidence),
            )
            .put(
                "compatibility",
                JSONObject()
                    .put("manifestVersion", compatibility.manifestVersion)
                    .put("minHostVersion", compatibility.minHostVersion)
                    .put("maxHostMajor", compatibility.maxHostMajor),
            )
            .put("instructions", JSONArray(manifest.instructions))
    }

    private fun fileName(manifest: SkillManifest): String = "${manifest.id}@${manifest.version}.json"

    private fun writeAtomically(file: File, text: String) {
        val parent = file.parentFile ?: throw IOException("Arsenal file has no parent directory: ${file.name}")
        parent.mkdirs()
        val temporary = File.createTempFile(".${file.name.take(64)}.", ".tmp", parent)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            if (temporary.renameTo(file)) return
            file.delete()
            if (temporary.renameTo(file)) return
            temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
    }

    private fun sanitize(value: String): String = buildString(value.length.coerceAtMost(1_024)) {
        for (character in value.take(1_024)) append(if (character.isWhitespace() || character.code < 0x20) ' ' else character)
    }

    private fun failure(code: ArsenalIssueCode, message: String): ArsenalImportResult =
        ArsenalImportResult(null, listOf(ArsenalIssue(code, message)))

    companion object {
        const val SKILLS_DIRECTORY = "skills"
        const val STATE_FILE_NAME = "state.json"
        const val LOG_FILE_NAME = "logs.jsonl"
        private val CONFIG_KEY = Regex("[a-z0-9][a-z0-9._-]{0,47}")
    }
}

/**
 * Android entry point. It owns no logic beyond resolving `filesDir/arsenal`; every rule lives in the
 * pure [ArsenalRegistry] and [ArsenalSelector] so JVM tests run without Robolectric.
 */
class ArsenalRepository(context: Context) {
    private val appContext = context.applicationContext
    private val registry = ArsenalRegistry(File(appContext.filesDir, DIRECTORY))

    val directory: File get() = registry.root
    val skillsDirectory: File get() = registry.skillsDirectory
    val logFile: File get() = registry.logFile

    fun list(): List<ArsenalSkill> = registry.list()

    fun find(id: String): ArsenalSkill? = registry.find(id)

    fun importSkill(json: String): ArsenalImportResult = registry.importSkill(json)

    fun setEnabled(id: String, enabled: Boolean): Boolean = registry.setEnabled(id, enabled)

    fun configure(id: String, key: String, value: String): ArsenalIssue? = registry.configure(id, key, value)

    fun remove(id: String): Boolean = registry.remove(id)

    fun selectForGoal(goal: String, limit: Int = 3): List<ArsenalSkill> = registry.selectForGoal(goal, limit)

    fun promptBlock(goal: String, limit: Int = 3): String = registry.promptBlock(goal, limit)

    fun validate(id: String, policy: ArsenalHostPolicy = ArsenalHostPolicy.trustedBuiltins()): SkillValidationResult =
        registry.validate(id, policy)

    fun recordExecution(
        skillId: String,
        objectiveId: String,
        success: Boolean,
        detail: String,
        evidence: List<String> = emptyList(),
    ): Boolean = registry.recordExecution(skillId, objectiveId, success, detail, evidence)

    fun recentLogs(limit: Int = 20): List<ArsenalLogEntry> = registry.recentLogs(limit)

    companion object {
        const val DIRECTORY = "arsenal"
    }
}

package com.nyx.ai.context

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Durable project knowledge categories. */
enum class MemoryType {
    DECISION,
    BUILD_INSTRUCTION,
    TASK_OUTCOME,
    FAILURE,
    VERIFIED_RESULT,
    LESSON,
}

/**
 * A pointer to durable evidence. A reference is usable only when it carries a complete
 * SHA-256 and either a stored context record id or a project-relative path.
 */
data class MemoryEvidenceRef(
    val recordId: String? = null,
    val path: String? = null,
    val sha256: String? = null,
) {
    val isUsable: Boolean
        get() = sha256?.let { SHA256_PATTERN.matches(it) } == true &&
            (!recordId.isNullOrBlank() || !path.isNullOrBlank())

    fun toJson(): JSONObject = JSONObject()
        .put("recordId", recordId ?: JSONObject.NULL)
        .put("path", path ?: JSONObject.NULL)
        .put("sha256", sha256 ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): MemoryEvidenceRef? {
            val recordId = if (json.isNull("recordId")) null else
                json.optString("recordId").trim().takeIf { it.isNotEmpty() }
            val path = if (json.isNull("path")) null else
                json.optString("path").trim().takeIf { it.isNotEmpty() }
            val sha256 = if (json.isNull("sha256")) null else
                json.optString("sha256").trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
            return MemoryEvidenceRef(recordId, path, sha256).takeIf { it.isUsable }
        }
    }
}

/** One durable, evidence-linked project memory entry. */
data class MemoryEntry(
    val id: String,
    val type: MemoryType,
    val title: String,
    val summary: String,
    val evidenceRefs: List<MemoryEvidenceRef> = emptyList(),
    val objectiveId: String? = null,
    val createdAt: Long,
    val confidence: Double,
    val tags: List<String> = emptyList(),
) {
    val hasUsableEvidence: Boolean
        get() = evidenceRefs.isNotEmpty() && evidenceRefs.all { it.isUsable }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("title", title)
        .put("summary", summary)
        .put("evidenceRefs", JSONArray().apply { evidenceRefs.forEach { put(it.toJson()) } })
        .put("objectiveId", objectiveId ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("confidence", confidence)
        .put("tags", JSONArray().apply { tags.forEach { put(it) } })
}

/**
 * Persistent, workspace-scoped project memory backed by one JSON document per workspace.
 *
 * The workspace key is the SHA-256 of the normalized canonical workspace path, so two
 * checkouts never share memory. Writes use a temporary sibling plus an atomic rename.
 * A corrupt or foreign document is quarantined and treated as empty rather than
 * preventing the project from opening.
 */
class ProjectMemory(
    private val root: File,
    storeDirectory: File? = null,
) {
    private val workspacePath: String = normalizedWorkspacePath(root)
    val workspaceKey: String = sha256Hex(workspacePath)
    private val storeDirectory: File = (storeDirectory ?: File(root, ".nyx/project-memory")).absoluteFile
    val documentFile: File = File(this.storeDirectory, "$workspaceKey.json")

    private val lock = Any()
    private var loaded = false
    private val entries = mutableListOf<MemoryEntry>()

    /** All entries, oldest first. Intended for diagnostics; prompts must use [buildPromptBlock]. */
    fun all(): List<MemoryEntry> = synchronized(lock) {
        loadIfNeeded()
        entries.sortedWith(compareBy<MemoryEntry> { it.createdAt }.thenBy { it.id })
    }

    fun lessonsFor(goal: String): List<MemoryEntry> = synchronized(lock) {
        loadIfNeeded()
        val words = keywords(goal)
        entries.asSequence()
            .filter { it.type == MemoryType.LESSON }
            .map { it to overlap(words, entryKeywords(it)) }
            .filter { (_, score) -> words.isEmpty() || score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.createdAt }
                    .thenBy { it.first.id },
            )
            .take(MAX_VIEW_ENTRIES)
            .map { it.first }
            .toList()
    }

    /** Only verified results whose evidence is still shaped like real evidence. */
    fun verifiedResults(): List<MemoryEntry> = synchronized(lock) {
        loadIfNeeded()
        entries.asSequence()
            .filter { it.type == MemoryType.VERIFIED_RESULT && it.hasUsableEvidence }
            .sortedWith(compareByDescending<MemoryEntry> { it.createdAt }.thenBy { it.id })
            .take(MAX_VIEW_ENTRIES)
            .toList()
    }

    fun buildInstructions(): List<MemoryEntry> = synchronized(lock) {
        loadIfNeeded()
        entries.asSequence()
            .filter { it.type == MemoryType.BUILD_INSTRUCTION }
            .sortedWith(compareByDescending<MemoryEntry> { it.createdAt }.thenBy { it.id })
            .take(MAX_VIEW_ENTRIES)
            .toList()
    }

    /**
     * Records an entry after normalizing text and evidence. Near-identical entries of the
     * same type are merged instead of appended. A [MemoryType.VERIFIED_RESULT] without
     * complete evidence is downgraded to [MemoryType.TASK_OUTCOME].
     */
    fun record(
        type: MemoryType,
        title: String,
        summary: String,
        evidenceRefs: List<MemoryEvidenceRef> = emptyList(),
        objectiveId: String? = null,
        confidence: Double = if (type == MemoryType.VERIFIED_RESULT) 0.8 else 0.6,
        tags: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): MemoryEntry = synchronized(lock) {
        loadIfNeeded()
        val safeTitle = singleLine(title).take(MAX_TITLE_CHARS)
        require(safeTitle.isNotEmpty()) { "Memory title must not be blank" }
        val safeSummary = multiline(summary).take(MAX_SUMMARY_CHARS)
        require(safeSummary.isNotEmpty()) { "Memory summary must not be blank" }
        val refs = sanitizeEvidence(evidenceRefs)
        val callerEvidenceIsComplete = evidenceRefs.isNotEmpty() &&
            evidenceRefs.all { it.isUsable } && hasCompleteEvidence(refs)
        val effectiveType = if (type == MemoryType.VERIFIED_RESULT && !callerEvidenceIsComplete) {
            MemoryType.TASK_OUTCOME
        } else {
            type
        }
        val safeObjective = objectiveId?.trim()?.take(MAX_OBJECTIVE_CHARS)?.takeIf { it.isNotEmpty() }
        val safeTags = sanitizeTags(tags)
        val effectiveConfidence = confidenceFor(effectiveType, confidence)
        val candidate = MemoryEntry(
            id = UUID.randomUUID().toString(),
            type = effectiveType,
            title = safeTitle,
            summary = safeSummary,
            evidenceRefs = refs,
            objectiveId = safeObjective,
            createdAt = now.coerceAtLeast(0L),
            confidence = effectiveConfidence,
            tags = safeTags,
        )
        val duplicate = entries.firstOrNull { it.type == effectiveType && isNearDuplicate(it, candidate) }
        if (duplicate != null) {
            val merged = duplicate.copy(
                evidenceRefs = (duplicate.evidenceRefs + refs).distinctBy(::evidenceKey).take(MAX_EVIDENCE_REFS),
                objectiveId = duplicate.objectiveId ?: safeObjective,
                confidence = maxOf(duplicate.confidence, effectiveConfidence),
                tags = (duplicate.tags + safeTags).distinct().take(MAX_TAGS),
            )
            entries[entries.indexOf(duplicate)] = merged
            persist()
            merged
        } else {
            entries += candidate
            evictOverflow(candidate.id)
            persist()
            candidate
        }
    }

    /**
     * Records a task result. Only a successful result with usable evidence becomes a
     * [MemoryType.VERIFIED_RESULT]; everything else stays an outcome or a failure.
     */
    fun recordOutcome(
        title: String,
        summary: String,
        success: Boolean,
        evidenceRefs: List<MemoryEvidenceRef> = emptyList(),
        objectiveId: String? = null,
        confidence: Double = if (success) 0.6 else 0.5,
        tags: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): MemoryEntry {
        val refs = sanitizeEvidence(evidenceRefs)
        val verified = success && evidenceRefs.isNotEmpty() &&
            evidenceRefs.all { it.isUsable } && hasCompleteEvidence(refs)
        val type = when {
            verified -> MemoryType.VERIFIED_RESULT
            success -> MemoryType.TASK_OUTCOME
            else -> MemoryType.FAILURE
        }
        val boundedConfidence = if (verified) {
            maxOf(confidence, 0.8).coerceAtMost(1.0)
        } else {
            confidence.coerceIn(0.0, 0.9)
        }
        return record(type, title, summary, refs, objectiveId, boundedConfidence, tags, now)
    }

    /**
     * Builds a bounded advisory prompt section. Ranking is deterministic: usable verified
     * results first, then keyword overlap with [goal], type value, recency, and confidence.
     * Irrelevant entries, failures, low-confidence entries, and stale unverified claims are
     * omitted. The returned text is never longer than [maxChars].
     */
    fun buildPromptBlock(goal: String, maxChars: Int = DEFAULT_PROMPT_CHARS): String = synchronized(lock) {
        if (maxChars <= 0) {
            ""
        } else {
            loadIfNeeded()
            val now = System.currentTimeMillis()
            val goalWords = keywords(goal)
            val ranked = entries.asSequence()
                .filter { it.type != MemoryType.FAILURE }
                .filter { it.confidence >= MIN_PROMPT_CONFIDENCE }
                .filter { !isStale(it, now) }
                .map { entry ->
                    val matches = overlap(goalWords, entryKeywords(entry))
                    PromptCandidate(entry, matches, promptScore(entry, matches, now))
                }
                .filter { goalWords.isEmpty() || it.overlap > 0 }
                .sortedWith(
                    compareByDescending<PromptCandidate> { it.score }
                        .thenByDescending { it.entry.createdAt }
                        .thenBy { it.entry.id },
                )
                .take(MAX_PROMPT_ENTRIES)
                .toList()
            if (maxChars <= EVIDENCE_HEADER.length) {
                EVIDENCE_HEADER.take(maxChars)
            } else {
                val output = StringBuilder(minOf(maxChars, 8_192))
                output.append(EVIDENCE_HEADER)
                for (candidate in ranked) {
                    val block = "\n\n" + formatMemoryEntry(candidate.entry)
                    if (output.length + block.length <= maxChars) {
                        output.append(block)
                    } else {
                        val remaining = maxChars - output.length
                        if (remaining > MIN_PROMPT_APPEND) output.append(block.take(remaining))
                        break
                    }
                }
                if (output.length > maxChars) output.toString().take(maxChars) else output.toString()
            }
        }
    }

    private fun formatMemoryEntry(entry: MemoryEntry): String = buildString {
        append('[').append(entry.type.name).append("] ").append(entry.title)
        append(" (confidence=").append(formatConfidence(entry.confidence))
        if (entry.tags.isNotEmpty()) append(", tags=").append(entry.tags.take(4).joinToString(","))
        append(")\n")
        append(entry.summary.take(MAX_PROMPT_SUMMARY_CHARS)).append('\n')
        if (entry.type == MemoryType.VERIFIED_RESULT && entry.hasUsableEvidence) {
            append("EVIDENCE: ")
            append(entry.evidenceRefs.filter { it.isUsable }.take(MAX_PROMPT_EVIDENCE).joinToString("; ") { ref ->
                listOfNotNull(
                    ref.recordId?.let { "record=$it" },
                    ref.path?.let { "path=$it" },
                    ref.sha256?.let { "sha256=$it" },
                ).joinToString(" ")
            })
        } else {
            append("EVIDENCE: none recorded - UNVERIFIED memory, not proof. Verify before relying on it.")
        }
    }

    private fun promptScore(entry: MemoryEntry, keywordOverlap: Int, now: Long): Int {
        val verifiedBonus = if (entry.type == MemoryType.VERIFIED_RESULT) 1_000 else 0
        val typeWeight = when (entry.type) {
            MemoryType.VERIFIED_RESULT -> 60
            MemoryType.DECISION -> 35
            MemoryType.BUILD_INSTRUCTION -> 30
            MemoryType.LESSON -> 25
            MemoryType.TASK_OUTCOME -> 10
            MemoryType.FAILURE -> 0
        }
        val age = (now - entry.createdAt).coerceAtLeast(0L)
        val recency = when {
            age <= ONE_HOUR_MILLIS -> 8
            age <= ONE_DAY_MILLIS -> 6
            age <= ONE_WEEK_MILLIS -> 4
            age <= STALE_AFTER_MILLIS -> 2
            else -> 0
        }
        val confidence = (entry.confidence.coerceIn(0.0, 1.0) * 10).toInt()
        return verifiedBonus + keywordOverlap * 25 + typeWeight + recency + confidence
    }

    private fun isStale(entry: MemoryEntry, now: Long): Boolean {
        if (entry.type == MemoryType.VERIFIED_RESULT || entry.type == MemoryType.BUILD_INSTRUCTION) return false
        return now - entry.createdAt > STALE_AFTER_MILLIS
    }

    private fun isNearDuplicate(existing: MemoryEntry, candidate: MemoryEntry): Boolean {
        if (existing.title.trim().equals(candidate.title.trim(), ignoreCase = true)) return true
        val left = comparisonTokens(existing)
        val right = comparisonTokens(candidate)
        if (left.isEmpty() || right.isEmpty()) return false
        val intersection = left.count { it in right }
        val union = left.size + right.size - intersection
        return union > 0 && intersection.toDouble() / union >= DEDUPE_SIMILARITY
    }

    private fun comparisonTokens(entry: MemoryEntry): Set<String> =
        Regex("[a-z0-9]{2,}").findAll("${entry.title} ${entry.summary}".lowercase(Locale.ROOT))
            .map { it.value }
            .toSet()

    private fun evictOverflow(protectedId: String) {
        while (entries.size > MAX_ENTRIES) {
            val victim = entries.asSequence()
                .filter { it.id != protectedId }
                .minWithOrNull(
                    compareBy<MemoryEntry> { retentionScore(it) }
                        .thenBy { it.createdAt }
                        .thenBy { it.id },
                ) ?: entries.first()
            entries.remove(victim)
        }
    }

    private fun retentionScore(entry: MemoryEntry): Int = when (entry.type) {
        MemoryType.VERIFIED_RESULT -> 100
        MemoryType.DECISION -> 70
        MemoryType.BUILD_INSTRUCTION -> 65
        MemoryType.LESSON -> 60
        MemoryType.TASK_OUTCOME -> 20
        MemoryType.FAILURE -> 10
    } + (entry.confidence.coerceIn(0.0, 1.0) * 10).toInt()

    private fun sanitizeEvidence(refs: List<MemoryEvidenceRef>): List<MemoryEvidenceRef> = refs.asSequence()
        .mapNotNull { ref ->
            val recordId = ref.recordId?.trim()?.take(MAX_REF_FIELD_CHARS)?.takeIf { it.isNotEmpty() }
            val path = ref.path?.trim()?.take(MAX_REF_FIELD_CHARS)?.takeIf { it.isNotEmpty() }
            val sha256 = ref.sha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { SHA256_PATTERN.matches(it) }
            MemoryEvidenceRef(recordId, path, sha256).takeIf { it.isUsable }
        }
        .distinctBy(::evidenceKey)
        .take(MAX_EVIDENCE_REFS)
        .toList()

    private fun hasCompleteEvidence(refs: List<MemoryEvidenceRef>): Boolean =
        refs.isNotEmpty() && refs.all { it.isUsable }

    private fun evidenceKey(ref: MemoryEvidenceRef): String =
        "${ref.recordId.orEmpty()}\u0000${ref.path.orEmpty()}\u0000${ref.sha256.orEmpty()}"

    private fun sanitizeTags(tags: List<String>): List<String> = tags.asSequence()
        .map { singleLine(it).take(MAX_TAG_CHARS) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_TAGS)
        .toList()

    private fun confidenceFor(type: MemoryType, value: Double): Double {
        val safe = if (value.isNaN() || value.isInfinite()) 0.5 else value
        return when (type) {
            MemoryType.VERIFIED_RESULT -> safe.coerceIn(0.8, 1.0)
            MemoryType.FAILURE -> safe.coerceIn(0.0, 0.9)
            else -> safe.coerceIn(0.0, 0.95)
        }
    }

    private fun entryKeywords(entry: MemoryEntry): Set<String> =
        keywords("${entry.title} ${entry.summary} ${entry.tags.joinToString(" ")}")

    private fun keywords(value: String): Set<String> = Regex("[A-Za-z0-9_]{2,}")
        .findAll(value)
        .flatMap { match ->
            val token = match.value
            val parts = token.split(Regex("(?<=[a-z0-9])(?=[A-Z])|_")).filter { it.length >= 2 }
            (parts + token).map { it.lowercase(Locale.ROOT) }
        }
        .filterNot { it in STOP_WORDS }
        .toSet()

    private fun overlap(left: Set<String>, right: Set<String>): Int =
        if (left.isEmpty() || right.isEmpty()) 0 else left.count { it in right }

    private fun singleLine(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun multiline(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        .replace(Regex("[\\t ]+\\n"), "\n")
        .replace(Regex("\\n[ \\t]+"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun formatConfidence(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value.coerceIn(0.0, 1.0))

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!documentFile.isFile) return
        val text = try {
            ContextFiles.readText(documentFile, MAX_DOCUMENT_CHARS + 1)
        } catch (_: Exception) {
            return
        }
        if (text.length > MAX_DOCUMENT_CHARS) {
            quarantine("oversized")
            return
        }
        val document = try {
            JSONObject(text)
        } catch (_: Exception) {
            quarantine("malformed")
            return
        }
        if (document.optInt("version", -1) != DOCUMENT_VERSION ||
            document.optString("workspaceKey") != workspaceKey
        ) {
            quarantine("foreign")
            return
        }
        val array = document.optJSONArray("entries") ?: JSONArray()
        val limit = minOf(array.length(), MAX_ENTRIES)
        for (index in 0 until limit) {
            val json = array.optJSONObject(index) ?: continue
            decodeEntry(json)?.let { entries += it }
        }
        val unique = entries.distinctBy { it.id }.take(MAX_ENTRIES)
        entries.clear()
        entries += unique
    }

    private fun decodeEntry(json: JSONObject): MemoryEntry? {
        val id = json.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
        val type = try {
            MemoryType.valueOf(json.optString("type").trim().uppercase(Locale.ROOT))
        } catch (_: Exception) {
            return null
        }
        val title = singleLine(json.optString("title")).take(MAX_TITLE_CHARS).takeIf { it.isNotEmpty() } ?: return null
        val summary = multiline(json.optString("summary")).take(MAX_SUMMARY_CHARS).takeIf { it.isNotEmpty() } ?: return null
        val evidenceJson = json.optJSONArray("evidenceRefs") ?: JSONArray()
        val refs = (0 until minOf(evidenceJson.length(), MAX_EVIDENCE_REFS)).mapNotNull { index ->
            evidenceJson.optJSONObject(index)?.let { MemoryEvidenceRef.fromJson(it) }
        }.take(MAX_EVIDENCE_REFS)
        val objectiveId = if (json.isNull("objectiveId")) null else
            json.optString("objectiveId").trim().take(MAX_OBJECTIVE_CHARS).takeIf { it.isNotEmpty() }
        val createdAt = json.optLong("createdAt", 0L).coerceAtLeast(0L)
        val confidence = confidenceFor(type, json.optDouble("confidence", 0.5))
        val tagsJson = json.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until minOf(tagsJson.length(), MAX_TAGS)).mapNotNull { index ->
            tagsJson.optString(index).takeIf { it.isNotBlank() }
        }.map { singleLine(it).take(MAX_TAG_CHARS) }.distinct()
        val effectiveType = if (type == MemoryType.VERIFIED_RESULT && !hasCompleteEvidence(refs)) {
            MemoryType.TASK_OUTCOME
        } else {
            type
        }
        return MemoryEntry(id, effectiveType, title, summary, refs, objectiveId, createdAt, confidence, tags)
    }

    private fun persist() {
        val parent = documentFile.parentFile ?: throw IllegalStateException("Memory document has no parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("Unable to create memory directory")
        }
        val document = JSONObject()
            .put("version", DOCUMENT_VERSION)
            .put("workspaceKey", workspaceKey)
            .put("workspacePath", workspacePath)
            .put("updatedAt", System.currentTimeMillis())
            .put("entries", JSONArray().apply {
                entries.sortedWith(compareBy<MemoryEntry> { it.createdAt }.thenBy { it.id })
                    .forEach { put(it.toJson()) }
            })
        val temp = File.createTempFile(".nyx-memory-", ".tmp", parent)
        try {
            Files.write(temp.toPath(), document.toString().toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(
                    temp.toPath(),
                    documentFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), documentFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    /** Keeps the damaged bytes for inspection but never lets them block a fresh document. */
    private fun quarantine(reason: String) {
        val parent = documentFile.parentFile ?: return
        val suffix = reason.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "corrupt" }
        val target = File(parent, "${documentFile.name}.$suffix-${System.currentTimeMillis()}")
        try {
            if (!documentFile.renameTo(target)) {
                documentFile.copyTo(target, overwrite = false)
                documentFile.delete()
            }
        } catch (_: Exception) {
            // The in-memory document still starts empty; a later persist replaces the file.
        }
    }

    private companion object {
        const val DOCUMENT_VERSION = 2
        const val MAX_ENTRIES = 200
        const val MAX_TITLE_CHARS = 120
        const val MAX_SUMMARY_CHARS = 1_200
        const val MAX_OBJECTIVE_CHARS = 128
        const val MAX_TAGS = 12
        const val MAX_TAG_CHARS = 32
        const val MAX_EVIDENCE_REFS = 8
        const val MAX_REF_FIELD_CHARS = 256
        const val MAX_DOCUMENT_CHARS = 2_000_000
        const val MAX_VIEW_ENTRIES = 50
        const val MAX_PROMPT_ENTRIES = 10
        const val MAX_PROMPT_SUMMARY_CHARS = 600
        const val MAX_PROMPT_EVIDENCE = 3
        const val DEFAULT_PROMPT_CHARS = 4_000
        const val MIN_PROMPT_APPEND = 24
        const val MIN_PROMPT_CONFIDENCE = 0.2
        const val DEDUPE_SIMILARITY = 0.85
        const val ONE_HOUR_MILLIS = 3_600_000L
        const val ONE_DAY_MILLIS = 86_400_000L
        const val ONE_WEEK_MILLIS = 604_800_000L
        const val STALE_AFTER_MILLIS = 7_776_000_000L
        const val EVIDENCE_HEADER =
            "PROJECT MEMORY (advisory, evidence-linked). EVIDENCE IS REQUIRED: cite record id, path, and sha256. " +
                "Unverified memory is NOT proof; verify every claim before relying on it."
    }
}

private data class PromptCandidate(val entry: MemoryEntry, val overlap: Int, val score: Int)

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

private val STOP_WORDS = setOf(
    "about", "after", "again", "also", "and", "are", "because", "before", "being", "but",
    "can", "could", "does", "for", "from", "have", "into", "its", "more", "not", "only",
    "our", "over", "should", "that", "the", "their", "then", "there", "these", "they",
    "this", "those", "through", "under", "using", "was", "were", "what", "when", "where",
    "which", "while", "with", "would", "your", "goal", "task", "project",
)

private fun normalizedWorkspacePath(root: File): String {
    val path = try {
        root.canonicalPath
    } catch (_: Exception) {
        root.absolutePath
    }
    val normalized = path.replace(File.separatorChar, '/').replace(Regex("/+"), "/").trimEnd('/')
    return normalized.ifEmpty { "/" }
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val number = byte.toInt() and 0xFF
            append(digits[number ushr 4]).append(digits[number and 0x0F])
        }
    }
}

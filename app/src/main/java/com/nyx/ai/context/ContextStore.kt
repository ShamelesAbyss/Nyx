package com.nyx.ai.context

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Original records are retained; references identify their full, immutable evidence bytes. */
data class ContextEvidenceReference(val recordId: String, val filePath: String, val sha256: String)

/** A durable text record associated with an objective and, optionally, one of its tasks. */
data class ContextRecord(
    val id: String,
    val objectiveId: String,
    val taskId: String?,
    val kind: String,
    val title: String,
    val filePath: String,
    val summary: String,
    val createdAt: Long,
    val evidence: List<ContextEvidenceReference> = emptyList(),
    val sourceReferences: List<ProjectSourceReference> = emptyList(),
    val contentSha256: String? = null,
)

/** File-backed context storage. All persisted paths are confined to [root]. */
class ContextStore(private val root: File) {
    fun write(objectiveId: String, taskId: String?, kind: String, title: String, text: String): ContextRecord =
        writeRecord(objectiveId, taskId, kind, title, text)

    /** Saves a durable copy of the observed text and its project-content identity. */
    fun writeObservation(
        objectiveId: String, taskId: String?, title: String, text: String, source: ProjectSourceReference,
    ): ContextRecord = writeRecord(objectiveId, taskId, "observation", title, text, sources = listOf(source))

    /** Deterministic caller-supplied summary, never an LLM call and never a deletion of originals. */
    fun compact(
        objectiveId: String,
        taskId: String?,
        title: String,
        summary: String,
        recordIds: List<String>,
        budgetChars: Int = 2_000,
    ): ContextRecord = compactRecord(objectiveId, taskId, "summary", title, summary, recordIds, budgetChars)

    /** Checkpoints are ordinary durable records and survive reopening the store. */
    fun checkpoint(
        objectiveId: String,
        taskId: String?,
        summary: String,
        recordIds: List<String> = emptyList(),
        budgetChars: Int = 2_000,
    ): ContextRecord = compactRecord(objectiveId, taskId, "checkpoint", "Execution checkpoint", summary, recordIds, budgetChars)

    fun latestCheckpoint(objectiveId: String, taskId: String? = null): ContextRecord? =
        list(objectiveId).lastOrNull { it.kind == "checkpoint" && (taskId == null || it.taskId == taskId) }

    private fun compactRecord(
        objectiveId: String, taskId: String?, kind: String, title: String, summary: String,
        recordIds: List<String>, budgetChars: Int,
    ): ContextRecord {
        require(budgetChars > 0 && summary.isNotBlank()) { "A checkpoint or summary needs a positive text budget" }
        require(recordIds.distinct().size <= 64) { "Too many evidence records" }
        val byId = list(objectiveId).associateBy { it.id }
        val originals = recordIds.distinct().map { id ->
            byId[id] ?: throw IllegalArgumentException("Evidence record is missing from this objective: $id")
        }
        val evidence = originals.flatMap { record ->
            val file = confined(File(root, record.filePath))
            val hash = ContextFiles.sha256(file) ?: throw IllegalStateException("Evidence is unreadable")
            require(record.contentSha256 == null || record.contentSha256 == hash) { "Original evidence was changed" }
            require(record.evidence.all(::hasEvidence)) { "Original evidence is missing or changed" }
            listOf(ContextEvidenceReference(record.id, record.filePath, hash)) + record.evidence
        }.distinctBy { it.recordId }
        require(evidence.size <= 128) { "Too many nested evidence references" }
        return writeRecord(objectiveId, taskId, kind, title, summary.take(budgetChars), evidence,
            originals.flatMap { it.sourceReferences }.distinct())
    }

    private fun writeRecord(
        objectiveId: String, taskId: String?, kind: String, title: String, text: String,
        evidence: List<ContextEvidenceReference> = emptyList(), sources: List<ProjectSourceReference> = emptyList(),
    ): ContextRecord {
        val safeObjective = checkedSegment(objectiveId, "objectiveId")
        val safeKind = checkedSegment(kind, "kind")
        val safeTitle = sanitizeSegment(title).take(27).ifEmpty { "untitled" }
        val kindDir = confined(File(File(File(root, "objectives"), safeObjective), safeKind))
        if (!kindDir.exists() && !kindDir.mkdirs() && !kindDir.isDirectory) {
            throw IllegalStateException("Unable to create context directory")
        }
        val id = UUID.randomUUID().toString()
        val filename = "$id-$safeTitle.txt"
        val contentFile = confined(File(kindDir, filename))
        val metadataFile = confined(File(kindDir, "$filename.json"))
        val record = ContextRecord(id, objectiveId, taskId, kind, title, relativePath(contentFile),
            singleLine(text).take(300), System.currentTimeMillis(), evidence.toList(), sources.toList(), ContextFiles.sha256(text))
        val metadata = JSONObject()
            .put("id", record.id).put("objectiveId", record.objectiveId).put("taskId", record.taskId)
            .put("kind", record.kind).put("title", record.title).put("filePath", record.filePath)
            .put("summary", record.summary).put("createdAt", record.createdAt).put("contentSha256", record.contentSha256)
            .put("evidence", JSONArray().apply { record.evidence.forEach { reference ->
                put(JSONObject().put("recordId", reference.recordId).put("filePath", reference.filePath).put("sha256", reference.sha256))
            } })
            .put("sourceReferences", JSONArray().apply { record.sourceReferences.forEach { source ->
                put(JSONObject().put("scopeId", source.scopeId).put("relativePath", source.relativePath)
                    .put("sha256", source.sha256).put("modifiedAt", source.modifiedAt).put("sizeBytes", source.sizeBytes))
            } }).toString()
        require(metadata.length <= MAX_METADATA_CHARS) { "Context metadata exceeds its bounded sidecar budget" }
        atomicWrite(contentFile, text)
        try { atomicWrite(metadataFile, metadata) } catch (failure: Exception) { contentFile.delete(); throw failure }
        return record
    }

    /** Reads at most [maxChars] characters; invalid, missing, or out-of-root paths read as empty text. */
    fun read(record: ContextRecord, maxChars: Int = 8_000): String {
        if (maxChars <= 0) return ""
        return try {
            val candidate = confined(File(root, record.filePath))
            if (!candidate.isFile) "" else ContextFiles.readText(candidate, maxChars)
        } catch (_: Exception) { "" }
    }

    fun hasEvidence(reference: ContextEvidenceReference): Boolean = try {
        val file = confined(File(root, reference.filePath))
        file.isFile && reference.sha256.matches(Regex("[0-9a-f]{64}")) && ContextFiles.sha256(file) == reference.sha256
    } catch (_: Exception) { false }

    /** Explicit historical evidence remains readable even when its project source has changed. */
    fun readEvidence(reference: ContextEvidenceReference, maxChars: Int = 8_000): String = try {
        if (maxChars <= 0 || !hasEvidence(reference)) "" else {
            val result = ContextFiles.readText(confined(File(root, reference.filePath)), maxChars)
            if (hasEvidence(reference)) result else ""
        }
    } catch (_: Exception) { "" }

    /** Sourced observations fail closed unless the caller provides a current-content validator. */
    fun isUsable(record: ContextRecord, sourceValidator: ((ProjectSourceReference) -> Boolean)? = null): Boolean = try {
        val file = confined(File(root, record.filePath))
        file.isFile && (record.contentSha256 == null || ContextFiles.sha256(file) == record.contentSha256) &&
            record.evidence.all(::hasEvidence) && record.sourceReferences.all { sourceValidator?.invoke(it) == true }
    } catch (_: Exception) { false }

    fun list(objectiveId: String): List<ContextRecord> {
        val safeObjective = checkedSegment(objectiveId, "objectiveId")
        val objectiveDir = try { confined(File(File(root, "objectives"), safeObjective)) } catch (_: Exception) { return emptyList() }
        if (!objectiveDir.isDirectory) return emptyList()
        val records = mutableListOf<ContextRecord>()
        val metadataFiles = safeWalk(objectiveDir).filter { it.isFile && it.name.endsWith(".txt.json") }.sortedBy { relativePath(it) }
        for (metadataFile in metadataFiles) {
            try {
                val text = ContextFiles.readText(confined(metadataFile), MAX_METADATA_CHARS + 1)
                if (text.length > MAX_METADATA_CHARS) continue
                val json = JSONObject(text)
                val evidenceJson = json.optJSONArray("evidence") ?: JSONArray()
                val sourcesJson = json.optJSONArray("sourceReferences") ?: JSONArray()
                if (evidenceJson.length() > 128 || sourcesJson.length() > 128) continue
                val evidence = (0 until evidenceJson.length()).map { index ->
                    val value = evidenceJson.getJSONObject(index)
                    ContextEvidenceReference(value.getString("recordId"), value.getString("filePath"), value.getString("sha256"))
                }
                val sources = (0 until sourcesJson.length()).map { index ->
                    val value = sourcesJson.getJSONObject(index)
                    ProjectSourceReference(value.getString("scopeId"), value.getString("relativePath"), value.getString("sha256"),
                        value.getLong("modifiedAt"), value.getLong("sizeBytes"))
                }
                val record = ContextRecord(json.getString("id"), json.getString("objectiveId"),
                    if (json.isNull("taskId")) null else json.getString("taskId"), json.getString("kind"),
                    json.getString("title"), json.getString("filePath"), json.optString("summary", ""), json.getLong("createdAt"),
                    evidence, sources, if (json.isNull("contentSha256")) null else json.getString("contentSha256"))
                if (record.objectiveId != objectiveId) continue
                if (confined(File(root, record.filePath)).isFile) records += record
            } catch (_: Exception) {
                // Missing, oversized, or malformed sidecars do not prevent listing other records.
            }
        }
        return records.sortedWith(compareBy<ContextRecord> { it.createdAt }.thenBy { it.id })
    }

    fun deleteObjective(objectiveId: String) {
        val objectiveDir = confined(File(File(root, "objectives"), checkedSegment(objectiveId, "objectiveId")))
        if (!objectiveDir.exists()) return
        try {
            // walkFileTree's default is NO FOLLOW_LINKS. Directory symlinks are visited as files,
            // allowing their deletion without deleting or even enumerating the target directory.
            Files.walkFileTree(objectiveDir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (!attrs.isSymbolicLink) confined(path.toFile())
                    Files.deleteIfExists(path)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(path: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (error != null) throw error
                    confined(path.toFile())
                    Files.deleteIfExists(path)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("Unable to delete context file", failure)
        }
    }

    fun totalBytes(objectiveId: String): Long {
        val objectiveDir = confined(File(File(root, "objectives"), checkedSegment(objectiveId, "objectiveId")))
        if (!objectiveDir.isDirectory) return 0L
        return safeWalk(objectiveDir).filter { it.isFile && it.name.endsWith(".txt") }.sumOf { it.length() }
    }

    private fun safeWalk(directory: File): Sequence<File> = directory.walkTopDown().onEnter { safeDirectory(it) }
        .filter { !Files.isSymbolicLink(it.toPath()) }.filter { try { confined(it); true } catch (_: Exception) { false } }

    private fun safeDirectory(directory: File): Boolean = !Files.isSymbolicLink(directory.toPath()) &&
        try { confined(directory); true } catch (_: Exception) { false }

    private fun atomicWrite(target: File, contents: String) {
        val safeTarget = confined(target)
        val parent = safeTarget.parentFile ?: throw IllegalArgumentException("Invalid context path")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) throw IllegalStateException("Unable to create context directory")
        val temp = File.createTempFile(".nyx-context-", ".tmp", parent)
        try {
            Files.write(temp.toPath(), contents.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temp.toPath(), safeTarget.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), safeTarget.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }

    private fun confined(file: File): File {
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        val rootPath = canonicalRoot.path
        require(canonicalFile.path == rootPath || canonicalFile.path.startsWith(rootPath + File.separator)) { "Context path escapes store root" }
        return canonicalFile
    }

    private fun relativePath(file: File): String = root.canonicalFile.toPath().relativize(confined(file).toPath()).toString().replace(File.separatorChar, '/')

    private fun checkedSegment(value: String, label: String): String {
        require(value.isNotBlank()) { "$label must not be blank" }
        require(!value.contains('/') && !value.contains('\\') && value != "." && !value.contains("..")) { "$label must not contain path traversal" }
        return sanitizeSegment(value).also { require(it.isNotEmpty()) { "$label has no safe characters" } }
    }

    private fun sanitizeSegment(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
    private fun singleLine(text: String): String = text.replace(Regex("[\\r\\n\\t]+"), " ").trim()

    private companion object { const val MAX_METADATA_CHARS = 65_536 }
}

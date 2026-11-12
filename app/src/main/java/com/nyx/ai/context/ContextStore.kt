package com.nyx.ai.context

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject

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
)

/** File-backed context storage. All persisted paths are confined to [root]. */
class ContextStore(private val root: File) {
    fun write(objectiveId: String, taskId: String?, kind: String, title: String, text: String): ContextRecord {
        val safeObjective = checkedSegment(objectiveId, "objectiveId")
        val safeKind = checkedSegment(kind, "kind")
        // UUID (36) plus hyphen and title must keep the filename stem at 64 characters or fewer.
        val safeTitle = sanitizeSegment(title).take(27).ifEmpty { "untitled" }
        val objectiveDir = confined(File(File(root, "objectives"), safeObjective))
        val kindDir = confined(File(objectiveDir, safeKind))
        if (!kindDir.exists() && !kindDir.mkdirs() && !kindDir.isDirectory) {
            throw IllegalStateException("Unable to create context directory")
        }

        val id = UUID.randomUUID().toString()
        val filename = "$id-$safeTitle.txt"
        val contentFile = confined(File(kindDir, filename))
        val metadataFile = confined(File(kindDir, "$filename.json"))
        val createdAt = System.currentTimeMillis()
        val record = ContextRecord(
            id = id,
            objectiveId = objectiveId,
            taskId = taskId,
            kind = kind,
            title = title,
            filePath = relativePath(contentFile),
            summary = singleLine(text).take(300),
            createdAt = createdAt,
        )

        atomicWrite(contentFile, text)
        val metadata = JSONObject()
            .put("id", record.id)
            .put("objectiveId", record.objectiveId)
            .put("taskId", record.taskId)
            .put("kind", record.kind)
            .put("title", record.title)
            .put("filePath", record.filePath)
            .put("summary", record.summary)
            .put("createdAt", record.createdAt)
        try {
            atomicWrite(metadataFile, metadata.toString())
        } catch (failure: Exception) {
            contentFile.delete()
            throw failure
        }
        return record
    }

    /** Reads at most [maxChars] characters; invalid, missing, or out-of-root paths read as empty text. */
    fun read(record: ContextRecord, maxChars: Int = 8_000): String {
        if (maxChars <= 0) return ""
        return try {
            val candidate = confined(File(root, record.filePath))
            if (!candidate.isFile) return ""
            String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8).take(maxChars)
        } catch (_: Exception) {
            ""
        }
    }

    fun list(objectiveId: String): List<ContextRecord> {
        val safeObjective = checkedSegment(objectiveId, "objectiveId")
        val objectiveDir = try {
            confined(File(File(root, "objectives"), safeObjective))
        } catch (_: Exception) {
            return emptyList()
        }
        if (!objectiveDir.isDirectory) return emptyList()

        val records = mutableListOf<ContextRecord>()
        val metadataFiles = objectiveDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".txt.json") }
            .sortedBy { relativePath(it) }
        for (metadataFile in metadataFiles) {
            try {
                val safeMetadata = confined(metadataFile)
                val json = JSONObject(String(Files.readAllBytes(safeMetadata.toPath()), StandardCharsets.UTF_8))
                val record = ContextRecord(
                    id = json.getString("id"),
                    objectiveId = json.getString("objectiveId"),
                    taskId = if (json.isNull("taskId")) null else json.getString("taskId"),
                    kind = json.getString("kind"),
                    title = json.getString("title"),
                    filePath = json.getString("filePath"),
                    summary = json.optString("summary", ""),
                    createdAt = json.getLong("createdAt"),
                )
                if (record.objectiveId != objectiveId) continue
                val contentFile = confined(File(root, record.filePath))
                if (contentFile.isFile) records += record
            } catch (_: Exception) {
                // A missing or malformed sidecar must not prevent other records from being listed.
            }
        }
        return records.sortedWith(compareBy<ContextRecord> { it.createdAt }.thenBy { it.id })
    }

    fun deleteObjective(objectiveId: String) {
        val safeObjective = checkedSegment(objectiveId, "objectiveId")
        val objectiveDir = confined(File(File(root, "objectives"), safeObjective))
        if (!objectiveDir.exists()) return
        objectiveDir.walkBottomUp().forEach { file ->
            if (!file.delete() && file.exists()) throw IllegalStateException("Unable to delete context file")
        }
    }

    fun totalBytes(objectiveId: String): Long {
        val safeObjective = checkedSegment(objectiveId, "objectiveId")
        val objectiveDir = confined(File(File(root, "objectives"), safeObjective))
        if (!objectiveDir.isDirectory) return 0L
        return objectiveDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".txt") }
            .sumOf { it.length() }
    }

    private fun atomicWrite(target: File, contents: String) {
        val safeTarget = confined(target)
        val parent = safeTarget.parentFile ?: throw IllegalArgumentException("Invalid context path")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("Unable to create context directory")
        }
        val temp = File.createTempFile(".nyx-context-", ".tmp", parent)
        try {
            Files.write(temp.toPath(), contents.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(
                    temp.toPath(), safeTarget.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), safeTarget.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun confined(file: File): File {
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        val rootPath = canonicalRoot.path
        if (canonicalFile.path != rootPath && !canonicalFile.path.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("Context path escapes store root")
        }
        return canonicalFile
    }

    private fun relativePath(file: File): String {
        val rootPath = root.canonicalFile.toPath()
        return rootPath.relativize(confined(file).toPath()).toString().replace(File.separatorChar, '/')
    }

    private fun checkedSegment(value: String, label: String): String {
        require(value.isNotBlank()) { "$label must not be blank" }
        require(!value.contains('/') && !value.contains('\\') && value != "." && !value.contains("..")) {
            "$label must not contain path traversal"
        }
        return sanitizeSegment(value).also { require(it.isNotEmpty()) { "$label has no safe characters" } }
    }

    private fun sanitizeSegment(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)

    private fun singleLine(text: String): String = text.replace(Regex("[\\r\\n\\t]+"), " ").trim()
}

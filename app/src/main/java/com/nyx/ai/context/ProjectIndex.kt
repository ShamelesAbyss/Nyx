package com.nyx.ai.context

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** A source identity binds content to one project root and its explicit relative-path scope. */
data class ProjectSourceReference(
    val scopeId: String,
    val relativePath: String,
    val sha256: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
)

data class ProjectIndexLimits(
    val maxFiles: Int = 1_000,
    val maxFileBytes: Long = 1_048_576,
    val maxTotalBytes: Long = 16_777_216,
    val maxVisitedEntries: Int = 10_000,
    val maxDepth: Int = 64,
) {
    init {
        require(maxFiles >= 0 && maxFileBytes >= 0 && maxTotalBytes >= 0 && maxVisitedEntries >= 0 && maxDepth in 0..256)
    }
}

data class ProjectIndexSnapshot(
    val scopeId: String,
    val sources: List<ProjectSourceReference>,
    val complete: Boolean,
    val skippedPaths: List<String> = emptyList(),
)

data class ProjectIndexRefresh(
    val snapshot: ProjectIndexSnapshot,
    val changedPaths: List<String>,
    val removedPaths: List<String>,
    /** Includes missing, changed, and now-unindexed evidence. Never retain it as fresh. */
    val invalidatedSources: List<ProjectSourceReference>,
)

data class ProjectRetrieval(
    val text: String,
    val sources: List<ProjectSourceReference>,
    val usedChars: Int,
    val truncated: Boolean,
    val invalidatedPaths: List<String> = emptyList(),
)

/** Local-only index; no tool execution, model calls, filesystem writes, or network access. */
class ProjectIndex(
    root: File,
    relativePaths: Set<String> = setOf("."),
    excludedDirectories: Set<String> = setOf(".git", ".gradle", "build", "node_modules", ".idea"),
) {
    private val root = root.canonicalFile
    private val prefixes = relativePaths.map { checkedRelative(it, allowRoot = true) }.distinct().sorted()
    private val excludedDirectories = excludedDirectories.toSet()
    val scopeId: String = ContextFiles.sha256(buildString {
        fun field(value: String) { append(value.length).append(':').append(value) }
        field(this@ProjectIndex.root.path)
        field(prefixes.size.toString())
        prefixes.forEach { field(it) }
        field(this@ProjectIndex.excludedDirectories.size.toString())
        this@ProjectIndex.excludedDirectories.sorted().forEach { field(it) }
    })

    init { require(prefixes.isNotEmpty()) { "Project scope must not be empty" } }

    fun build(limits: ProjectIndexLimits = ProjectIndexLimits()): ProjectIndexSnapshot {
        val sources = mutableListOf<ProjectSourceReference>()
        val skipped = sortedSetOf<String>()
        var totalBytes = 0L
        var visited = 0
        var complete = true
        var exhausted = false
        val seen = mutableSetOf<String>()

        fun visit(file: File, depth: Int) {
            if (exhausted) return
            if (visited >= limits.maxVisitedEntries) {
                complete = false
                exhausted = true
                return
            }
            visited++
            val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            if (depth > limits.maxDepth || relative.any { it.code < 32 }) {
                skipped += relative
                complete = false
                return
            }
            if (!seen.add(relative)) return
            if (file.isDirectory && file != root && file.name in excludedDirectories) return
            if (!safeFile(file)) {
                skipped += relative
                complete = false
                return
            }
            if (file.isDirectory) {
                try {
                    // Never allocate listFiles() for an unbounded directory. If enumeration exceeds
                    // the remaining visit budget, omit the whole directory rather than select a
                    // readdir-order-dependent prefix and present it as deterministic coverage.
                    val children = mutableListOf<File>()
                    var overflow = false
                    Files.newDirectoryStream(file.toPath()).use { entries ->
                        for (entry in entries) {
                            if (children.size >= limits.maxVisitedEntries - visited) { overflow = true; break }
                            children += entry.toFile()
                        }
                    }
                    if (overflow) {
                        skipped += relative.ifEmpty { "." }
                        complete = false
                        exhausted = true
                    } else children.sortedBy { it.name }.forEach { visit(it, depth + 1) }
                } catch (_: Exception) {
                    skipped += relative
                    complete = false
                }
                return
            }
            if (!file.isFile) {
                skipped += relative
                complete = false
                return
            }
            val size = file.length()
            if (sources.size >= limits.maxFiles || size > limits.maxFileBytes || size > limits.maxTotalBytes - totalBytes) {
                skipped += relative
                complete = false
                return
            }
            try {
                val before = attributes(file)
                // Charge the scan even if a concurrent write makes this observation unusable.
                totalBytes += size
                val hash = ContextFiles.sha256(file, minOf(limits.maxFileBytes, size))
                val after = attributes(file)
                if (hash == null || !sameFile(before, after)) {
                    skipped += relative
                    complete = false
                } else {
                    sources += ProjectSourceReference(scopeId, relative, hash, after.lastModifiedTime().toMillis(), after.size())
                }
            } catch (_: Exception) {
                skipped += relative
                complete = false
            }
        }

        prefixes.forEach { prefix ->
            val file = File(root, prefix)
            if (!file.exists()) { skipped += prefix; complete = false } else visit(file, prefix.count { it == '/' })
        }
        return ProjectIndexSnapshot(scopeId, sources.sortedBy { it.relativePath }, complete, skipped.toList())
    }

    fun refresh(previous: ProjectIndexSnapshot, limits: ProjectIndexLimits = ProjectIndexLimits()): ProjectIndexRefresh {
        require(previous.scopeId == scopeId) { "Index belongs to a different scope" }
        val current = build(limits)
        val currentByPath = current.sources.associateBy { it.relativePath }
        val invalidated = previous.sources.filter { old -> currentByPath[old.relativePath]?.sha256 != old.sha256 }
        val changed = invalidated.filter { it.relativePath in currentByPath }.map { it.relativePath }.distinct().sorted()
        val removed = invalidated.filter { it.relativePath !in currentByPath }.map { it.relativePath }.distinct().sorted()
        return ProjectIndexRefresh(current, changed, removed, invalidated.sortedBy { it.relativePath })
    }

    /** Re-hashes even if size and mtime are unchanged. A snapshot is not a freshness guarantee. */
    fun isCurrent(source: ProjectSourceReference): Boolean = try {
        val file = resolve(source)
        file != null && file.isFile && source.sizeBytes >= 0 && file.length() == source.sizeBytes &&
            ContextFiles.sha256(file, source.sizeBytes) == source.sha256
    } catch (_: Exception) { false }

    fun retrieve(
        snapshot: ProjectIndexSnapshot,
        query: String,
        budgetChars: Int = 4_000,
        maxFiles: Int = 6,
        maxFileChars: Int = 1_500,
    ): ProjectRetrieval {
        require(snapshot.scopeId == scopeId) { "Index belongs to a different scope" }
        require(budgetChars >= 0 && maxFiles >= 0 && maxFileChars >= 0)
        val words = keywords(query)
        val ranked = snapshot.sources.distinctBy { it.relativePath }.sortedWith(
            compareByDescending<ProjectSourceReference> { words.intersect(keywords(it.relativePath)).size }
                .thenBy { it.relativePath },
        )
        val output = StringBuilder(minOf(budgetChars, 4_096))
        val included = mutableListOf<ProjectSourceReference>()
        val invalidated = mutableListOf<String>()
        var truncated = !snapshot.complete
        for (source in ranked) {
            if (included.size >= maxFiles || output.length >= budgetChars) { truncated = true; break }
            if (!isCurrent(source)) { invalidated += source.relativePath; truncated = true; continue }
            val header = "SOURCE ${source.relativePath} sha256=${source.sha256} mtime=${source.modifiedAt}\n"
            val separator = if (output.isEmpty()) "" else "\n\n"
            val available = budgetChars - output.length - separator.length - header.length
            if (available <= 0 || maxFileChars == 0) { truncated = true; continue }
            try {
                val file = resolve(source) ?: continue
                val cap = minOf(available, maxFileChars)
                val content = ContextFiles.readText(file, if (cap == Int.MAX_VALUE) cap else cap + 1)
                // Detect a modification occurring between the first validation and reading.
                if (!isCurrent(source)) { invalidated += source.relativePath; truncated = true; continue }
                output.append(separator).append(header).append(content.take(cap))
                included += source
                if (content.length > cap) truncated = true
            } catch (_: Exception) { invalidated += source.relativePath; truncated = true }
        }
        return ProjectRetrieval(output.toString(), included, output.length, truncated, invalidated.distinct().sorted())
    }

    private fun resolve(source: ProjectSourceReference): File? {
        if (source.scopeId != scopeId || !source.sha256.matches(Regex("[0-9a-f]{64}"))) return null
        val path = checkedRelative(source.relativePath, allowRoot = false)
        if (prefixes.none { it == "." || path == it || path.startsWith("$it/") }) return null
        val file = File(root, path)
        var ancestor: File? = file
        while (ancestor != null && ancestor != root) {
            if (Files.isSymbolicLink(ancestor.toPath()) || (ancestor.isDirectory && ancestor.name in excludedDirectories)) return null
            ancestor = ancestor.parentFile
        }
        return file.takeIf { safeFile(it) }
    }

    private fun safeFile(file: File): Boolean = try {
        val canonical = file.canonicalFile.toPath()
        if (canonical != root.toPath() && !canonical.startsWith(root.toPath())) false else {
            var ancestor: File? = file
            var safe = true
            while (ancestor != null && ancestor != root) {
                if (Files.isSymbolicLink(ancestor.toPath()) || (ancestor.isDirectory && ancestor.name in excludedDirectories)) {
                    safe = false
                    break
                }
                ancestor = ancestor.parentFile
            }
            safe
        }
    } catch (_: Exception) { false }

    private fun attributes(file: File): BasicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    private fun sameFile(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime() && a.fileKey() == b.fileKey()

    private fun keywords(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{2,}")
        .findAll(value.lowercase(Locale.ROOT)).map { it.value }.toSet()

    private fun checkedRelative(value: String, allowRoot: Boolean): String {
        require(value.isNotBlank() && value.none { it.code < 32 } && '\\' !in value && !File(value).isAbsolute) { "Use a relative project path" }
        if (value == "." && allowRoot) return value
        require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid project path" }
        return value
    }
}

package com.nyx.ai.context

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One declaration extracted from a Kotlin or Java source string. Lines are 1-based. */
data class SymbolDefinition(
    val name: String,
    val kind: String,
    val path: String,
    val line: Int,
    val signature: String = "",
)

/** One non-declaration mention of a symbol. Lines are 1-based. */
data class SymbolReference(
    val name: String,
    val path: String,
    val line: Int,
    val kind: String = "reference",
)

/** Parsed, hash-addressed source snapshot. */
data class IndexedSource(
    val path: String,
    val sha256: String,
    val packageName: String = "",
    val imports: List<String> = emptyList(),
    val definitions: List<SymbolDefinition> = emptyList(),
    val references: List<SymbolReference> = emptyList(),
    val updatedAt: Long = 0L,
)

/** A ranked search hit produced by [SymbolIndex.findRelevant]. */
data class SymbolMatch(
    val path: String,
    val name: String,
    val kind: String,
    val line: Int,
    val signature: String,
    val score: Int,
)

/**
 * Incremental, content-addressed index for Kotlin and Java source strings.
 *
 * This parser never reads project files, executes code, resolves classpaths, or invokes a
 * compiler. Callers supply each source string explicitly. `update` skips parsing when the
 * supplied bytes hash to the already indexed SHA-256. The JSON document lives directly
 * under [root] and is replaced atomically.
 */
class SymbolIndex(
    private val root: File,
    private val maxFiles: Int = 1_000,
    private val maxDefinitionsPerFile: Int = 400,
    private val maxReferencesPerFile: Int = 1_000,
) {
    init {
        require(maxFiles > 0) { "maxFiles must be positive" }
        require(maxDefinitionsPerFile > 0) { "maxDefinitionsPerFile must be positive" }
        require(maxReferencesPerFile > 0) { "maxReferencesPerFile must be positive" }
    }

    val indexFile: File = File(root, "symbol-index.json")
    private val lock = Any()
    private var loaded = false
    private val files = LinkedHashMap<String, IndexedSource>()

    /** Returns true only when the content was new or changed and therefore reparsed. */
    fun update(path: String, content: String): Boolean = synchronized(lock) {
        val safePath = checkedPath(path)
        require(content.length <= MAX_CONTENT_CHARS) { "Source content exceeds the index budget" }
        loadIfNeeded()
        val hash = sha256Hex(content)
        val previous = files[safePath]
        if (previous?.sha256 == hash) {
            false
        } else {
            val parsed = parseSource(safePath, content)
            if (previous == null && files.size >= maxFiles) {
                val victim = files.values.minWithOrNull(
                    compareBy<IndexedSource> { it.updatedAt }.thenBy { it.path },
                )
                if (victim != null) files.remove(victim.path)
            }
            files[safePath] = parsed.copy(sha256 = hash, updatedAt = System.currentTimeMillis())
            persist()
            true
        }
    }

    fun remove(path: String): Boolean = synchronized(lock) {
        loadIfNeeded()
        val safePath = try {
            checkedPath(path)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (safePath != null && files.remove(safePath) != null) {
            persist()
            true
        } else {
            false
        }
    }

    fun allPaths(): List<String> = synchronized(lock) {
        loadIfNeeded()
        files.keys.sorted()
    }

    /** Full parsed snapshots, sorted by path. Useful for diagnostics and persistence checks. */
    fun indexedFiles(): List<IndexedSource> = synchronized(lock) {
        loadIfNeeded()
        files.values.sortedBy { it.path }
    }

    fun indexedFile(path: String): IndexedSource? = synchronized(lock) {
        loadIfNeeded()
        try {
            files[checkedPath(path)]
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Exact identifier lookup; a qualified name also matches its final segment. */
    fun definitionsOf(symbol: String): List<SymbolDefinition> = synchronized(lock) {
        loadIfNeeded()
        val target = symbol.trim()
        if (target.isEmpty()) {
            emptyList()
        } else {
            val short = target.substringAfterLast('.')
            files.values.asSequence()
                .flatMap { it.definitions.asSequence() }
                .filter { it.name == target || (short != target && it.name == short) }
                .sortedWith(compareBy<SymbolDefinition> { it.path }.thenBy { it.line }.thenBy { it.name })
                .toList()
        }
    }

    fun referencesTo(symbol: String): List<SymbolReference> = synchronized(lock) {
        loadIfNeeded()
        val target = symbol.trim()
        if (target.isEmpty()) {
            emptyList()
        } else {
            val short = target.substringAfterLast('.')
            files.values.asSequence()
                .flatMap { it.references.asSequence() }
                .filter { it.name == target || (short != target && it.name == short) }
                .distinctBy { "${it.path}\u0000${it.line}\u0000${it.name}\u0000${it.kind}" }
                .sortedWith(
                    compareBy<SymbolReference> { it.path }
                        .thenBy { it.line }
                        .thenBy { it.name }
                        .thenBy { it.kind },
                )
                .toList()
        }
    }

    /**
     * Ranks declarations against [query] using identifier, signature, package, import, and
     * path token overlap. Deterministic tie-breaking keeps identical indexes byte-identical.
     */
    fun findRelevant(query: String, limit: Int): List<SymbolMatch> = synchronized(lock) {
        loadIfNeeded()
        if (limit <= 0) {
            emptyList()
        } else {
            val words = keywords(query)
            if (words.isEmpty()) {
                emptyList()
            } else {
                val matches = mutableListOf<SymbolMatch>()
                for (file in files.values) {
                    val fileScore = overlap(words, keywords(file.path.replace('/', ' '))) * 2 +
                        overlap(words, keywords(file.packageName)) * 2 +
                        overlap(words, keywords(file.imports.joinToString(" ")))
                    for (definition in file.definitions) {
                        val nameScore = overlap(words, keywords(definition.name)) * 8
                        val signatureScore = overlap(words, keywords(definition.signature)) * 2
                        val score = nameScore + signatureScore + fileScore
                        if (score > 0) {
                            matches += SymbolMatch(
                                path = file.path,
                                name = definition.name,
                                kind = definition.kind,
                                line = definition.line,
                                signature = definition.signature,
                                score = score,
                            )
                        }
                    }
                }
                matches.distinctBy { "${it.path}\u0000${it.name}\u0000${it.line}" }
                    .sortedWith(
                        compareByDescending<SymbolMatch> { it.score }
                            .thenBy { it.name }
                            .thenBy { it.path }
                            .thenBy { it.line },
                    )
                    .take(limit)
            }
        }
    }

    private fun parseSource(path: String, content: String): IndexedSource {
        val lines = content.lineSequence().take(MAX_LINES).toList()
        val scanner = LineScanner()
        val scanned = lines.map { scanner.scan(it) }
        val depths = IntArray(lines.size)
        var depth = 0
        for (index in lines.indices) {
            depths[index] = depth
            for (char in scanned[index]) {
                when (char) {
                    '{' -> depth++
                    '}' -> depth = (depth - 1).coerceAtLeast(0)
                }
            }
        }

        var packageName = ""
        val imports = mutableListOf<String>()
        val importReferences = mutableListOf<SymbolReference>()
        for (index in scanned.indices) {
            val trimmed = scanned[index].trim()
            if (trimmed.startsWith("package ")) {
                if (packageName.isEmpty()) {
                    PACKAGE_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { packageName = it }
                }
            } else if (trimmed.startsWith("import ")) {
                val body = trimmed.removePrefix("import").trim().removePrefix("static ").trim()
                    .removeSuffix(";").trim()
                if (body.isNotEmpty()) {
                    imports += body.take(MAX_IMPORT_CHARS)
                    val alias = IMPORT_ALIAS.find(body)?.groupValues?.get(1)
                    val symbolPart = body.replace(IMPORT_ALIAS, "").trim()
                    val simple = alias ?: symbolPart.substringAfterLast('.').removeSuffix("*").trim()
                    if (SIMPLE_NAME.matches(simple)) {
                        importReferences += SymbolReference(simple, path, index + 1, "import")
                    }
                }
            }
        }

        val definitions = mutableListOf<SymbolDefinition>()
        for (index in scanned.indices) {
            val code = scanned[index]
            val trimmed = code.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue
            if (depths[index] == 0) {
                typeDeclaration(code)?.let { declaration ->
                    definitions += SymbolDefinition(
                        name = declaration.name,
                        kind = declaration.kind,
                        path = path,
                        line = index + 1,
                        signature = stripLeadingAnnotations(trimmed).take(MAX_SIGNATURE_CHARS),
                    )
                }
            }
            var kotlinFunction = false
            for (match in KOTLIN_FUNCTION.findAll(code)) {
                val name = match.groupValues[1]
                if (!SIMPLE_NAME.matches(name) || name in NON_FUNCTION_NAMES) continue
                definitions += SymbolDefinition(
                    name = name,
                    kind = "function",
                    path = path,
                    line = index + 1,
                    signature = signatureAt(scanned, index, match.range.first),
                )
                kotlinFunction = true
            }
            if (!kotlinFunction) {
                val unannotated = stripLeadingAnnotations(code)
                val firstToken = unannotated.trimStart().substringBefore(' ')
                if (firstToken in KOTLIN_ONLY_STARTERS) continue
                val match = JAVA_METHOD.find(unannotated)
                if (match != null) {
                    val returnType = match.groupValues[1].trim()
                    val name = match.groupValues[2]
                    if (SIMPLE_NAME.matches(name) && name !in NON_FUNCTION_NAMES &&
                        returnType !in JAVA_MODIFIERS &&
                        returnType.isNotEmpty() && returnType.none { it == '=' || it == ':' } &&
                        !returnType.endsWith(".")
                    ) {
                        val start = code.indexOf(returnType).coerceAtLeast(0)
                        definitions += SymbolDefinition(
                            name = name,
                            kind = "function",
                            path = path,
                            line = index + 1,
                            signature = signatureAt(scanned, index, start),
                        )
                    }
                }
            }
        }

        val references = mutableListOf<SymbolReference>()
        references += importReferences
        val declaredByLine = definitions.groupBy { it.line }
            .mapValues { entry -> entry.value.map { it.name }.toSet() }
        for (index in scanned.indices) {
            val code = scanned[index]
            val trimmed = code.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("package ") || trimmed.startsWith("import ")) continue
            val declared = declaredByLine[index + 1].orEmpty()
            val namesOnLine = mutableSetOf<String>()
            for (match in TYPE_REFERENCE.findAll(code)) {
                val name = match.groupValues[1]
                if (name.length >= 2 && name !in declared && name !in namesOnLine) {
                    namesOnLine += name
                    references += SymbolReference(name, path, index + 1, "type")
                }
            }
            for (match in CALL_REFERENCE.findAll(code)) {
                val name = match.groupValues[1]
                if (name.length >= 2 && name !in declared && name !in NON_FUNCTION_NAMES && name !in namesOnLine) {
                    namesOnLine += name
                    references += SymbolReference(name, path, index + 1, "call")
                }
            }
        }

        val cappedDefinitions = definitions
            .distinctBy { "${it.name}\u0000${it.line}\u0000${it.kind}" }
            .sortedWith(compareBy<SymbolDefinition> { it.line }.thenBy { it.name }.thenBy { it.kind })
            .take(maxDefinitionsPerFile)
        val cappedReferences = references
            .distinctBy { "${it.name}\u0000${it.line}\u0000${it.kind}" }
            .sortedWith(compareBy<SymbolReference> { it.line }.thenBy { it.name }.thenBy { it.kind })
            .take(maxReferencesPerFile)
        return IndexedSource(
            path = path,
            sha256 = "",
            packageName = packageName,
            imports = imports.distinct().take(MAX_IMPORTS),
            definitions = cappedDefinitions,
            references = cappedReferences,
            updatedAt = 0L,
        )
    }

    private fun typeDeclaration(code: String): TypeDeclaration? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        INTERFACE_ANNOTATION.find(trimmed)?.let { return TypeDeclaration(it.groupValues[1], "annotation") }
        val stripped = stripLeadingAnnotations(trimmed)
        val match = TYPE_DECLARATION.find(stripped) ?: return null
        val keyword = match.groupValues[1]
        val name = match.groupValues[2]
        val keywordStart = match.groups[1]?.range?.first ?: match.range.first
        val prefix = stripped.substring(0, keywordStart)
        val kind = when {
            keyword == "object" -> "object"
            keyword == "interface" -> "interface"
            ENUM_MODIFIER.containsMatchIn(prefix) -> "enum"
            ANNOTATION_MODIFIER.containsMatchIn(prefix) -> "annotation"
            else -> "class"
        }
        return TypeDeclaration(name, kind)
    }

    private fun stripLeadingAnnotations(value: String): String {
        var result = value.trimStart()
        while (true) {
            val match = LEADING_ANNOTATION.find(result) ?: break
            if (match.range.first != 0 || match.value.isEmpty()) break
            result = result.substring(match.value.length)
        }
        return result
    }

    private fun signatureAt(lines: List<String>, startLine: Int, startIndex: Int): String {
        val builder = StringBuilder()
        var depth = 0
        var opened = false
        var lineIndex = startLine
        while (lineIndex < lines.size && lineIndex < startLine + MAX_SIGNATURE_LINES &&
            builder.length < MAX_SIGNATURE_CHARS
        ) {
            val text = lines[lineIndex]
            var index = if (lineIndex == startLine) startIndex.coerceIn(0, text.length) else 0
            while (index < text.length && builder.length < MAX_SIGNATURE_CHARS) {
                val char = text[index]
                if (!opened) {
                    builder.append(char)
                    if (char == '(') {
                        opened = true
                        depth = 1
                    }
                    index++
                    continue
                }
                when (char) {
                    '(' -> {
                        depth++
                        builder.append(char)
                        index++
                    }
                    ')' -> {
                        depth--
                        builder.append(char)
                        index++
                        if (depth == 0) {
                            while (index < text.length && builder.length < MAX_SIGNATURE_CHARS) {
                                val next = text[index]
                                if (next == '{' || next == '=' || next == ';') break
                                builder.append(next)
                                index++
                            }
                            return normalizeSignature(builder.toString())
                        }
                    }
                    '{', '=', ';' -> return normalizeSignature(builder.toString())
                    else -> {
                        builder.append(char)
                        index++
                    }
                }
            }
            builder.append(' ')
            lineIndex++
        }
        return normalizeSignature(builder.toString())
    }

    private fun normalizeSignature(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().removeSuffix("{").trim().take(MAX_SIGNATURE_CHARS)

    private data class TypeDeclaration(val name: String, val kind: String)

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!indexFile.isFile) return
        val text = try {
            ContextFiles.readText(indexFile, MAX_INDEX_CHARS + 1)
        } catch (_: Exception) {
            return
        }
        if (text.length > MAX_INDEX_CHARS) {
            quarantine()
            return
        }
        val document = try {
            JSONObject(text)
        } catch (_: Exception) {
            quarantine()
            return
        }
        if (document.optInt("version", -1) != INDEX_VERSION) {
            quarantine()
            return
        }
        val array = document.optJSONArray("files") ?: return
        val limit = minOf(array.length(), maxFiles)
        for (index in 0 until limit) {
            val json = array.optJSONObject(index) ?: continue
            val source = decodeSource(json) ?: continue
            files[source.path] = source
        }
    }

    private fun decodeSource(json: JSONObject): IndexedSource? {
        val path = try {
            checkedPath(json.optString("path"))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val sha256 = json.optString("sha256").trim()
        if (!SHA256_HEX.matches(sha256)) return null
        val packageName = json.optString("packageName").replace(Regex("\\s+"), " ").trim().take(MAX_PACKAGE_CHARS)
        val imports = decodeStrings(json.optJSONArray("imports"), MAX_IMPORTS, MAX_IMPORT_CHARS)
        val definitionsJson = json.optJSONArray("definitions") ?: JSONArray()
        val definitions = mutableListOf<SymbolDefinition>()
        for (index in 0 until minOf(definitionsJson.length(), maxDefinitionsPerFile)) {
            val definition = definitionsJson.optJSONObject(index) ?: continue
            val name = definition.optString("name").trim()
            if (!SIMPLE_NAME.matches(name)) continue
            definitions += SymbolDefinition(
                name = name,
                kind = definition.optString("kind").trim().take(MAX_KIND_CHARS).ifEmpty { "declaration" },
                path = path,
                line = definition.optInt("line", 0).coerceAtLeast(0),
                signature = definition.optString("signature").replace(Regex("\\s+"), " ").trim()
                    .take(MAX_SIGNATURE_CHARS),
            )
        }
        val referencesJson = json.optJSONArray("references") ?: JSONArray()
        val references = mutableListOf<SymbolReference>()
        for (index in 0 until minOf(referencesJson.length(), maxReferencesPerFile)) {
            val reference = referencesJson.optJSONObject(index) ?: continue
            val name = reference.optString("name").trim()
            if (!SIMPLE_NAME.matches(name)) continue
            references += SymbolReference(
                name = name,
                path = path,
                line = reference.optInt("line", 0).coerceAtLeast(0),
                kind = reference.optString("kind").trim().take(MAX_KIND_CHARS).ifEmpty { "reference" },
            )
        }
        return IndexedSource(
            path = path,
            sha256 = sha256,
            packageName = packageName,
            imports = imports,
            definitions = definitions.sortedWith(
                compareBy<SymbolDefinition> { it.line }.thenBy { it.name }.thenBy { it.kind },
            ).take(maxDefinitionsPerFile),
            references = references.sortedWith(
                compareBy<SymbolReference> { it.line }.thenBy { it.name }.thenBy { it.kind },
            ).take(maxReferencesPerFile),
            updatedAt = json.optLong("updatedAt", 0L).coerceAtLeast(0L),
        )
    }

    private fun decodeStrings(array: JSONArray?, limit: Int, maxChars: Int): List<String> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), limit)).mapNotNull { index ->
            array.optString(index).replace(Regex("\\s+"), " ").trim().take(maxChars).takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private fun persist() {
        val parent = indexFile.parentFile ?: throw IllegalStateException("Index file has no parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("Unable to create the symbol index directory")
        }
        val encodedFiles = JSONArray()
        for (source in files.values.sortedBy { it.path }) {
            encodedFiles.put(
                JSONObject()
                    .put("path", source.path)
                    .put("sha256", source.sha256)
                    .put("packageName", source.packageName)
                    .put("imports", JSONArray().apply { source.imports.forEach { put(it) } })
                    .put("definitions", JSONArray().apply {
                        source.definitions.forEach { definition ->
                            put(
                                JSONObject()
                                    .put("name", definition.name)
                                    .put("kind", definition.kind)
                                    .put("line", definition.line)
                                    .put("signature", definition.signature),
                            )
                        }
                    })
                    .put("references", JSONArray().apply {
                        source.references.forEach { reference ->
                            put(
                                JSONObject()
                                    .put("name", reference.name)
                                    .put("line", reference.line)
                                    .put("kind", reference.kind),
                            )
                        }
                    })
                    .put("updatedAt", source.updatedAt),
            )
        }
        val document = JSONObject()
            .put("version", INDEX_VERSION)
            .put("updatedAt", System.currentTimeMillis())
            .put("files", encodedFiles)
        val temp = File.createTempFile(".nyx-symbol-", ".tmp", parent)
        try {
            Files.write(temp.toPath(), document.toString().toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(
                    temp.toPath(),
                    indexFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    /** A damaged document is preserved beside the index and replaced only on the next update. */
    private fun quarantine() {
        val parent = indexFile.parentFile ?: return
        val target = File(parent, "${indexFile.name}.corrupt-${System.currentTimeMillis()}")
        try {
            if (!indexFile.renameTo(target)) {
                indexFile.copyTo(target, overwrite = false)
                indexFile.delete()
            }
        } catch (_: Exception) {
            // Loading still falls back to an empty index.
        }
    }

    private fun checkedPath(path: String): String {
        val trimmed = path.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_PATH_CHARS) { "Invalid source path" }
        require(!trimmed.startsWith('/') && !trimmed.contains('\\') && trimmed.none { it.code < 32 }) {
            "Invalid source path"
        }
        require(!WINDOWS_ABSOLUTE.matches(trimmed) && !File(trimmed).isAbsolute) { "Invalid source path" }
        val segments = trimmed.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid source path" }
        return segments.joinToString("/")
    }

    private fun keywords(value: String): Set<String> = Regex("[A-Za-z0-9_]{2,}")
        .findAll(value)
        .flatMap { match ->
            val token = match.value
            val parts = token.split(Regex("(?<=[a-z0-9])(?=[A-Z])|_")).filter { it.length >= 2 }
            (parts + token).map { it.lowercase(Locale.ROOT) }
        }
        .filterNot { it in SYMBOL_STOP_WORDS }
        .toSet()

    private fun overlap(left: Set<String>, right: Set<String>): Int =
        if (left.isEmpty() || right.isEmpty()) 0 else left.count { it in right }

    private companion object {
        const val INDEX_VERSION = 1
        const val MAX_CONTENT_CHARS = 2_000_000
        const val MAX_LINES = 20_000
        const val MAX_INDEX_CHARS = 4_000_000
        const val MAX_PATH_CHARS = 512
        const val MAX_PACKAGE_CHARS = 256
        const val MAX_IMPORT_CHARS = 512
        const val MAX_IMPORTS = 200
        const val MAX_SIGNATURE_CHARS = 240
        const val MAX_SIGNATURE_LINES = 8
        const val MAX_KIND_CHARS = 24
        val PACKAGE_PATTERN = Regex("""^package\s+([A-Za-z_][\w.]*)""")
        val IMPORT_ALIAS = Regex("""\s+as\s+([A-Za-z_][A-Za-z0-9_]*)\s*$""")
        val SIMPLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val INTERFACE_ANNOTATION = Regex("""^@interface\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val LEADING_ANNOTATION = Regex("""^@[A-Za-z_][A-Za-z0-9_.]*(?:\s*\([^)]*\))?\s*""")
        val ENUM_MODIFIER = Regex("\\benum\\b")
        val ANNOTATION_MODIFIER = Regex("\\bannotation\\b")
        val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:.*")
        val TYPE_DECLARATION = Regex(
            """^(?:(?:public|private|protected|internal|open|abstract|sealed|data|enum|annotation|value|fun|final|static|strictfp|expect|actual|inline|inner|companion)\s+)*(class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)""",
        )
        val KOTLIN_FUNCTION = Regex(
            """\bfun\s+(?:<[^>]{0,200}>\s*)?(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
        )
        val JAVA_METHOD = Regex(
            """^\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\s+)*(?:<[^>]{0,120}>\s*)?([A-Za-z_][A-Za-z0-9_.$<>\[\]?,\s]*?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
        )
        val TYPE_REFERENCE = Regex("\\b([A-Z][A-Za-z0-9_]+)\\b")
        val CALL_REFERENCE = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?:<[^>]{0,80}>)?\\s*\\(")
        val NON_FUNCTION_NAMES = setOf(
            "if", "for", "while", "when", "switch", "catch", "return", "throw", "new", "try",
            "else", "do", "synchronized", "assert", "super", "this", "fun", "val", "var",
            "class", "object", "interface", "enum", "package", "import", "constructor", "init",
        )
        val KOTLIN_ONLY_STARTERS = setOf(
            "val", "var", "fun", "typealias", "data", "sealed", "value", "inline", "suspend",
            "operator", "infix", "tailrec", "external", "expect", "actual", "companion",
        )
        val JAVA_MODIFIERS = setOf(
            "public", "private", "protected", "static", "final", "abstract", "synchronized",
            "native", "default", "strictfp", "class", "interface", "enum", "record",
        )
    }
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")

private val SYMBOL_STOP_WORDS = setOf(
    "about", "after", "again", "also", "and", "are", "because", "before", "being", "but",
    "can", "could", "does", "for", "from", "have", "into", "its", "more", "not", "only",
    "our", "over", "should", "that", "the", "their", "then", "there", "these", "they",
    "this", "those", "through", "under", "using", "was", "were", "what", "when", "where",
    "which", "while", "with", "would", "your", "find", "show", "symbol", "code",
)

/** Blanks comments, strings, and character literals while preserving line structure. */
private class LineScanner {
    private var blockCommentDepth = 0
    private var rawString = false
    private var quotedString = false

    fun scan(line: String): String {
        val output = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (blockCommentDepth > 0) {
                when {
                    char == '/' && index + 1 < line.length && line[index + 1] == '*' -> {
                        blockCommentDepth++
                        output.append("  ")
                        index += 2
                    }
                    char == '*' && index + 1 < line.length && line[index + 1] == '/' -> {
                        blockCommentDepth--
                        output.append("  ")
                        index += 2
                    }
                    else -> {
                        output.append(' ')
                        index++
                    }
                }
                continue
            }
            if (rawString) {
                if (char == '"' && index + 2 < line.length &&
                    line[index + 1] == '"' && line[index + 2] == '"'
                ) {
                    rawString = false
                    output.append("   ")
                    index += 3
                } else {
                    output.append(' ')
                    index++
                }
                continue
            }
            if (quotedString) {
                when {
                    char == '\\' && index + 1 < line.length -> {
                        output.append("  ")
                        index += 2
                    }
                    char == '"' -> {
                        quotedString = false
                        output.append(' ')
                        index++
                    }
                    else -> {
                        output.append(' ')
                        index++
                    }
                }
                continue
            }
            when {
                char == '/' && index + 1 < line.length && line[index + 1] == '/' -> {
                    while (index < line.length) {
                        output.append(' ')
                        index++
                    }
                }
                char == '/' && index + 1 < line.length && line[index + 1] == '*' -> {
                    blockCommentDepth = 1
                    output.append("  ")
                    index += 2
                }
                char == '"' && index + 2 < line.length &&
                    line[index + 1] == '"' && line[index + 2] == '"' -> {
                    rawString = true
                    output.append("   ")
                    index += 3
                }
                char == '"' -> {
                    quotedString = true
                    output.append(' ')
                    index++
                }
                char == '\'' -> {
                    output.append(' ')
                    index++
                    while (index < line.length) {
                        val next = line[index]
                        output.append(' ')
                        index++
                        if (next == '\\' && index < line.length) {
                            output.append(' ')
                            index++
                        } else if (next == '\'') {
                            break
                        }
                    }
                }
                else -> {
                    output.append(char)
                    index++
                }
            }
        }
        return output.toString()
    }
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

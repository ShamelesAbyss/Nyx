package com.nyx.ai.workbench

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An app-private, single-objective store. Never expose an arbitrary path supplied by a model.
 * Calls serialize across instances and processes. Data rename precedes manifest commit; on
 * interruption uncommitted orphan files are discarded, never implicitly trusted/adopted.
 * Manifest corruption fails closed instead of rebuilding provenance from arbitrary files.
 * Files and metadata are fsynced; directory fsync is best effort on platforms lacking it.
 * Private ancestor directories must not be writable by an untrusted OS-level adversary.
 */
class WorkbenchStore(root: File, val objectiveId: String, private val limits: WorkbenchLimits = WorkbenchLimits()) {
    private val base = root.toPath().toAbsolutePath().normalize()
    private val home: Path
    private val mutex: ReentrantLock
    private val manifestPath: Path get() = home.resolve("meta/manifest.json")

    init {
        safeName(objectiveId)
        ensureDirectory(base)
        home = base.resolve(objectiveId)
        ensureDirectory(home)
        listOf("artifacts", "sources", "imports", "meta").forEach { ensureDirectory(home.resolve(it)) }
        mutex = locks.computeIfAbsent(home.toString()) { ReentrantLock() }
        locked { loadAndRecover() }
    }

    fun writeArtifact(name: String, format: ArtifactFormat, bytes: ByteArray): ArtifactRecord {
        safeArtifactName(name, format)
        require(bytes.size.toLong() <= minOf(limits.maxArtifactBytes, format.maxBytes)) { "Artifact byte quota exceeded" }
        val payload = bytes.copyOf() // Caller mutation cannot change validated/hashed bytes.
        val validation = ArtifactFormats.requireValid(format, payload)
        return locked {
            val m = loadAndRecover()
            val hash = sha256(payload)
            val old = m.artifacts.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
            if (old != null) {
                require(old.fileName == name && old.format == format && old.sha256 == hash && old.status == ArtifactStatus.VERIFIED) { "Artifact name already registered with different contents or invalid state" }
                return@locked old
            }
            require(m.artifacts.size < limits.maxArtifacts) { "Artifact count quota exceeded" }
            checkQuota(payload.size.toLong())
            val record = ArtifactRecord(UUID.randomUUID().toString(), name, format, payload.size.toLong(), hash,
                System.currentTimeMillis(), validationNote = validation.note, relativePath = "artifacts/$name")
            commitPayload(record.relativePath, payload, m.copy(artifacts = m.artifacts + record))
            record
        }
    }

    fun listArtifacts(): List<ArtifactRecord> = locked { loadAndRecover().artifacts.filter { it.status == ArtifactStatus.VERIFIED } }
    fun readManifest(): WorkbenchManifest = locked { loadAndRecover() }
    fun readArtifact(id: String): ByteArray = locked {
        val a = registered(loadAndRecover(), id)
        verifiedBytes(a)
    }
    fun readArtifact(record: ArtifactRecord): ByteArray = locked {
        val a = registered(loadAndRecover(), record.id)
        require(a == record) { "Unregistered, forged or stale artifact record" }
        verifiedBytes(a)
    }
    fun verifyArtifact(id: String): Boolean = try { readArtifact(id); true } catch (_: Exception) { false }
    fun verifyArtifact(record: ArtifactRecord): Boolean = try { readArtifact(record); true } catch (_: Exception) { false }
    fun verifiedArtifactFile(record: ArtifactRecord): File = locked {
        val a = registered(loadAndRecover(), record.id)
        require(a == record) { "Unregistered, forged or stale artifact record" }
        verifiedBytes(a)
        checkedPath(a.relativePath).toFile()
    }
    fun deleteArtifact(id: String): Boolean = locked {
        val m = loadAndRecover()
        val a = m.artifacts.firstOrNull { it.id == id } ?: return@locked false
        // Commit revocation first. A crash can leave only a non-exportable orphan.
        save(m.copy(artifacts = m.artifacts.filterNot { it.id == id }))
        Files.deleteIfExists(checkedPath(a.relativePath)); syncDirectory(home.resolve("artifacts"))
        true
    }

    fun recordSource(source: ResearchSource, content: ByteArray? = null): ResearchSource {
        validateSource(source, false)
        val payload = content?.copyOf()
        require(payload == null || payload.size.toLong() <= limits.maxSourceBytes) { "Source byte quota exceeded" }
        return locked {
            val m = loadAndRecover()
            val hash = payload?.let { sha256(it) } ?: source.sha256
            val bytes = payload?.size?.toLong() ?: source.bytes
            val existing = m.sources.firstOrNull { it.url == source.url && it.sha256 == hash && it.status == source.status }
            if (existing != null) {
                // A later fetch may supply bytes for a previously metadata-only record.
                val path = sourcePath(existing.id)
                if (payload != null && !Files.exists(path, NOFOLLOW_LINKS)) {
                    checkQuota(payload.size.toLong())
                    val updated = existing.copy(bytes = bytes, sha256 = hash)
                    commitPayload("sources/${existing.id}.txt", payload, m.copy(sources = m.sources.map { if (it.id == existing.id) updated else it }))
                    return@locked updated
                }
                return@locked existing
            }
            require(m.sources.size < limits.maxSources) { "Source count quota exceeded" }
            val normalized = source.copy(id = UUID.randomUUID().toString(), bytes = bytes, sha256 = hash)
            validateSource(normalized, true)
            val next = m.copy(sources = m.sources + normalized)
            if (payload == null) save(next) else {
                checkQuota(payload.size.toLong())
                commitPayload("sources/${normalized.id}.txt", payload, next)
            }
            normalized
        }
    }
    fun listSources(): List<ResearchSource> = locked { loadAndRecover().sources }
    fun readSource(id: String): ByteArray? = locked {
        val s = loadAndRecover().sources.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown source ID")
        val path = sourcePath(s.id)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return@locked null
        readChecked(path, limits.maxSourceBytes).also { require(it.size.toLong() == s.bytes && sha256(it) == s.sha256) { "Source checksum mismatch" } }
    }
    fun importFile(name: String, bytes: ByteArray, contentType: String = "application/octet-stream"): ImportRecord {
        safeName(name); bounded(contentType, 128, "contentType")
        require(bytes.size.toLong() <= limits.maxImportBytes) { "Import byte quota exceeded" }
        val payload = bytes.copyOf()
        return locked {
            val m = loadAndRecover(); val hash = sha256(payload)
            m.imports.firstOrNull { it.fileName == name && it.sha256 == hash }?.let { old ->
                val actual = readChecked(checkedPath(old.relativePath), limits.maxImportBytes)
                require(actual.size.toLong() == old.bytes && sha256(actual) == old.sha256) { "Existing import corrupted" }
                return@locked old
            }
            require(m.imports.size < limits.maxImports) { "Import count quota exceeded" }
            checkQuota(payload.size.toLong())
            val id = UUID.randomUUID().toString()
            val record = ImportRecord(id, name, payload.size.toLong(), hash, System.currentTimeMillis(), contentType, "imports/$id.bin")
            commitPayload(record.relativePath, payload, m.copy(imports = m.imports + record)); record
        }
    }
    fun listImports(): List<ImportRecord> = locked { loadAndRecover().imports }
    fun readImport(id: String): ByteArray = locked {
        val r = loadAndRecover().imports.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown import ID")
        readChecked(checkedPath(r.relativePath), limits.maxImportBytes).also { require(it.size.toLong() == r.bytes && sha256(it) == r.sha256) { "Import checksum mismatch" } }
    }

    private fun registered(m: WorkbenchManifest, id: String): ArtifactRecord {
        val a = m.artifacts.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown artifact ID")
        require(a.status == ArtifactStatus.VERIFIED) { "Artifact is not verified" }; return a
    }
    private fun verifiedBytes(a: ArtifactRecord): ByteArray {
        val bytes = readChecked(checkedPath(a.relativePath), minOf(limits.maxArtifactBytes, a.format.maxBytes))
        require(bytes.size.toLong() == a.bytes && sha256(bytes) == a.sha256) { "Artifact checksum mismatch" }
        ArtifactFormats.requireValid(a.format, bytes)
        return bytes
    }

    private fun loadAndRecover(): WorkbenchManifest {
        checkLayout()
        val existing = Files.exists(manifestPath, NOFOLLOW_LINKS)
        var m = if (existing) {
            try { WorkbenchManifestCodec.decode(readChecked(manifestPath, limits.maxMetadataBytes)).also { validateManifest(it) } }
            catch (e: Exception) { throw IOException("Workbench manifest invalid; refusing unsafe recovery", e) }
        } else {
            // Missing metadata is not permission to adopt or destroy previously committed data.
            listOf("artifacts", "sources", "imports").forEach { dir ->
                Files.newDirectoryStream(home.resolve(dir)).use { paths ->
                    if (paths.iterator().hasNext()) throw IOException("Workbench manifest missing with existing data; refusing destructive recovery")
                }
            }
            val now = System.currentTimeMillis(); WorkbenchManifest(objectiveId, now, now)
        }
        val allowed = mapOf("artifacts" to m.artifacts.map { it.fileName }.toSet(),
            "sources" to m.sources.map { "${it.id}.txt" }.toSet(), "imports" to m.imports.map { "${it.id}.bin" }.toSet(),
            "meta" to setOf("manifest.json", ".lock"))
        allowed.forEach { (dir, keep) ->
            Files.newDirectoryStream(home.resolve(dir)).use { paths -> paths.forEach { path ->
                checkRegular(path)
                if (path.fileName.toString() !in keep) Files.delete(path)
            } }
        }
        val artifacts = m.artifacts.map { a ->
            if (a.status == ArtifactStatus.FAILED) a else {
                try { verifiedBytes(a); a }
                catch (e: Exception) { a.copy(status = ArtifactStatus.FAILED, validationNote = "Recovery verification failed: ${(e.message ?: "unreadable").take(200)}") }
            }
        }
        if (!existing || artifacts != m.artifacts) { m = m.copy(artifacts = artifacts); save(m) }
        return m
    }

    private fun validateManifest(m: WorkbenchManifest) {
        require(m.objectiveId == objectiveId && m.version == 1 && m.createdAt >= 0 && m.updatedAt >= 0)
        bounded(m.status, 32, "status")
        require(m.artifacts.size <= limits.maxArtifacts && m.sources.size <= limits.maxSources && m.imports.size <= limits.maxImports)
        require(m.artifacts.map { it.id }.distinct().size == m.artifacts.size)
        require(m.artifacts.map { it.fileName.lowercase() }.distinct().size == m.artifacts.size)
        require(m.sources.map { it.id }.distinct().size == m.sources.size && m.imports.map { it.id }.distinct().size == m.imports.size)
        m.artifacts.forEach { a ->
            safeId(a.id); safeArtifactName(a.fileName, a.format); digest(a.sha256)
            require(a.bytes in 1..minOf(limits.maxArtifactBytes, a.format.maxBytes) && a.createdAt >= 0)
            require(a.relativePath == "artifacts/${a.fileName}"); bounded(a.validationNote, 512, "validationNote")
        }
        m.sources.forEach { validateSource(it, true); require(it.bytes <= limits.maxSourceBytes) }
        m.imports.forEach { i ->
            safeId(i.id); safeName(i.fileName); digest(i.sha256)
            require(i.bytes in 0..limits.maxImportBytes && i.importedAt >= 0 && i.relativePath == "imports/${i.id}.bin")
            bounded(i.contentType, 128, "contentType")
        }
    }
    private fun validateSource(s: ResearchSource, requireId: Boolean) {
        if (requireId) safeId(s.id)
        bounded(s.url, 2048, "url"); require(s.url.isNotBlank())
        bounded(s.finalUrl, 2048, "finalUrl"); bounded(s.title, 512, "title")
        bounded(s.contentType, 128, "contentType"); bounded(s.status, 32, "status")
        bounded(s.note, 1024, "note"); require(s.bytes in 0..limits.maxSourceBytes && s.fetchedAt >= 0)
        if (s.sha256.isNotEmpty()) digest(s.sha256)
    }
    private fun sourcePath(id: String): Path { safeId(id); return checkedPath("sources/$id.txt") }

    private fun checkQuota(added: Long) {
        var used = 0L
        listOf("artifacts", "sources", "imports").forEach { dir ->
            Files.newDirectoryStream(home.resolve(dir)).use { paths -> paths.forEach { p -> used += checkRegular(p).size() } }
        }
        require(added <= limits.maxTotalBytes - used) { "Workbench total byte quota exceeded" }
    }
    private fun prepared(m: WorkbenchManifest): ByteArray {
        val next = m.copy(updatedAt = System.currentTimeMillis())
        validateManifest(next)
        return WorkbenchManifestCodec.encode(next).also { require(it.size.toLong() <= limits.maxMetadataBytes) { "Manifest metadata byte quota exceeded" } }
    }
    private fun save(m: WorkbenchManifest) { atomicWrite(manifestPath, prepared(m), true) }
    private fun commitPayload(relative: String, payload: ByteArray, next: WorkbenchManifest) {
        val metadata = prepared(next) // All quotas checked before leaving any final file.
        val path = checkedPath(relative)
        require(!Files.exists(path, NOFOLLOW_LINKS)) { "Payload path already exists" }
        atomicWrite(path, payload, false)
        try { atomicWrite(manifestPath, metadata, true) }
        catch (e: Exception) { Files.deleteIfExists(checkedPath(relative)); throw e }
    }
    private fun atomicWrite(target: Path, data: ByteArray, replace: Boolean) {
        checkLayout(); checkAncestors(target.parent)
        if (Files.exists(target, NOFOLLOW_LINKS)) checkRegular(target)
        val temp = target.parent.resolve(".tmp-${UUID.randomUUID()}")
        try {
            FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(data)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            checkLayout(); checkRegular(temp)
            if (Files.exists(target, NOFOLLOW_LINKS)) { checkRegular(target); require(replace) { "Refusing overwrite" } }
            if (replace) Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING) else Files.move(temp, target, ATOMIC_MOVE)
            syncDirectory(target.parent)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun readChecked(path: Path, max: Long): ByteArray {
        checkAncestors(path.parent)
        val attrs = checkRegular(path)
        require(attrs.size() <= max) { "File exceeds byte bound" }
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { c ->
            require(c.size() == attrs.size()) { "File changed while opening" }
            val buffer = ByteBuffer.allocate(attrs.size().toInt())
            while (buffer.hasRemaining()) { if (c.read(buffer) < 0) throw IOException("Truncated file") }
            require(c.size() == attrs.size()) { "File changed while reading" }
            return buffer.array()
        }
    }
    private fun checkedPath(relative: String): Path {
        val parts = relative.split('/')
        require(parts.size == 2 && parts[0] in setOf("artifacts", "sources", "imports")) { "Unsafe relative path" }
        safeName(parts[1]); checkLayout()
        val path = home.resolve(relative).normalize()
        require(path.startsWith(home) && path.parent == home.resolve(parts[0]))
        if (Files.exists(path, NOFOLLOW_LINKS)) checkRegular(path)
        return path
    }
    private fun checkLayout() {
        checkAncestors(home)
        listOf("artifacts", "sources", "imports", "meta").forEach { checkDirectory(home.resolve(it)) }
    }
    private fun <T> locked(block: () -> T): T = mutex.withLock {
        checkLayout()
        val lockPath = home.resolve("meta/.lock")
        if (!Files.exists(lockPath, NOFOLLOW_LINKS)) {
            try { FileChannel.open(lockPath, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) } }
            catch (e: java.nio.file.FileAlreadyExistsException) { checkRegular(lockPath) }
        }
        checkRegular(lockPath)
        FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS).use { channel -> channel.lock().use { block() } }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        internal fun safeName(name: String) {
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) && ".." !in name &&
                !name.endsWith('.') && !ZipBundleWriter.reserved(name)) { "Unsafe objective ID or filename" }
        }
        private fun safeArtifactName(name: String, format: ArtifactFormat) {
            safeName(name)
            val ext = name.substringAfterLast('.', "").lowercase()
            require(ext == format.extension || (format == ArtifactFormat.HTML && ext == "htm")) { "Filename extension does not match format" }
        }
        private fun safeId(id: String) { require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "Invalid record ID" } }
        private fun digest(hash: String) { require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid SHA-256" } }
        private fun bounded(value: String, max: Int, label: String) {
            require(value.length <= max && value.none { it == '\u0000' || (it < ' ' && it !in "\t\r\n") }) { "$label exceeds metadata bounds or contains controls" }
            // Reject malformed Unicode before org.json can replace it on UTF-8 encoding.
            require(ArtifactFormats.utf8(value.toByteArray(Charsets.UTF_8)) == value) { "$label contains malformed Unicode" }
        }
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun checkRegular(path: Path): BasicFileAttributes {
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(a.isRegularFile && !a.isSymbolicLink) { "Symlink or nonregular file forbidden: ${path.fileName}" }; return a
        }
        private fun checkDirectory(path: Path) {
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(a.isDirectory && !a.isSymbolicLink) { "Symlink or nondirectory forbidden" }
        }
        private fun checkAncestors(path: Path) {
            var current = path.root
            path.forEach { part -> current = current.resolve(part); checkDirectory(current) }
        }
        private fun ensureDirectory(path: Path) {
            var current = path.root
            path.forEach { part ->
                current = current.resolve(part)
                if (!Files.exists(current, NOFOLLOW_LINKS)) {
                    try { Files.createDirectory(current) }
                    catch (_: java.nio.file.FileAlreadyExistsException) { /* Validate winner below. */ }
                }
                checkDirectory(current)
            }
        }
        private fun syncDirectory(path: Path) {
            try { FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) } }
            catch (_: IOException) { /* Android/filesystem may not support directory fsync. */ }
            catch (_: UnsupportedOperationException) { /* No weaker non-atomic rename fallback. */ }
        }
    }
}

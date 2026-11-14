package com.nyx.ai.workbench

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID

/**
 * Successful shares stay readable for a conservative grace period; chooser return is not proof
 * that a recipient has finished reading. Only expired snapshots are removed. A full fresh cache
 * rejects another share instead of revoking existing recipients' files. Cleanup is demand-driven.
 * Private ancestors must not be writable by an adversarial same-UID process (as for WorkbenchStore).
 */
class HandoffCachePolicy(
    val maxAgeMs: Long = 24L * 60 * 60 * 1000,
    val maxSnapshots: Int = 16,
    val maxBytes: Long = 32L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxAgeMs > 0 && maxSnapshots in 1..MAX_SCAN && maxBytes > 0) { "Invalid share cache limits." }
    }

    /** Retains the file on successful preparation; rolls back only this new snapshot on failure. */
    fun <T> snapshot(base: File, fileName: String, bytes: ByteArray, prepare: (File) -> T): T = synchronized(lock) {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}").matches(fileName) && ".." !in fileName) { "Unsafe artifact filename." }
        require(bytes.size.toLong() <= maxBytes) { "Artifact exceeds share cache byte limit." }
        val now = clock().also { require(it >= 0) { "Invalid share cache clock." } }
        val root = directory(base)
        val remaining = prune(root, now)
        require(remaining.size < maxSnapshots) { "Share cache is full of fresh snapshots; try again after they expire." }
        val total = remaining.fold(0L) { sum, item ->
            require(item.bytes <= maxBytes - sum) { "Share cache byte limit reached; fresh shares were retained." }
            sum + item.bytes
        }
        require(bytes.size.toLong() <= maxBytes - total) { "Share cache byte limit reached; fresh shares were retained." }
        checkAncestors(root)
        val folder = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
        val file = folder.resolve(fileName)
        try {
            checkAncestors(folder)
            FileChannel.open(file, CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                val payload = ByteBuffer.wrap(bytes)
                while (payload.hasRemaining()) channel.write(payload)
                channel.force(true)
                require(channel.size() == bytes.size.toLong()) { "Staged artifact size mismatch." }
                channel.position(0)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteBuffer.allocate(8192)
                while (channel.read(buffer) != -1) {
                    buffer.flip()
                    digest.update(buffer)
                    buffer.clear()
                }
                require(digest.digest().contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes))) { "Staged artifact digest mismatch." }
            }
            checkRegular(file)
            require(file.toFile().setReadOnly()) { "Could not protect staged artifact." }
            Files.setLastModifiedTime(folder, FileTime.fromMillis(now))
            prepare(file.toFile())
        } catch (error: Exception) {
            // Never recursively delete, and never follow a replaced folder/link on rollback.
            try {
                checkAncestors(folder)
                Files.deleteIfExists(file)
                Files.deleteIfExists(folder)
            } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** Validates the whole bounded, shallow layout before deleting any expired snapshot. */
    fun cleanup(base: File): Int = synchronized(lock) {
        val now = clock().also { require(it >= 0) { "Invalid share cache clock." } }
        val root = directory(base)
        val snapshots = scan(root)
        val expired = snapshots.filter { expired(it, now) }
        expired.forEach { remove(it) }
        expired.size
    }

    private data class Snapshot(val directory: Path, val file: Path?, val bytes: Long, val timestamp: Long)

    private fun prune(root: Path, now: Long): List<Snapshot> {
        val snapshots = scan(root)
        snapshots.filter { expired(it, now) }.forEach { remove(it) }
        return snapshots.filterNot { expired(it, now) }
    }

    // Future/unknown timestamps fail conservatively: a clock rollback must not evict a fresh share.
    private fun expired(item: Snapshot, now: Long): Boolean =
        item.timestamp >= 0 && now >= item.timestamp && now - item.timestamp >= maxAgeMs

    private fun scan(root: Path): List<Snapshot> {
        checkAncestors(root)
        return Files.newDirectoryStream(root).use { entries ->
            val snapshots = mutableListOf<Snapshot>()
            for (folder in entries) {
                require(snapshots.size < MAX_SCAN) { "Share cache layout exceeds bounded cleanup scan." }
                require(UUID_NAME.matches(folder.fileName.toString())) { "Unexpected share cache entry." }
                checkAncestors(folder)
                val attrs = attributes(folder)
                var file: Path? = null
                var bytes = 0L
                Files.newDirectoryStream(folder).use { children ->
                    for (child in children) {
                        require(file == null) { "Unexpected share snapshot layout." }
                        bytes = checkRegular(child).size()
                        file = child
                    }
                }
                snapshots += Snapshot(folder, file, bytes, attrs.lastModifiedTime().toMillis())
            }
            snapshots
        }
    }

    private fun remove(item: Snapshot) {
        checkAncestors(item.directory)
        item.file?.let { checkRegular(it); Files.delete(it) }
        Files.delete(item.directory) // Empty directory only; never descend into unknown content.
    }

    private fun directory(base: File): Path {
        val root = base.toPath().toAbsolutePath().normalize()
        checkAncestors(requireNotNull(root.parent))
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(root) }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* Validate the winner below. */ }
        }
        checkAncestors(root)
        return root
    }

    private fun checkAncestors(path: Path) {
        var current = path.root
        path.forEach { part ->
            current = current.resolve(part)
            val attrs = attributes(current)
            require(attrs.isDirectory && !attrs.isSymbolicLink) { "Unsafe artifact handoff directory." }
        }
    }

    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun checkRegular(path: Path): BasicFileAttributes = attributes(path).also {
        require(it.isRegularFile && !it.isSymbolicLink) { "Unsafe artifact handoff file." }
    }

    private companion object {
        // One file per UUID directory (also recognizes the existing v0.8.9 snapshot layout).
        const val MAX_SCAN = 512
        val UUID_NAME = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val lock = Any() // Quota reservation + preparation are serialized across policy instances.
    }
}

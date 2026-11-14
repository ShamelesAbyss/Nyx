package com.nyx.ai.workbench

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** No filesystem input. Classic single-disk ZIP only; ZIP64/encryption/symlinks unsupported. */
object ZipBundleWriter {
    private const val MAX_ENTRIES = 100
    private const val MAX_EXPANDED = 32L * 1024 * 1024
    private const val MAX_ENTRY = 8L * 1024 * 1024

    fun write(entries: Map<String, ByteArray>): ByteArray {
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES)
        require(entries.values.sumOf { it.size.toLong() } <= MAX_EXPANDED)
        entries.forEach { (name, data) -> safeEntry(name); require(data.size.toLong() <= MAX_ENTRY) }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            entries.toSortedMap().forEach { (name, data) ->
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(data) }.value
                    time = 315532800000L // 1980-01-01: deterministic supported DOS timestamp
                }
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
            }
        }
        return output.toByteArray().also { ArtifactFormats.requireValid(ArtifactFormat.ZIP, it) }
    }

    internal fun safeEntry(name: String) {
        require(name.length in 1..240 && !name.startsWith('/') && '\\' !in name && ':' !in name)
        val parts = name.removeSuffix("/").split('/')
        require(parts.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) && ".." !in it && !reserved(it) }) { "Unsafe ZIP entry path" }
    }

    internal fun reserved(name: String): Boolean {
        val base = name.substringBefore('.').uppercase()
        return base in setOf("CON", "PRN", "AUX", "NUL") || base.matches(Regex("(?:COM|LPT)[0-9]"))
    }

    internal fun validate(b: ByteArray) {
        fun u16(p: Int): Int { require(p >= 0 && p + 2 <= b.size) { "Truncated ZIP" }; return (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8) }
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        fun index(n: Long): Int { require(n in 0..b.size.toLong()) { "Invalid ZIP offset" }; return n.toInt() }
        require(b.size >= 22) { "Truncated ZIP" }
        var eocd = -1
        for (p in b.size - 22 downTo maxOf(0, b.size - 65557)) {
            if (u32(p) == 0x06054b50L && p + 22 + u16(p + 20) == b.size) { eocd = p; break }
        }
        require(eocd >= 0) { "ZIP end directory missing" }
        require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0) { "Multi-disk ZIP unsupported" }
        val count = u16(eocd + 10)
        require(count in 1..MAX_ENTRIES && u16(eocd + 8) == count) { "ZIP entry count exceeds limit or disagrees" }
        val centralSize = u32(eocd + 12)
        val centralStart = index(u32(eocd + 16))
        require(centralStart.toLong() + centralSize == eocd.toLong()) { "ZIP directory bounds mismatch" }
        val names = mutableSetOf<String>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var pos = centralStart
        var expandedTotal = 0L
        repeat(count) {
            require(pos + 46 <= eocd && u32(pos) == 0x02014b50L) { "Invalid ZIP central directory" }
            val flags = u16(pos + 8)
            require(flags and (0x0800 or 0x0008).inv() == 0) { "Encrypted or unsupported ZIP flags" }
            val method = u16(pos + 10)
            require(method == 0 || method == 8) { "Unsupported ZIP compression" }
            val crc = u32(pos + 16)
            val compressed = u32(pos + 20)
            val expanded = u32(pos + 24)
            require(expanded <= MAX_ENTRY && compressed <= b.size && expanded != 0xffffffffL) { "ZIP entry too large or ZIP64 unsupported" }
            expandedTotal += expanded
            require(expandedTotal <= MAX_EXPANDED) { "ZIP expanded-byte quota exceeded" }
            val nl = u16(pos + 28); val xl = u16(pos + 30); val cl = u16(pos + 32)
            require(u16(pos + 34) == 0 && pos.toLong() + 46 + nl + xl + cl <= eocd) { "ZIP central entry truncated" }
            val rawName = b.copyOfRange(pos + 46, pos + 46 + nl)
            val name = ArtifactFormats.utf8(rawName)
            safeEntry(name); require(names.add(name.lowercase())) { "Duplicate ZIP entry" }
            val mode = (u32(pos + 38) shr 16).toInt() and 0xf000
            require(mode == 0 || mode == 0x8000 || mode == 0x4000) { "ZIP symlink or special file forbidden" }
            require(!name.endsWith('/') || expanded == 0L) { "ZIP directory contains data" }
            val local = index(u32(pos + 42))
            require(local + 30 <= centralStart && u32(local) == 0x04034b50L) { "ZIP local header missing" }
            require(u16(local + 6) == flags && u16(local + 8) == method) { "ZIP header disagreement" }
            val lnl = u16(local + 26); val lxl = u16(local + 28)
            val dataStart = index(local.toLong() + 30 + lnl + lxl)
            require(dataStart <= centralStart && lnl == nl && b.copyOfRange(local + 30, local + 30 + lnl).contentEquals(rawName)) { "ZIP filename mismatch" }
            val dataEnd = index(dataStart.toLong() + compressed)
            require(dataEnd <= centralStart) { "ZIP compressed data overlaps directory" }
            var localEnd = dataEnd
            if (flags and 8 == 0) {
                require(u32(local + 14) == crc && u32(local + 18) == compressed && u32(local + 22) == expanded) { "ZIP local sizes/CRC disagree" }
            } else {
                if (u32(localEnd) == 0x08074b50L) localEnd += 4
                require(localEnd + 12 <= centralStart && u32(localEnd) == crc && u32(localEnd + 4) == compressed && u32(localEnd + 8) == expanded) { "ZIP invalid data descriptor" }
                localEnd += 12
            }
            val computed = CRC32()
            var actual = 0L
            if (method == 0) {
                require(compressed == expanded) { "Stored ZIP size mismatch" }
                computed.update(b, dataStart, compressed.toInt()); actual = compressed
            } else {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(b, dataStart, compressed.toInt())
                    val chunk = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(chunk)
                        require(n > 0 || inflater.finished()) { "Truncated or invalid ZIP deflate stream" }
                        actual += n
                        require(actual <= expanded && actual <= MAX_ENTRY) { "ZIP expansion exceeds declared size" }
                        computed.update(chunk, 0, n)
                    }
                    require(inflater.bytesRead == compressed) { "ZIP trailing compressed data" }
                } finally { inflater.end() }
            }
            require(actual == expanded && computed.value == crc) { "ZIP checksum/size mismatch" }
            ranges.add(local to localEnd)
            pos += 46 + nl + xl + cl
        }
        require(pos == eocd) { "ZIP unexpected central directory data" }
        var end = 0
        ranges.sortedBy { it.first }.forEach { (start, stop) ->
            require(start == end && stop >= start) { "ZIP overlapping/unregistered local data" }; end = stop
        }
        require(end == centralStart) { "ZIP unregistered local entries or prefix data" }
    }
}

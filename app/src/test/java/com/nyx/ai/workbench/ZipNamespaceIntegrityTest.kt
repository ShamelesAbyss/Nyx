package com.nyx.ai.workbench

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipNamespaceIntegrityTest {
    private fun standardZip(names: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith('/')) zip.write("fixture".toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun ambiguousNamespacesRejectedByWriterAndIndependentValidatorInBothOrders() {
        val conflicts = listOf(listOf("a", "a/b.txt"), listOf("a", "a/"),
            listOf("A", "a/b.txt"), listOf("a.txt", "a.txt."), listOf("a./b.txt"),
            listOf("a/b", "a/b/c.txt"), listOf("A/", "a/"))
        conflicts.forEach { names ->
            listOf(names, names.reversed()).forEach { order ->
                val bytes = standardZip(order)
                val validation = ArtifactFormats.validate(ArtifactFormat.ZIP, bytes)
                assertFalse("$order: ${validation.note}", validation.valid)
                val entries = order.associateWith { if (it.endsWith('/')) byteArrayOf() else "fixture".toByteArray() }
                assertThrows("$order", IllegalArgumentException::class.java) { ZipBundleWriter.write(entries) }
            }
        }
    }

    @Test fun explicitEmptyDirectoriesAndSafeChildrenRemainSupportedInAnyOrder() {
        val names = listOf("Folder/", "folder/child.txt", "folder/nested/", "folder/nested/report.md")
        listOf(names, names.reversed()).forEach { order ->
            val validation = ArtifactFormats.validate(ArtifactFormat.ZIP, standardZip(order))
            assertTrue(validation.note, validation.valid)
            val entries = order.associateWith { if (it.endsWith('/')) byteArrayOf() else "fixture".toByteArray() }
            val stored = ZipBundleWriter.write(entries)
            assertArrayEquals(stored, ZipBundleWriter.write(entries))
            assertTrue(ArtifactFormats.validate(ArtifactFormat.ZIP, stored).valid)
        }
    }

    @Test fun directoryModeMustAgreeWithEntryName() {
        val bytes = ZipBundleWriter.write(mapOf("a.txt" to "fixture".toByteArray()))
        val central = (0 until bytes.size - 3).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
            bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[central + 41] = 0x40 // UNIX directory mode with file name.
        assertFalse(ArtifactFormats.validate(ArtifactFormat.ZIP, bytes).valid)
    }
}

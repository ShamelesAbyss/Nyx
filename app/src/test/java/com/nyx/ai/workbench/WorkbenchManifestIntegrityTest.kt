package com.nyx.ai.workbench

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WorkbenchManifestIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun numberFixture(): String {
        val artifact = ArtifactRecord("00000000-0000-0000-0000-000000000001", "a.txt", ArtifactFormat.TEXT,
            3, "a".repeat(64), 7, relativePath = "artifacts/a.txt")
        val source = ResearchSource("https://example.com/source", fetchedAt = 7, bytes = 3,
            sha256 = "a".repeat(64), id = "00000000-0000-0000-0000-000000000002")
        val imported = ImportRecord("00000000-0000-0000-0000-000000000003", "in.txt", 3,
            "a".repeat(64), 7, "text/plain", "imports/00000000-0000-0000-0000-000000000003.bin")
        return ArtifactFormats.utf8(WorkbenchManifestCodec.encode(WorkbenchManifest("objective", 7, 7,
            listOf(source), listOf(artifact), listOf(imported))))
    }

    private fun replaceField(json: String, section: String?, key: String, token: String): String {
        val o = JSONObject(json)
        val target = if (section == null) o else o.getJSONArray(section).getJSONObject(0)
        target.put(key, "BEDROCK_NUMERIC_TOKEN")
        return o.toString().replace("\"BEDROCK_NUMERIC_TOKEN\"", token)
    }

    @Test fun everyNumericSchemaFieldRejectsFractionStringExponentAndOverflow() {
        val fields = listOf(null to "version", null to "createdAt", null to "updatedAt",
            "sources" to "fetchedAt", "sources" to "bytes", "artifacts" to "createdAt",
            "artifacts" to "bytes", "imports" to "importedAt", "imports" to "bytes")
        fields.forEach { (section, key) ->
            listOf("1.9", "\"1\"", "1e0", "9223372036854775808", "-9223372036854775809", "true", "null").forEach { token ->
                val bytes = replaceField(numberFixture(), section, key, token).toByteArray()
                assertThrows("$section.$key = $token", Exception::class.java) { WorkbenchManifestCodec.decode(bytes) }
            }
        }
        listOf("4294967297", "-4294967295").forEach { token ->
            assertThrows(Exception::class.java) { WorkbenchManifestCodec.decode(replaceField(numberFixture(), null, "version", token).toByteArray()) }
        }
    }

    @Test fun normalV1AndMaximumIntegralTimestampsRoundTripExactly() {
        val m = WorkbenchManifest("objective", Long.MAX_VALUE, 0)
        assertEquals(m, WorkbenchManifestCodec.decode(WorkbenchManifestCodec.encode(m)))
        val fixture = numberFixture().toByteArray()
        assertArrayEquals(fixture, WorkbenchManifestCodec.encode(WorkbenchManifestCodec.decode(fixture)))
        // Ordinary deliverable JSON continues to support decimal and exponent numbers.
        assertTrue(ArtifactFormats.validate(ArtifactFormat.JSON, "{\"value\":1.9,\"other\":1e2}".toByteArray()).valid)
    }

    @Test fun wrongStringFieldTypesNeverCoerceOnAndroidOrDesktop() {
        listOf("objectiveId", "status").forEach { key ->
            val changed = JSONObject(numberFixture()).put(key, 123).toString().toByteArray()
            assertThrows(Exception::class.java) { WorkbenchManifestCodec.decode(changed) }
        }
    }

    @Test fun reopeningMalformedNumericMetadataPreservesAllEvidenceAndOrphans() {
        val mutations = listOf(null to "version", null to "createdAt", "artifacts" to "bytes",
            "sources" to "fetchedAt", "imports" to "importedAt")
        mutations.forEachIndexed { index, (section, key) ->
            val root = temporary.newFolder("numeric-$index")
            val store = WorkbenchStore(root, "objective")
            store.writeArtifact("a.txt", ArtifactFormat.TEXT, "abc".toByteArray())
            val source = store.recordSource(ResearchSource("https://example.com/source"), "abc".toByteArray())
            val imported = store.importFile("in.txt", "abc".toByteArray())
            val home = File(root, "objective")
            File(home, "artifacts/orphan.txt").writeText("not registered; preserve on invalid metadata")
            val metadata = File(home, "meta/manifest.json")
            metadata.writeText(replaceField(metadata.readText(), section, key, "1.9"))
            val paths = listOf(metadata, File(home, "artifacts/a.txt"), File(home, "sources/${source.id}.txt"),
                File(home, imported.relativePath), File(home, "artifacts/orphan.txt"))
            val before = paths.map { it.readBytes() }
            assertThrows(IOException::class.java) { WorkbenchStore(root, "objective") }
            paths.forEachIndexed { n, path -> assertArrayEquals(path.toString(), before[n], path.readBytes()) }
        }
    }
}

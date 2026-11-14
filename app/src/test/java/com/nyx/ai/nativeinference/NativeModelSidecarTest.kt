package com.nyx.ai.nativeinference

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class NativeModelSidecarTest {
    @Test fun missingCacheIsRebuiltAndRecreationUsesPersistedMetadata() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory)
        val sidecar = File(directory, "${file.name}.json")
        var persisted = 0
        val recovered = NativeModelSidecar.readOrRecover(file, sidecar) { persisted++; persist(file, sidecar, it) }
        val recreated = NativeModelSidecar.readOrRecover(file, sidecar) { fail("Valid persisted cache was not reused") }
        assertEquals(recovered, recreated)
        assertEquals(1, persisted)
        assertTrue(sidecar.isFile)
        assertEquals(setOf(file.name, sidecar.name), directory.listFiles()!!.map { it.name }.toSet())
    }
    @Test fun malformedSchemaStaleSizeAndInvalidDimensionsRecoverFromWeights() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory)
        val metadata = GgufMetadataParser.read(file)
        val sidecar = File(directory, "${file.name}.json")
        fun valid() = json(file, NativeCachedMetadata(metadata, 123))
        val corrupt = listOf("{broken", valid().put("schema", 2).toString(), valid().put("sizeBytes", file.length() + 1).toString(),
            valid().apply { getJSONObject("gguf").put("blockCount", 0) }.toString(),
            valid().apply { getJSONObject("gguf").put("kvHeads", 3) }.toString(),
            valid().apply { getJSONObject("gguf").put("tensorBytes", file.length() + 1) }.toString(),
            valid().apply { getJSONObject("gguf").put("vocabularySize", 0) }.toString())
        for (bad in corrupt) {
            sidecar.writeText(bad)
            var persisted = false
            val recovered = NativeModelSidecar.readOrRecover(file, sidecar) { persisted = true; persist(file, sidecar, it) }
            assertTrue("Corruption did not trigger strict recovery", persisted)
            assertEquals(metadata, recovered.metadata)
            assertEquals(file.lastModified(), recovered.importedAt)
            assertEquals(recovered, NativeModelSidecar.readOrRecover(file, sidecar) { fail("Repair was not durable") })
            assertEquals(1, JSONObject(sidecar.readText()).getInt("schema"))
        }
    }
    @Test fun oversizedAndEmptySidecarsRecoverWithoutTrustingCache() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory)
        val sidecar = File(directory, "${file.name}.json")
        for (size in listOf(0, NativeModelSidecar.MAX_BYTES.toInt() + 1)) {
            sidecar.writeBytes(ByteArray(size))
            val recovered = NativeModelSidecar.readOrRecover(file, sidecar) { persist(file, sidecar, it) }
            assertEquals("llama", recovered.metadata.architecture)
            assertTrue(sidecar.length() in 1..NativeModelSidecar.MAX_BYTES)
        }
    }
    @Test fun invalidAndTruncatedWeightsCannotAcquireRecoveredMetadata() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory)
        val sidecar = File(directory, "${file.name}.json")
        for (bytes in listOf(ByteArray(24), NativeBedrockFixture.bytes().dropLast(1).toByteArray())) {
            file.writeBytes(bytes)
            sidecar.writeText("{broken")
            assertThrows(InvalidGgufException::class.java) {
                NativeModelSidecar.readOrRecover(file, sidecar) { fail("Invalid weights were persisted") }
            }
        }
    }
    @Test fun failedPersistenceIsNotReportedAsSuccessfulRecovery() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory)
        assertThrows(java.io.IOException::class.java) {
            NativeModelSidecar.readOrRecover(file, File(directory, "missing.json")) { throw java.io.IOException("test-owned failure") }
        }
        Unit
    }
    private fun json(file: File, value: NativeCachedMetadata): JSONObject = JSONObject().put("schema", 1)
        .put("sizeBytes", file.length()).put("importedAt", value.importedAt).put("gguf", NativeModelSidecar.encode(value.metadata))
    private fun persist(file: File, sidecar: File, value: NativeCachedMetadata) {
        val temp = File(sidecar.parentFile, ".test-sidecar.part")
        try {
            FileOutputStream(temp).use { it.write(json(file, value).toString().toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}

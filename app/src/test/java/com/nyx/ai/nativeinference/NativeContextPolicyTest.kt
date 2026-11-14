package com.nyx.ai.nativeinference

import org.junit.Assert.*
import org.junit.Test

class NativeContextPolicyTest {
    @Test fun allocationPaddingAndKvAgreeAtAllEdges() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory)
        val metadata = GgufMetadataParser.read(file)
        for ((requested, allocated) in listOf(512 to 512, 513 to 768, 767 to 768, 768 to 768, 32767 to 32768, 32768 to 32768)) {
            val estimate = metadata.estimate(file.length(), requested)
            assertEquals(allocated, nativeAllocationContext(requested))
            assertEquals(requested, estimate.contextLength)
            assertEquals(allocated, estimate.allocatedContextLength)
            assertEquals(2L * allocated * 1 * (4 + 4) * 2, estimate.kvCacheBytes)
        }
    }
    @Test fun unsafeAndOverflowingRequestsAreRejectedBeforePadding() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, 511, 32769, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { nativeAllocationContext(value) }
        }
    }
    @Test fun admissionChargesPaddingNotTheSmallerUsableContext() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory)
        val metadata = GgufMetadataParser.read(file).copy(blockCount = 4096, embeddingLength = 128, attentionHeads = 2, kvHeads = 2)
        val alignedWorking = metadata.estimate(file.length(), 512).estimatedWorkingSetBytes!!
        // At this size the 20% dynamic margin exceeds the configured 256 MiB floor.
        val available = alignedWorking * 5 / 4 + 4
        assertTrue(nativeAdmission(metadata, file.length(), 512, available, 0, false, 256).admitted)
        val padded = nativeAdmission(metadata, file.length(), 513, available, 0, false, 256)
        assertFalse(padded.admitted)
        assertEquals(768, padded.estimate.allocatedContextLength)
        assertTrue(padded.reason.contains("Insufficient"))
    }
    @Test fun trainingLimitAppliesToUsableContextAndDoesNotRejectAllocationPadding() = NativeBedrockFixture.directory { directory ->
        val file = NativeBedrockFixture.model(directory, trainingContext = 513)
        val metadata = GgufMetadataParser.read(file)
        val available = 8L * 1024 * 1024 * 1024
        assertTrue(nativeAdmission(metadata, file.length(), 513, available, 0, false, 512).admitted)
        assertFalse(nativeAdmission(metadata, file.length(), 514, available, 0, false, 512).admitted)
        assertFalse(nativeAdmission(metadata, file.length(), 513, available, 0, true, 512).admitted)
    }
}

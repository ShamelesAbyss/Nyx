package com.nyx.ai.nativeinference

import com.nyx.ai.network.InferenceOptions
import org.junit.Assert.*
import org.junit.Test

class NativeRequestOptionsTest {
    @Test fun nullTokenBoundPreservesConfiguredNativeDefault() {
        assertEquals(512, InferenceOptions().nativeOutputTokenLimit(512))
        assertEquals(256, InferenceOptions(responseSchema = "ignored").nativeOutputTokenLimit(256))
    }

    @Test fun explicitNativeBudgetUsesActualRuntimeSupportedCeiling() {
        assertEquals(1, InferenceOptions(maxOutputTokens = 1).nativeOutputTokenLimit(512))
        assertEquals(32, InferenceOptions(maxOutputTokens = 32).nativeOutputTokenLimit(512))
        assertEquals(4096, InferenceOptions(maxOutputTokens = 4096).nativeOutputTokenLimit(512))
        assertEquals(4096, InferenceOptions(maxOutputTokens = 9999).nativeOutputTokenLimit(512))
    }

    @Test fun translatingTokenLimitDoesNotMutateContextThreadsTemperatureOrSchema() {
        val options = InferenceOptions(temperature = 0.2, contextLength = 8192, cpuThreads = 7,
            maxOutputTokens = 32, responseSchema = "ignored schema")
        assertEquals(32, options.nativeOutputTokenLimit(512))
        assertEquals(8192, options.contextLength); assertEquals(7, options.cpuThreads)
        assertEquals(0.2, options.temperature, 0.0); assertEquals("ignored schema", options.responseSchema)
    }
}

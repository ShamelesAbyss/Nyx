package com.nyx.ai.network

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSummaryTest {
    @Test fun providerCountsAndNanosecondsProduceMeasuredRate() {
        val result = ModelDiagnosticResult("fixture", "model", stats = InferenceTokenStats(outputTokens = 10, generationDurationNanos = 2_000_000_000))
        assertTrue(result.summary.contains("5.00 tokens/s"))
        assertTrue(result.summary.contains("not a workload benchmark"))
    }
    @Test fun missingOrZeroDurationDoesNotInventThroughput() {
        assertTrue(ModelDiagnosticResult("fixture", "model").summary.contains("throughput: unavailable"))
        val result = ModelDiagnosticResult("fixture", "model", stats = InferenceTokenStats(outputTokens = 10, generationDurationNanos = 0))
        assertTrue(result.summary.contains("throughput: unavailable"))
    }
}

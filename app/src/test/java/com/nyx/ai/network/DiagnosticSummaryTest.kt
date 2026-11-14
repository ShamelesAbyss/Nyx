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
    @Test fun providerPromptEvaluationTimingIsReportedSeparately() {
        val result = ModelDiagnosticResult("fixture", "model", stats = InferenceTokenStats(
            promptTokens = 10,
            outputTokens = 5,
            generationDurationNanos = 2_000_000_000,
            totalDurationNanos = 4_000_000_000,
            loadDurationNanos = 1_000_000_000,
            promptEvalDurationNanos = 1_000_000_000,
        ))
        assertTrue(result.summary.contains("model load 1000ms"))
        assertTrue(result.summary.contains("prompt eval 1000ms"))
        assertTrue(result.summary.contains("Measured prompt rate: 10.00 tokens/s"))
    }
}

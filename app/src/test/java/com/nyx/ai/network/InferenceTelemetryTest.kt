package com.nyx.ai.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceTelemetryTest {
    @Test fun measuredProviderFieldsProduceRatesWithoutInventingQueueTiming() {
        val summary = InferenceTelemetry.summary(
            stats = InferenceTokenStats(
                promptTokens = 12,
                outputTokens = 3,
                generationDurationNanos = 4_000_000_000,
                totalDurationNanos = 9_000_000_000,
                loadDurationNanos = 2_000_000_000,
                promptEvalDurationNanos = 1_000_000_000,
            ),
            firstTextMillis = 900,
            elapsedMillis = 9_500,
            streamedCharacters = 64,
            reasoningCharacters = 12,
        )
        assertTrue(summary.contains("first text 900ms"))
        assertTrue(summary.contains("provider model load 2000ms"))
        assertTrue(summary.contains("provider prompt eval 1000ms"))
        assertTrue(summary.contains("provider generation 4000ms"))
        assertTrue(summary.contains("provider total 9000ms"))
        assertTrue(summary.contains("Prompt tokens 12; output tokens 3"))
        assertTrue(summary.contains("0.75 tokens/s"))
        assertTrue(summary.contains("12.00 tokens/s"))
        assertTrue(summary.contains("64 plan characters"))
        assertTrue(summary.contains("12 reasoning characters"))
        assertTrue(summary.contains("Queue wait is not provider-separated"))
        assertFalse(summary.contains("64 tokens"))
    }

    @Test fun absentOrInvalidMetricsStayExplicitlyUnavailable() {
        val summary = InferenceTelemetry.summary(null, null, 500, 10)
        assertTrue(summary.contains("first text unavailable"))
        assertTrue(summary.contains("provider model load unavailable"))
        assertTrue(summary.contains("provider prompt eval unavailable"))
        assertTrue(summary.contains("Prompt tokens unavailable; output tokens unavailable"))
        assertTrue(summary.contains("Measured output rate unavailable"))
        assertTrue(summary.contains("measured prompt rate unavailable"))
    }

    @Test fun ratesRequirePositiveProviderDurationAndNonnegativeTokens() {
        assertTrue(InferenceTelemetry.rate(10, 0).contains("unavailable"))
        assertTrue(InferenceTelemetry.rate(-1, 1_000).contains("unavailable"))
        assertTrue(InferenceTelemetry.rate(0, 1_000_000_000).contains("0.00 tokens/s"))
        assertTrue(InferenceTelemetry.duration(-1).contains("unavailable"))
    }
}

package com.nyx.ai.network

import java.util.Locale

/**
 * Formats only observations the provider actually supplied. Character counts are never converted
 * into token counts, and a wall-clock total is never presented as a separate load/prompt phase.
 */
object InferenceTelemetry {
    fun summary(
        stats: InferenceTokenStats?,
        firstTextMillis: Long?,
        elapsedMillis: Long,
        streamedCharacters: Int,
        reasoningCharacters: Int = 0,
    ): String = buildString {
        append("Planning telemetry (provider-reported where available): ")
        append("first text ${firstTextMillis?.let { "${it}ms" } ?: "unavailable"}; ")
        append("request wall ${elapsedMillis.coerceAtLeast(0)}ms")
        append("; provider model load ${duration(stats?.loadDurationNanos)}")
        append("; provider prompt eval ${duration(stats?.promptEvalDurationNanos)}")
        append("; provider generation ${duration(stats?.generationDurationNanos)}")
        append("; provider total ${duration(stats?.totalDurationNanos)}. ")
        append("Streamed $streamedCharacters plan characters")
        if (reasoningCharacters > 0) append(" and $reasoningCharacters reasoning characters (not plan text)")
        append(". Prompt tokens ${stats?.promptTokens ?: "unavailable"}; output tokens ${stats?.outputTokens ?: "unavailable"}. ")
        append("Measured output rate ${rate(stats?.outputTokens, stats?.generationDurationNanos)}; ")
        append("measured prompt rate ${rate(stats?.promptTokens, stats?.promptEvalDurationNanos)}. ")
        append("Queue wait is not provider-separated; token counts are never inferred from character counts.")
    }

    fun duration(nanos: Long?): String =
        if (nanos == null || nanos < 0) "unavailable" else "${nanos / 1_000_000}ms"

    fun rate(tokens: Long?, durationNanos: Long?): String {
        if (tokens == null || tokens < 0 || durationNanos == null || durationNanos <= 0) return "unavailable"
        val value = tokens.toDouble() * 1_000_000_000.0 / durationNanos
        if (!value.isFinite()) return "unavailable"
        return String.format(Locale.ROOT, "%.2f tokens/s", value)
    }
}

package com.nyx.ai.nativeinference

import com.nyx.ai.network.InferenceOptions

/** NativeRuntime/JNI supports 1..4096 generated tokens. Null preserves the configured default. */
internal fun InferenceOptions.nativeOutputTokenLimit(defaultLimit: Int): Int =
    maxOutputTokens?.coerceAtMost(4096) ?: defaultLimit

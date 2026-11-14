package com.nyx.ai.nativeinference

import android.app.ActivityManager
import android.content.Context
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.InferenceOptions
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

class NativeAdmissionException(message: String) : IOException(message)
enum class NativePhase { UNLOADED, LOADING, READY, GENERATING, ERROR }
data class NativeRuntimeStatus(
    val phase: NativePhase = NativePhase.UNLOADED,
    val modelPath: String? = null,
    val modelName: String? = null,
    /** Usable request limit; allocation padding never extends generation. */
    val contextLength: Int? = null,
    val estimate: NativeMemoryEstimate? = null,
    val availableMemBytes: Long? = null,
    val detail: String? = null,
    val lastLoadMillis: Long? = null,
    val generatedTokens: Int? = null,
    val lastGenerateMillis: Long? = null,
    val lastUsedAtEpochMs: Long? = null,
    val allocatedContextLength: Int? = null,
) {
    val loaded: Boolean get() = modelPath != null && phase in setOf(NativePhase.READY, NativePhase.GENERATING)
}
data class NativeGenerationOptions(val maxOutputTokens: Int = 512, val seed: Int = -1)
data class NativeAdmission(val admitted: Boolean, val availableMemBytes: Long, val safetyMarginBytes: Long, val estimate: NativeMemoryEstimate, val reason: String)

/** Single production session. The test seam is internal and cannot change process-global JNI ownership. */
object NativeRuntime {
    @Volatile private var appContext: Context? = null
    private val session = NativeSessionRuntime(::admission)
    val status: StateFlow<NativeRuntimeStatus> get() = session.status
    val currentOptions: InferenceOptions? get() = session.currentOptions

    /** Stores application context only. */
    fun initialize(context: Context) { appContext = context.applicationContext }
    fun cancel() = session.cancel()
    suspend fun load(modelPath: String, options: InferenceOptions = InferenceOptions()): NativeRuntimeStatus = session.load(modelPath, options)
    suspend fun unload() = session.unload()
    suspend fun generate(
        modelPath: String,
        messages: List<ApiMessage>,
        options: InferenceOptions = InferenceOptions(),
        generation: NativeGenerationOptions = NativeGenerationOptions(),
        onToken: (String) -> Unit = {},
    ): String = session.generate(modelPath, messages, options, generation, onToken)

    fun admission(metadata: GgufMetadata, sizeBytes: Long, contextLength: Int): NativeAdmission {
        val context = appContext ?: throw IllegalStateException("Initialize NativeRuntime with an Android Context first")
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        val configuredMb = context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)
            .getInt("native_memory_margin_mb", 512)
        return nativeAdmission(metadata, sizeBytes, contextLength, memory.availMem, memory.threshold, memory.lowMemory, configuredMb)
    }

    internal suspend fun <T> withUnloadedModel(path: String, block: () -> T): T = session.withUnloadedModel(path, block)
}

/** Pure admission calculation for deterministic tests; Android memory availability remains a snapshot. */
internal fun nativeAdmission(
    metadata: GgufMetadata,
    sizeBytes: Long,
    contextLength: Int,
    availableMemBytes: Long,
    thresholdBytes: Long,
    lowMemory: Boolean,
    configuredMarginMb: Int,
): NativeAdmission {
    val estimate = metadata.estimate(sizeBytes, contextLength)
    val margin = maxOf(configuredMarginMb.coerceIn(256, 2048).toLong() * 1024 * 1024, availableMemBytes / 5, thresholdBytes)
    val working = estimate.estimatedWorkingSetBytes
    val admitted = contextLength <= metadata.trainingContext && !lowMemory && working != null && working <= availableMemBytes - margin
    val reason = when {
        contextLength > metadata.trainingContext -> "Requested context exceeds model training context ${metadata.trainingContext}"
        lowMemory -> "Android reports low system memory"
        working == null -> "Cannot qualify this model's KV cache; use a supported conventional transformer"
        !admitted -> "Insufficient available system memory: weights=${estimate.weightFileBytes}, KV=${estimate.kvCacheBytes}, compute=${estimate.computeReserveBytes}, available=$availableMemBytes, margin=$margin bytes"
        else -> "Conservative estimate fits currently available system memory; actual native peak is not guaranteed"
    }
    return NativeAdmission(admitted, availableMemBytes, margin, estimate, reason)
}

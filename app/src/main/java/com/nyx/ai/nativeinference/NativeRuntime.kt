package com.nyx.ai.nativeinference

import android.app.ActivityManager
import android.content.Context
import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.InferenceOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicLong

class NativeAdmissionException(message: String) : IOException(message)
enum class NativePhase { UNLOADED, LOADING, READY, GENERATING, ERROR }
data class NativeRuntimeStatus(
    val phase: NativePhase = NativePhase.UNLOADED,
    val modelPath: String? = null,
    val modelName: String? = null,
    val contextLength: Int? = null,
    val estimate: NativeMemoryEstimate? = null,
    val availableMemBytes: Long? = null,
    val detail: String? = null,
    val lastLoadMillis: Long? = null,
    val generatedTokens: Int? = null,
    val lastGenerateMillis: Long? = null,
    val lastUsedAtEpochMs: Long? = null,
) {
    val loaded: Boolean get() = modelPath != null && phase in setOf(NativePhase.READY, NativePhase.GENERATING)
}
data class NativeGenerationOptions(val maxOutputTokens: Int = 512, val seed: Int = -1)
data class NativeAdmission(val admitted: Boolean, val availableMemBytes: Long, val safetyMarginBytes: Long, val estimate: NativeMemoryEstimate, val reason: String)

object NativeRuntime {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(NativeRuntimeStatus())
    val status: StateFlow<NativeRuntimeStatus> = mutableStatus.asStateFlow()
    @Volatile private var appContext: Context? = null
    @Volatile private var loadedOptions: InferenceOptions? = null
    @Volatile private var libraryLoaded = false
    private val cancellationEpoch = AtomicLong()

    /** The options of the resident native session, if any. Safe for read-only calibration planning. */
    val currentOptions: InferenceOptions? get() = loadedOptions

    /** Call once from the provider/repository constructor, or application integration. Stores application context only. */
    fun initialize(context: Context) { appContext = context.applicationContext }

    fun cancel() {
        cancellationEpoch.incrementAndGet()
        if (libraryLoaded) NativeBindings.cancel()
    }

    suspend fun load(modelPath: String, options: InferenceOptions = InferenceOptions()): NativeRuntimeStatus = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(modelPath, options) }
    }
    suspend fun unload() {
        cancel()
        withContext(Dispatchers.IO) { mutex.withLock { unloadLocked() } }
    }
    suspend fun generate(
        modelPath: String,
        messages: List<ApiMessage>,
        options: InferenceOptions = InferenceOptions(),
        generation: NativeGenerationOptions = NativeGenerationOptions(),
        onToken: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty() && messages.size <= 256) { "Use 1..256 chat messages" }
        var inputBytes = 0L
        for (message in messages) {
            require(message.content.length <= 1024 * 1024 && message.role.length <= 16) { "Message exceeds native limits" }
            inputBytes += message.content.toByteArray(Charsets.UTF_8).size.toLong() + message.role.length
            require(inputBytes <= 1024 * 1024) { "Messages exceed 1 MiB" }
        }
        require(generation.maxOutputTokens in 1..4096)
        mutex.withLock {
            loadLocked(modelPath, options)
            currentCoroutineContext().ensureActive()
            val callerContext = currentCoroutineContext()
            val ready = mutableStatus.value
            val operationEpoch = cancellationEpoch.get()
            val started = System.nanoTime()
            mutableStatus.value = ready.copy(phase = NativePhase.GENERATING, detail = null, generatedTokens = null, lastGenerateMillis = null)
            try {
                blockingOperation(operationEpoch) {
                    val sink = NativeTokenSink { callerContext.ensureActive(); onToken(it) }
                    val tokens = NativeBindings.generate(messages.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                        messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                        options.temperature.toFloat(), generation.maxOutputTokens, generation.seed, sink)
                    val text = sink.finish()
                    mutableStatus.value = mutableStatus.value.copy(generatedTokens = tokens)
                    text
                }
            } catch (error: CancellationException) {
                mutableStatus.value = mutableStatus.value.copy(generatedTokens = null, detail = "Native inference cancelled")
                throw error
            } catch (error: Exception) {
                mutableStatus.value = mutableStatus.value.copy(detail = error.message)
                throw error
            } finally {
                // blockingOperation joins native work BEFORE the outer mutex is released.
                mutableStatus.value = mutableStatus.value.copy(
                    phase = NativePhase.READY,
                    lastGenerateMillis = (System.nanoTime() - started) / 1_000_000,
                    lastUsedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    fun admission(metadata: GgufMetadata, sizeBytes: Long, contextLength: Int): NativeAdmission {
        val context = appContext ?: throw IllegalStateException("Initialize NativeRuntime with an Android Context first")
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        val estimate = metadata.estimate(sizeBytes, contextLength)
        val configuredMb = context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE)
            .getInt("native_memory_margin_mb", 512).coerceIn(256, 2048)
        val margin = maxOf(configuredMb.toLong() * 1024 * 1024, memory.availMem / 5, memory.threshold)
        val working = estimate.estimatedWorkingSetBytes
        val admitted = contextLength <= metadata.trainingContext && !memory.lowMemory && working != null && working <= memory.availMem - margin
        val reason = when {
            contextLength > metadata.trainingContext -> "Requested context exceeds model training context ${metadata.trainingContext}"
            memory.lowMemory -> "Android reports low system memory"
            working == null -> "Cannot qualify this model's KV cache; use a supported conventional transformer"
            !admitted -> "Insufficient available system memory: weights=${estimate.weightFileBytes}, KV=${estimate.kvCacheBytes}, compute=${estimate.computeReserveBytes}, available=${memory.availMem}, margin=$margin bytes"
            else -> "Conservative estimate fits currently available system memory; actual native peak is not guaranteed"
        }
        return NativeAdmission(admitted, memory.availMem, margin, estimate, reason)
    }

    private suspend fun loadLocked(modelPath: String, options: InferenceOptions): NativeRuntimeStatus {
        val operationEpoch = cancellationEpoch.get()
        validateOptions(options)
        val file = File(modelPath)
        require(file.isAbsolute && '\u0000' !in modelPath) { "Native model ID must be an absolute path" }
        val path = file.canonicalPath
        if (status.value.loaded && status.value.modelPath == path &&
            loadedOptions?.contextLength == options.contextLength && loadedOptions?.cpuThreads == options.cpuThreads) return status.value
        currentCoroutineContext().ensureActive()
        val metadata = GgufMetadataParser.read(file)
        if (options.contextLength > metadata.trainingContext) throw NativeAdmissionException("Requested context exceeds training context ${metadata.trainingContext}")
        if (metadata.chatTemplate.isNullOrBlank()) throw NativeAdmissionException("Model has no default chat template; import an instruct/chat GGUF")
        // Release an old session before measuring admission; no two models resident at once.
        if (libraryLoaded) unloadLocked()
        val admission = admission(metadata, file.length(), options.contextLength)
        if (!admission.admitted) throw NativeAdmissionException(admission.reason)
        mutableStatus.value = NativeRuntimeStatus(NativePhase.LOADING, path, metadata.name ?: file.name,
            options.contextLength, admission.estimate, admission.availableMemBytes)
        val started = System.nanoTime()
        try {
            val actualContext = blockingOperation(operationEpoch) { NativeBindings.load(path.toByteArray(Charsets.UTF_8), options.contextLength, options.cpuThreads) }
            loadedOptions = options
            mutableStatus.value = mutableStatus.value.copy(phase = NativePhase.READY, contextLength = actualContext,
                lastLoadMillis = (System.nanoTime() - started) / 1_000_000, lastUsedAtEpochMs = System.currentTimeMillis())
            return mutableStatus.value
        } catch (error: Throwable) {
            // A coroutine can be cancelled immediately after successful native load but before resume.
            // Always free in that case; never leave an undisclosed resident session.
            if (libraryLoaded) NativeBindings.unload()
            loadedOptions = null
            mutableStatus.value = NativeRuntimeStatus(if (error is CancellationException) NativePhase.UNLOADED else NativePhase.ERROR, detail = error.message)
            throw error
        }
    }
    private fun unloadLocked() {
        if (libraryLoaded) NativeBindings.unload()
        loadedOptions = null
        mutableStatus.value = NativeRuntimeStatus()
    }
    internal suspend fun <T> withUnloadedModel(path: String, block: () -> T): T = withContext(Dispatchers.IO) {
        cancel()
        mutex.withLock {
            if (mutableStatus.value.modelPath == path) unloadLocked()
            block()
        }
    }
    private fun validateOptions(options: InferenceOptions) {
        require(options.contextLength in 512..32768) { "Native context is bounded to 512..32768" }
        require(options.cpuThreads in 1..32) { "Native threads must be 1..32" }
        require(options.temperature.isFinite() && options.temperature in 0.0..2.0) { "Temperature must be finite and 0..2" }
    }

    /** The cancellation callback is armed before starting IO. Cleanup joins it even on prompt cancellation. */
    private suspend fun <T> blockingOperation(operationEpoch: Long, block: () -> T): T = coroutineScope {
        NativeBindings.ensureLibrary()
        libraryLoaded = true
        currentCoroutineContext().ensureActive()
        NativeBindings.beginOperation()
        if (cancellationEpoch.get() != operationEpoch) NativeBindings.cancel()
        var worker: Job? = null
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { NativeBindings.cancel() }
                worker = launch(Dispatchers.IO + NonCancellable) {
                    try {
                        val result = block()
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { worker?.join() }
        }
    }
}

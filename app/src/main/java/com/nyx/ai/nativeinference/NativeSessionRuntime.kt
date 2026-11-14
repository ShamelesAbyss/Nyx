package com.nyx.ai.nativeinference

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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface NativeBackend {
    fun ensureLibrary()
    fun beginOperation()
    fun cancel()
    fun load(path: ByteArray, context: Int, threads: Int): Int
    fun unload()
    fun generate(roles: Array<ByteArray>, contents: Array<ByteArray>, temperature: Float, maxTokens: Int, seed: Int, sink: NativeTokenSink): Int
}

private object JniNativeBackend : NativeBackend {
    override fun ensureLibrary() = NativeBindings.ensureLibrary()
    override fun beginOperation() = NativeBindings.beginOperation()
    override fun cancel() = NativeBindings.cancel()
    override fun load(path: ByteArray, context: Int, threads: Int): Int = NativeBindings.load(path, context, threads)
    override fun unload() = NativeBindings.unload()
    override fun generate(roles: Array<ByteArray>, contents: Array<ByteArray>, temperature: Float, maxTokens: Int, seed: Int, sink: NativeTokenSink): Int =
        NativeBindings.generate(roles, contents, temperature, maxTokens, seed, sink)
}

/** Only NativeRuntime constructs a production instance; JNI still has one globally serialized session. */
internal class NativeSessionRuntime(
    private val admissionCheck: (GgufMetadata, Long, Int) -> NativeAdmission,
    private val backend: NativeBackend = JniNativeBackend,
) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(NativeRuntimeStatus())
    val status: StateFlow<NativeRuntimeStatus> = mutableStatus.asStateFlow()
    @Volatile private var loadedOptions: InferenceOptions? = null
    @Volatile private var libraryLoaded = false
    private val cancellationEpoch = AtomicLong()
    val currentOptions: InferenceOptions? get() = loadedOptions

    fun cancel() {
        cancellationEpoch.incrementAndGet()
        if (libraryLoaded) backend.cancel()
    }

    suspend fun load(modelPath: String, options: InferenceOptions): NativeRuntimeStatus = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(modelPath, options, cancellationEpoch.get()) }
    }
    suspend fun unload() {
        cancel()
        withContext(Dispatchers.IO) { mutex.withLock { unloadLocked() } }
    }
    suspend fun generate(
        modelPath: String,
        messages: List<ApiMessage>,
        options: InferenceOptions,
        generation: NativeGenerationOptions,
        onToken: (String) -> Unit,
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
            // One request lifetime, including cached/load setup and generation. Never resnapshot after load.
            val requestEpoch = cancellationEpoch.get()
            loadLocked(modelPath, options, requestEpoch)
            checkCancellation(requestEpoch)
            val callerContext = currentCoroutineContext()
            val ready = mutableStatus.value
            val started = System.nanoTime()
            mutableStatus.value = ready.copy(phase = NativePhase.GENERATING, detail = null, generatedTokens = null, lastGenerateMillis = null)
            try {
                blockingOperation(requestEpoch) {
                    val sink = NativeTokenSink {
                        callerContext.ensureActive()
                        if (cancellationEpoch.get() != requestEpoch) throw CancellationException("Native inference cancelled")
                        onToken(it)
                    }
                    val tokens = backend.generate(messages.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray(),
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

    private suspend fun loadLocked(modelPath: String, options: InferenceOptions, requestEpoch: Long): NativeRuntimeStatus {
        validateOptions(options)
        checkCancellation(requestEpoch)
        val file = File(modelPath)
        require(file.isAbsolute && '\u0000' !in modelPath) { "Native model ID must be an absolute path" }
        val path = file.canonicalPath
        if (status.value.loaded && status.value.modelPath == path &&
            loadedOptions?.contextLength == options.contextLength && loadedOptions?.cpuThreads == options.cpuThreads) return status.value
        val metadata = GgufMetadataParser.read(file)
        checkCancellation(requestEpoch)
        if (options.contextLength > metadata.trainingContext) throw NativeAdmissionException("Requested context exceeds training context ${metadata.trainingContext}")
        if (metadata.chatTemplate.isNullOrBlank()) throw NativeAdmissionException("Model has no default chat template; import an instruct/chat GGUF")
        // Release an old session before measuring admission; no two models resident at once.
        if (libraryLoaded) unloadLocked()
        val admission = admissionCheck(metadata, file.length(), options.contextLength)
        if (!admission.admitted) throw NativeAdmissionException(admission.reason)
        val expectedAllocation = nativeAllocationContext(options.contextLength)
        mutableStatus.value = NativeRuntimeStatus(NativePhase.LOADING, path, metadata.name ?: file.name,
            options.contextLength, admission.estimate, admission.availableMemBytes, allocatedContextLength = expectedAllocation)
        val started = System.nanoTime()
        try {
            val actualContext = blockingOperation(requestEpoch) { backend.load(path.toByteArray(Charsets.UTF_8), options.contextLength, options.cpuThreads) }
            if (actualContext != expectedAllocation) throw NativeAdmissionException("Native allocation context disagrees with admission policy: expected=$expectedAllocation actual=$actualContext")
            loadedOptions = options
            mutableStatus.value = mutableStatus.value.copy(phase = NativePhase.READY,
                lastLoadMillis = (System.nanoTime() - started) / 1_000_000, lastUsedAtEpochMs = System.currentTimeMillis())
            return mutableStatus.value
        } catch (error: Throwable) {
            // Cancellation after native load must never leave an undisclosed resident session.
            if (libraryLoaded) backend.unload()
            loadedOptions = null
            mutableStatus.value = NativeRuntimeStatus(if (error is CancellationException) NativePhase.UNLOADED else NativePhase.ERROR, detail = error.message)
            throw error
        }
    }
    private fun unloadLocked() {
        if (libraryLoaded) backend.unload()
        loadedOptions = null
        mutableStatus.value = NativeRuntimeStatus()
    }
    /** Deletion waits for active work, even for this model; explicit Stop/Unload remains immediately cancellable. */
    suspend fun <T> withUnloadedModel(path: String, block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (mutableStatus.value.modelPath == path) unloadLocked()
            block()
        }
    }
    private fun validateOptions(options: InferenceOptions) {
        nativeAllocationContext(options.contextLength)
        require(options.cpuThreads in 1..32) { "Native threads must be 1..32" }
        require(options.temperature.isFinite() && options.temperature in 0.0..2.0) { "Temperature must be finite and 0..2" }
    }
    private suspend fun checkCancellation(requestEpoch: Long) {
        currentCoroutineContext().ensureActive()
        if (cancellationEpoch.get() != requestEpoch) throw CancellationException("Native inference cancelled")
    }

    /** Arm before IO; check explicit cancellation after reset and after native return; join before unlocking. */
    private suspend fun <T> blockingOperation(requestEpoch: Long, block: () -> T): T = coroutineScope {
        backend.ensureLibrary()
        libraryLoaded = true
        checkCancellation(requestEpoch)
        backend.beginOperation()
        if (cancellationEpoch.get() != requestEpoch) backend.cancel()
        checkCancellation(requestEpoch)
        var worker: Job? = null
        val result = try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { backend.cancel() }
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
        checkCancellation(requestEpoch)
        result
    }
}

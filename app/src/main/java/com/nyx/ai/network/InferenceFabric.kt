package com.nyx.ai.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/** Counts are measured requests and streamed chunks, not tokenizer-derived token counts. */
data class InferenceMetrics(
    val queued: Int = 0, val active: Int = 0, val calls: Long = 0,
    val completed: Long = 0, val failed: Long = 0, val cancelled: Long = 0,
    val streamedChunks: Long = 0, val generatedCharacters: Long = 0,
    val lastLatencyMillis: Long = 0, val provider: String = "", val lastFailure: String? = null,
)

object InferenceFabric {
    // One active inference across providers: tasks/tools may run concurrently, models do not.
    // Avoid speculative model switching and native multi-residency on phones.
    private val gate = Semaphore(1)
    private val pending = AtomicInteger()
    private val _metrics = MutableStateFlow(InferenceMetrics())
    val metrics = _metrics.asStateFlow()
    fun wrap(provider: InferenceProvider): InferenceProvider = if (provider is QueuedProvider) provider else QueuedProvider(provider)

    private class QueuedProvider(private val delegate: InferenceProvider) : InferenceProvider by delegate {
        override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions, onToken: (String) -> Unit): ChatResult {
            require(messages.size <= 128) { "Too many messages; compact the conversation first." }
            require(messages.sumOf { it.content.length.toLong() } <= 200_000) { "Conversation exceeds the bounded request size." }
            val count = pending.incrementAndGet()
            if (count > 24) { pending.decrementAndGet(); error("Inference queue is full; wait for active work.") }
            _metrics.update { it.copy(queued = pending.get()) }
            var admitted = false
            try {
                // Selected deadlines include queue waiting; no global metric is used to infer admission.
                return withInferenceActivityTimeouts(options, onToken) { outgoingOptions, observedText ->
                    gate.withPermit {
                        admitted = true
                        pending.decrementAndGet()
                        _metrics.update { it.copy(queued = pending.get(), active = 1, calls = it.calls + 1, provider = delegate.displayName, lastFailure = null) }
                        val started = System.nanoTime()
                        try {
                            suspend fun dispatch(): ChatResult = delegate.chat(model, messages, tools, outgoingOptions) { chunk ->
                                if (chunk.isNotEmpty()) _metrics.update { it.copy(streamedChunks = it.streamedChunks + 1, generatedCharacters = it.generatedCharacters + chunk.length) }
                                observedText(chunk)
                            }
                            val result = if (options.executionTimeouts == null) withTimeout(10 * 60_000L) { dispatch() }
                                else dispatch()
                            currentCoroutineContext().ensureActive()
                            _metrics.update { it.copy(completed = it.completed + 1) }
                            result
                        } catch (cancel: CancellationException) {
                            _metrics.update { it.copy(cancelled = it.cancelled + 1) }; throw cancel
                        } catch (e: Exception) {
                            _metrics.update { it.copy(failed = it.failed + 1, lastFailure = e.javaClass.simpleName) }; throw e
                        } finally {
                            _metrics.update { it.copy(active = 0, lastLatencyMillis = (System.nanoTime() - started) / 1_000_000) }
                        }
                    }
                }
            } finally {
                if (!admitted) pending.decrementAndGet()
                _metrics.update { it.copy(queued = pending.get()) }
            }
        }
    }
}

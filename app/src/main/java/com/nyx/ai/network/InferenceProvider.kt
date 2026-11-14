package com.nyx.ai.network

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class ProviderCapabilities(
    /** Adapter API availability only, NOT demonstrated support by a selected model. */
    val toolCalling: Boolean,
    val streaming: Boolean,
    val contextLength: Int?,
    val maxConcurrentRequests: Int,
    val local: Boolean,
    val healthy: Boolean,
    val modelCount: Int = 0,
    val detail: String? = null,
    val modelToolCalling: CapabilitySupport = CapabilitySupport.UNKNOWN,
)

interface InferenceProvider {
    val id: String
    val displayName: String
    val kind: String
    val local: Boolean
    /** Non-secret revision. Rotate on runtime/model replacement; wrappers preserve it. */
    val capabilityRevision: String get() = ""
    suspend fun capabilities(): ProviderCapabilities
    suspend fun listModels(): List<LocalModel>
    suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions,
        onToken: (String) -> Unit = {}): ChatResult
    fun close() {}
}

class OllamaInferenceProvider(
    override val id: String,
    override val displayName: String,
    private val client: OllamaClient,
    override val local: Boolean,
    private val knownContextLength: Int? = null,
    runtimeRevision: String = "",
) : InferenceProvider {
    override val kind: String = "ollama"
    override val capabilityRevision: String = digest("${client.baseUrl}|$runtimeRevision")
    override suspend fun capabilities(): ProviderCapabilities = adapterCapabilities(local, knownContextLength) { listModels() }
    override suspend fun listModels(): List<LocalModel> = client.listModels()
    override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions,
        onToken: (String) -> Unit): ChatResult = try {
        client.chat(model, messages, tools, options, onToken)
    } catch (error: ToolCallingUnsupportedException) {
        recordExplicitToolRejection(this, model, options, tools)
        throw error
    }
}

class OpenAiCompatibleInferenceProvider(
    override val id: String,
    override val displayName: String,
    private val client: OllamaClient,
    override val local: Boolean,
    private val knownContextLength: Int? = null,
    runtimeRevision: String = "",
) : InferenceProvider {
    override val kind: String = "openai-compatible"
    override val capabilityRevision: String = digest("${client.baseUrl}|$runtimeRevision")
    override suspend fun capabilities(): ProviderCapabilities = adapterCapabilities(local, knownContextLength) { listModels() }
    override suspend fun listModels(): List<LocalModel> = client.listModels()
    override suspend fun chat(model: String, messages: List<ApiMessage>, tools: JSONArray?, options: InferenceOptions,
        onToken: (String) -> Unit): ChatResult = try {
        client.chat(model, messages, tools, options, onToken)
    } catch (error: ToolCallingUnsupportedException) {
        recordExplicitToolRejection(this, model, options, tools)
        throw error
    }
}

private suspend fun adapterCapabilities(local: Boolean, context: Int?, inventory: suspend () -> List<LocalModel>): ProviderCapabilities = try {
    val models = inventory()
    ProviderCapabilities(true, true, context, if (local) 1 else 2, local, true, models.size)
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Exception) {
    ProviderCapabilities(true, true, context, if (local) 1 else 2, local, false, detail = safeFailureReason(error))
}

private fun recordExplicitToolRejection(provider: InferenceProvider, model: String, options: InferenceOptions, tools: JSONArray?) {
    providerCapabilityCache.recordToolSupport(provider.id, model, false)
    if (tools != null) ModelCapabilities.store().recordExplicitRejection(
        ModelIdentity.fromRequest(provider, model, options, tools), ObservedCapability.NATIVE_TOOLS)
}

/** Legacy informational cache, not planning evidence. ModelCapabilityStore keys actual requests. */
class ProviderCapabilityCache(private val clock: () -> Long = System::currentTimeMillis) {
    private sealed interface CacheKey {
        data class ToolSupport(val providerId: String, val model: String) : CacheKey
        data class Failure(val providerId: String) : CacheKey
    }
    private sealed interface CacheValue {
        val atMs: Long
        data class ToolSupport(val supported: Boolean, override val atMs: Long) : CacheValue
        data class Failure(val message: String?, override val atMs: Long) : CacheValue
    }
    private val lock = Any()
    private val entries = LinkedHashMap<CacheKey, CacheValue>()
    fun recordToolSupport(providerId: String, model: String, supported: Boolean) = synchronized(lock) {
        val key = CacheKey.ToolSupport(providerId, model)
        entries.remove(key); entries[key] = CacheValue.ToolSupport(supported, clock()); evictOldestIfNeeded()
    }
    fun toolSupport(providerId: String, model: String): Boolean? = synchronized(lock) {
        (freshValue(CacheKey.ToolSupport(providerId, model)) as? CacheValue.ToolSupport)?.supported
    }
    fun recordFailure(providerId: String, message: String?) = synchronized(lock) {
        val key = CacheKey.Failure(providerId)
        entries.remove(key); entries[key] = CacheValue.Failure(message, clock()); evictOldestIfNeeded()
    }
    fun lastFailure(providerId: String): String? = synchronized(lock) {
        (freshValue(CacheKey.Failure(providerId)) as? CacheValue.Failure)?.message
    }
    private fun freshValue(key: CacheKey): CacheValue? {
        val value = entries[key] ?: return null
        if (clock() < value.atMs || clock() - value.atMs > ModelCapabilityPolicy.CACHE_TTL_MS) {
            entries.remove(key); return null
        }
        return value
    }
    fun clear() = synchronized(lock) { entries.clear() }
    private fun evictOldestIfNeeded() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) return
            oldest.next(); oldest.remove()
        }
    }
    private companion object { const val MAX_ENTRIES = 256 }
}
internal val providerCapabilityCache = ProviderCapabilityCache()
private fun safeFailureReason(error: Exception): String = when (error) {
    is SocketTimeoutException -> "Provider request timed out"
    is UnknownHostException -> "Provider host could not be resolved"
    is IOException -> "Provider request failed"
    else -> "Could not list provider models"
}

package com.nyx.ai.network

import com.nyx.ai.network.ApiMessage
import com.nyx.ai.network.ChatResult
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.LocalModel
import com.nyx.ai.network.OllamaClient
import com.nyx.ai.network.ToolCallingUnsupportedException
import org.json.JSONArray
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException


data class ProviderCapabilities(
    val toolCalling: Boolean,
    val streaming: Boolean,
    val contextLength: Int?,
    val maxConcurrentRequests: Int,
    val local: Boolean,
    val healthy: Boolean,
    val modelCount: Int = 0,
    val detail: String? = null,
)

interface InferenceProvider {
    val id: String
    val displayName: String
    val kind: String // "ollama" or "openai-compatible"
    val local: Boolean
    suspend fun capabilities(): ProviderCapabilities
    suspend fun listModels(): List<LocalModel>
    suspend fun chat(
        model: String,
        messages: List<ApiMessage>,
        tools: JSONArray?,
        options: InferenceOptions,
        onToken: (String) -> Unit = {},
    ): ChatResult
    fun close() {}
}

class OllamaInferenceProvider(
    override val id: String,
    override val displayName: String,
    private val client: OllamaClient,
    override val local: Boolean,
    private val knownContextLength: Int? = null,
) : InferenceProvider {
    override val kind: String = "ollama"

    override suspend fun capabilities(): ProviderCapabilities = try {
        val models = listModels()
        ProviderCapabilities(
            toolCalling = true,
            streaming = true,
            contextLength = knownContextLength,
            maxConcurrentRequests = if (local) 1 else 2,
            local = local,
            healthy = true,
            modelCount = models.size,
        )
    } catch (error: Exception) {
        ProviderCapabilities(
            toolCalling = true,
            streaming = true,
            contextLength = knownContextLength,
            maxConcurrentRequests = if (local) 1 else 2,
            local = local,
            healthy = false,
            detail = safeFailureReason(error),
        )
    }

    override suspend fun listModels(): List<LocalModel> = client.listModels()

    override suspend fun chat(
        model: String,
        messages: List<ApiMessage>,
        tools: JSONArray?,
        options: InferenceOptions,
        onToken: (String) -> Unit,
    ): ChatResult = try {
        client.chat(model, messages, tools, options, onToken)
    } catch (error: ToolCallingUnsupportedException) {
        providerCapabilityCache.recordToolSupport(id, model, false)
        throw error
    }
}

class OpenAiCompatibleInferenceProvider(
    override val id: String,
    override val displayName: String,
    private val client: OllamaClient,
    override val local: Boolean,
    private val knownContextLength: Int? = null,
) : InferenceProvider {
    override val kind: String = "openai-compatible"

    override suspend fun capabilities(): ProviderCapabilities = try {
        val models = listModels()
        ProviderCapabilities(
            toolCalling = true,
            streaming = true,
            contextLength = knownContextLength,
            maxConcurrentRequests = if (local) 1 else 2,
            local = local,
            healthy = true,
            modelCount = models.size,
        )
    } catch (error: Exception) {
        ProviderCapabilities(
            toolCalling = true,
            streaming = true,
            contextLength = knownContextLength,
            maxConcurrentRequests = if (local) 1 else 2,
            local = local,
            healthy = false,
            detail = safeFailureReason(error),
        )
    }

    override suspend fun listModels(): List<LocalModel> = client.listModels()

    override suspend fun chat(
        model: String,
        messages: List<ApiMessage>,
        tools: JSONArray?,
        options: InferenceOptions,
        onToken: (String) -> Unit,
    ): ChatResult = try {
        client.chat(model, messages, tools, options, onToken)
    } catch (error: ToolCallingUnsupportedException) {
        providerCapabilityCache.recordToolSupport(id, model, false)
        throw error
    }
}

/** A process-local, insertion-ordered cache shared by providers for learned model capabilities. */
class ProviderCapabilityCache {
    private sealed interface CacheKey {
        data class ToolSupport(val providerId: String, val model: String) : CacheKey
        data class Failure(val providerId: String) : CacheKey
    }

    private sealed interface CacheValue {
        data class ToolSupport(val supported: Boolean) : CacheValue
        data class Failure(val message: String?) : CacheValue
    }

    private val lock = Any()
    private val entries = LinkedHashMap<CacheKey, CacheValue>()

    fun recordToolSupport(providerId: String, model: String, supported: Boolean) {
        synchronized(lock) {
            val key = CacheKey.ToolSupport(providerId, model)
            entries.remove(key)
            entries[key] = CacheValue.ToolSupport(supported)
            evictOldestIfNeeded()
        }
    }

    fun toolSupport(providerId: String, model: String): Boolean? = synchronized(lock) {
        (entries[CacheKey.ToolSupport(providerId, model)] as? CacheValue.ToolSupport)?.supported
    }

    fun recordFailure(providerId: String, message: String?) {
        synchronized(lock) {
            val key = CacheKey.Failure(providerId)
            entries.remove(key)
            entries[key] = CacheValue.Failure(message)
            evictOldestIfNeeded()
        }
    }

    fun lastFailure(providerId: String): String? = synchronized(lock) {
        (entries[CacheKey.Failure(providerId)] as? CacheValue.Failure)?.message
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun evictOldestIfNeeded() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) return
            oldest.next()
            oldest.remove()
        }
    }

    private companion object {
        const val MAX_ENTRIES = 256
    }
}

internal val providerCapabilityCache = ProviderCapabilityCache()

private fun safeFailureReason(error: Exception): String = when (error) {
    is SocketTimeoutException -> "Provider request timed out"
    is UnknownHostException -> "Provider host could not be resolved"
    is IOException -> "Provider request failed"
    else -> "Could not list provider models"
}

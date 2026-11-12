package com.nyx.ai.settings

import com.nyx.ai.data.ModelEndpointEntity
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProviderHandle(
    val provider: InferenceProvider,
    val endpoint: ModelEndpointEntity,
    val options: InferenceOptions,
)

/**
 * Resolves an endpoint record into an authenticated provider. Credentials are read from the
 * Keystore-backed vault only when the transport policy allows it, and are never logged or persisted
 * outside the vault.
 */
class InferenceClientFactory(
    private val database: NyxDatabase,
    private val vault: CredentialVault,
) {
    suspend fun providerFor(endpointId: String?, fallbackBaseUrl: String? = null): ProviderHandle {
        val endpoint = resolveEndpoint(endpointId, fallbackBaseUrl)
            ?: throw IllegalStateException("No inference endpoint is configured. Add one in Settings.")
        val secret = withContext(Dispatchers.IO) {
            endpoint.credentialReference?.let { reference ->
                if (!CredentialTransportPolicy.allows(endpoint.baseUrl)) null else runCatching { vault.read(reference) }.getOrNull()
            }
        }
        val provider = ProviderFactory.from(
            id = endpoint.id,
            displayName = endpoint.displayName,
            baseUrl = endpoint.baseUrl,
            apiKey = secret,
            contextLength = parseOptions(endpoint).contextLength,
        )
        return ProviderHandle(provider, endpoint, parseOptions(endpoint))
    }

    private suspend fun resolveEndpoint(endpointId: String?, fallbackBaseUrl: String?): ModelEndpointEntity? {
        if (!endpointId.isNullOrBlank()) {
            database.modelEndpointDao().getById(endpointId)?.let { return it }
        }
        if (!fallbackBaseUrl.isNullOrBlank()) {
            database.modelEndpointDao().observeAll().first().firstOrNull { it.baseUrl == fallbackBaseUrl }?.let { return it }
        }
        return database.modelEndpointDao().observeAll().first()
            .filter { it.isEnabled }
            .maxByOrNull { it.updatedAt }
    }

    private fun parseOptions(endpoint: ModelEndpointEntity): InferenceOptions {
        val json = runCatching { JSONObject(endpoint.configurationJson ?: "{}") }.getOrDefault(JSONObject())
        return InferenceOptions(
            temperature = json.optDouble("temperature", 0.7).coerceIn(0.0, 2.0),
            contextLength = json.optInt("contextLength", 4096).coerceIn(512, 131_072),
            cpuThreads = json.optInt("cpuThreads", 4).coerceIn(1, 64),
        )
    }
}

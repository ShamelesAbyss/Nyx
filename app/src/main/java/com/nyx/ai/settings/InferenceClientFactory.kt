package com.nyx.ai.settings

import android.content.Context
import com.nyx.ai.data.ModelEndpointEntity
import com.nyx.ai.data.NyxDatabase
import com.nyx.ai.network.InferenceFabric
import com.nyx.ai.network.InferenceOptions
import com.nyx.ai.network.InferenceProvider
import com.nyx.ai.network.ProviderFactory
import com.nyx.ai.nativeinference.NativeInferenceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProviderHandle(val provider: InferenceProvider, val endpoint: ModelEndpointEntity, val options: InferenceOptions)

/** Resolution never silently substitutes an endpoint or drops a saved Bearer credential. */
class InferenceClientFactory(private val database: NyxDatabase, private val vault: CredentialVault, private val context: Context) {
    suspend fun providerFor(endpointId: String?, fallbackBaseUrl: String? = null): ProviderHandle {
        val endpoint = if (!endpointId.isNullOrBlank()) database.modelEndpointDao().getById(endpointId)
            else if (!fallbackBaseUrl.isNullOrBlank()) database.modelEndpointDao().observeAll().first().firstOrNull { it.baseUrl == fallbackBaseUrl && it.isEnabled }
            else null
        requireNotNull(endpoint) { "The selected inference endpoint is unavailable. Select it again in Models." }
        require(endpoint.isEnabled) { "Inference endpoint is disabled." }
        return providerForRecord(endpoint)
    }

    suspend fun providerForRecord(endpoint: ModelEndpointEntity): ProviderHandle {
        val options = parseOptions(endpoint)
        if (endpoint.provider == "native-gguf") {
            return ProviderHandle(InferenceFabric.wrap(NativeInferenceProvider(context.applicationContext)), endpoint, options)
        }
        require(endpoint.provider != "GGUF") { "This is a legacy GGUF registration. Import the file into native Models first." }
        require(CredentialTransportPolicy.validEndpoint(endpoint.baseUrl)) { "Invalid or credential-bearing endpoint URL." }
        val consent = context.getSharedPreferences("nyx_settings", Context.MODE_PRIVATE).getBoolean("external_consent", false)
        val host = java.net.URI(endpoint.baseUrl).host?.trim('[', ']')?.lowercase()
        val loopback = host in setOf("127.0.0.1", "localhost", "::1")
        require(loopback || consent) { "External endpoint consent was revoked or not granted." }
        val secret = endpoint.credentialReference?.let { reference ->
            require(CredentialTransportPolicy.allows(endpoint.baseUrl)) { "Saved credentials require HTTPS outside loopback." }
            withContext(Dispatchers.IO) { vault.read(reference) } ?: error("Saved API key is unavailable. Re-enter it in Settings.")
        }
        val raw = ProviderFactory.from(endpoint.id, endpoint.displayName, endpoint.baseUrl, secret, options.contextLength)
        return ProviderHandle(InferenceFabric.wrap(raw), endpoint, options)
    }
    private fun parseOptions(endpoint: ModelEndpointEntity): InferenceOptions {
        val json = runCatching { JSONObject(endpoint.configurationJson ?: "{}") }.getOrDefault(JSONObject())
        return InferenceOptions(json.optDouble("temperature", 0.7).coerceIn(0.0, 2.0), json.optInt("contextLength", 4096).coerceIn(512, 32768), json.optInt("cpuThreads", 4).coerceIn(1, 16))
    }
}

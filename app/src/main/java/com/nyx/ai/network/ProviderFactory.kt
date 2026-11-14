package com.nyx.ai.network

import java.net.URI
import java.util.Locale

object ProviderFactory {
    fun from(
        id: String,
        displayName: String,
        baseUrl: String,
        apiKey: String?,
        contextLength: Int? = null,
    ): InferenceProvider {
        val client = OllamaClient(baseUrl, apiKey)
        val local = isLocalUrl(baseUrl)
        return if (isOpenAiCompatible(baseUrl)) {
            OpenAiCompatibleInferenceProvider(id, displayName, client, local, contextLength)
        } else {
            OllamaInferenceProvider(id, displayName, client, local, contextLength)
        }
    }

    fun isLocalUrl(baseUrl: String): Boolean {
        val uri = parseHttpUri(baseUrl) ?: return false
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
            ?.lowercase(Locale.ROOT) ?: return false
        return host == "127.0.0.1" ||
            host == "localhost" ||
            host == "::1"
    }

    fun isOpenAiCompatible(baseUrl: String): Boolean {
        val uri = parseHttpUri(baseUrl) ?: return false
        return uri.path.orEmpty().trimEnd('/').endsWith("/v1", ignoreCase = true)
    }

    private fun parseHttpUri(baseUrl: String): URI? {
        return try {
            URI(InferenceEndpointPolicy.parse(baseUrl).toString())
        } catch (_: Exception) {
            null
        }
    }
}

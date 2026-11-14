package com.nyx.ai.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.URI

/** A configured endpoint is explicit authority for that origin, never for arbitrary fetch tools. */
object InferenceEndpointPolicy {
    fun parse(endpoint: String): HttpUrl {
        require(endpoint.length in 1..2048 && endpoint.none { it.isISOControl() || it == '\\' }) { "Invalid inference endpoint URL." }
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid inference endpoint URL.")
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.host != null && (uri.port == -1 || uri.port in 1..65535)) { "Credential-bearing or invalid inference endpoint URL." }
        val url = endpoint.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid inference endpoint URL.")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Credential-bearing inference endpoint URL." }
        val address = NetworkAddressPolicy.literal(url.host)
        if (url.scheme == "http" && address != null) {
            require(NetworkAddressPolicy.isLocalInferenceAddress(address)) { "HTTP inference requires an explicitly selected loopback or private LAN endpoint." }
        }
        return url
    }

    fun allowsCredential(endpoint: String): Boolean = runCatching {
        val url = parse(endpoint)
        url.scheme == "https" || NetworkAddressPolicy.isLoopbackHost(url.host)
    }.getOrDefault(false)

    fun requireCredentialTransport(url: HttpUrl, hasCredential: Boolean) {
        require(!hasCredential || url.scheme == "https" || NetworkAddressPolicy.isLoopbackHost(url.host)) {
            "Bearer credentials require HTTPS outside loopback."
        }
    }

    /** Applied to the actual lookup and the connected address, including pooled/literal routes. */
    internal fun addressAllowed(url: HttpUrl, hasCredential: Boolean, address: InetAddress): Boolean = when {
        // Names claiming localhost must not resolve to a different machine even over HTTPS.
        NetworkAddressPolicy.isLoopbackHost(url.host) -> address.isLoopbackAddress
        url.scheme == "https" -> !address.isAnyLocalAddress && !address.isMulticastAddress
        hasCredential -> false
        else -> NetworkAddressPolicy.isLocalInferenceAddress(address)
    }
}

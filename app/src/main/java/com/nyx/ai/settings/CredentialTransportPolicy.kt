package com.nyx.ai.settings

import java.net.URI
import java.util.Locale

/** Prevents bearer credentials from leaving the device over an unencrypted remote connection. */
object CredentialTransportPolicy {
    fun validEndpoint(endpoint: String): Boolean = try {
        val uri = URI(endpoint)
        endpoint.length <= 2048 && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.port in -1..65535 && uri.userInfo == null && uri.query == null && uri.fragment == null
    } catch (_: Exception) { false }

    fun allows(endpoint: String): Boolean {
        return try {
            val uri = URI(endpoint)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)?.trim('[', ']') ?: return false
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return false
            if (scheme == "https") true
            else scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")
        } catch (_: Exception) {
            false
        }
    }
}

package com.nyx.ai.settings

import java.net.URI
import java.util.Locale

/** Prevents bearer credentials from leaving the device over an unencrypted remote connection. */
object CredentialTransportPolicy {
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

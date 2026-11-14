package com.nyx.ai.settings

import com.nyx.ai.network.InferenceEndpointPolicy

/** Configuration-time guard; actual DNS/connected-address checks also live in the transport. */
object CredentialTransportPolicy {
    fun validEndpoint(endpoint: String): Boolean = runCatching { InferenceEndpointPolicy.parse(endpoint); true }.getOrDefault(false)
    fun allows(endpoint: String): Boolean = InferenceEndpointPolicy.allowsCredential(endpoint)
}

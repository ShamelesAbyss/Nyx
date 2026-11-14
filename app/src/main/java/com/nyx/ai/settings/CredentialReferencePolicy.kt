package com.nyx.ai.settings

import java.security.MessageDigest

/** Existing simple references keep their on-disk identity; supported gateway references are opaque. */
object CredentialReferencePolicy {
    private val simple = Regex("[A-Za-z0-9_-]{1,80}")
    private val gateway = Regex("gateway:[a-z0-9][a-z0-9._-]{0,63}")

    fun fileStem(reference: String): String {
        if (simple.matches(reference)) return reference
        require(gateway.matches(reference)) { "Invalid credential reference." }
        // The dot makes this filename namespace disjoint from every legacy simple reference.
        // Logical reference (not this filename) remains the AES-GCM associated data.
        val digest = MessageDigest.getInstance("SHA-256").digest(reference.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "gateway.$digest"
    }
}

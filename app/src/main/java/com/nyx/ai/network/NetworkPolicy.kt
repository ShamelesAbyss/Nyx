package com.nyx.ai.network

import com.nyx.ai.autonomy.policy.AutonomyMode
import java.net.URI

enum class NetworkDecision { ALLOW, ASK, DENY }

data class NetworkAssessment(val decision: NetworkDecision, val reason: String, val host: String)

/**
 * Structural URL policy for model-initiated network access. It is intentionally independent of the
 * autonomy engine: even Full autonomy cannot fetch a private address, embed credentials in a URL, or
 * downgrade a public HTTPS request to HTTP.
 */
object NetworkPolicy {
    private val LOOPBACK_NAMES = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    fun assess(url: String, upload: Boolean, mode: AutonomyMode): NetworkAssessment {
        val parsed = runCatching { URI(url.trim()) }.getOrNull()
            ?: return NetworkAssessment(NetworkDecision.DENY, "Malformed URL.", "")
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return NetworkAssessment(NetworkDecision.DENY, "Only HTTP(S) URLs are supported.", "")
        }
        if (parsed.userInfo != null) {
            return NetworkAssessment(NetworkDecision.DENY, "URLs with embedded credentials are refused.", "")
        }
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) {
            return NetworkAssessment(NetworkDecision.DENY, "URL has no valid host.", "")
        }
        val loopback = isLoopbackHost(host)
        if (!loopback && scheme != "https") {
            return NetworkAssessment(NetworkDecision.DENY, "Public network access requires HTTPS.", host)
        }
        if (!loopback && isPrivateHost(host)) {
            return NetworkAssessment(NetworkDecision.DENY, "Private and link-local addresses are not reachable by network tools.", host)
        }
        val port = parsed.port
        if (port != -1 && port != 80 && port != 443 && !loopback) {
            return NetworkAssessment(NetworkDecision.DENY, "Only ports 80 and 443 are allowed for public hosts.", host)
        }
        if (upload && isPrivateHost(host)) {
            return NetworkAssessment(NetworkDecision.DENY, "Uploads to private addresses are refused.", host)
        }
        return when (mode) {
            AutonomyMode.MANUAL -> NetworkAssessment(NetworkDecision.ASK, "Manual approval is required for network access.", host)
            AutonomyMode.SMART -> if (upload) {
                NetworkAssessment(NetworkDecision.ASK, "Uploading data requires approval.", host)
            } else {
                NetworkAssessment(NetworkDecision.ALLOW, "Ordinary HTTPS read allowed in Smart mode.", host)
            }

            AutonomyMode.FULL -> if (upload) {
                NetworkAssessment(NetworkDecision.ASK, "Uploading data still requires explicit approval.", host)
            } else {
                NetworkAssessment(NetworkDecision.ALLOW, "Network read allowed inside the granted scope.", host)
            }
        }
    }

    fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase().trim('[', ']')
        return normalized in LOOPBACK_NAMES || normalized.endsWith(".localhost")
    }

    /** Literal private, link-local, CGNAT, and unspecified addresses. DNS results are checked separately. */
    fun isPrivateHost(host: String): Boolean {
        val value = host.lowercase().trim('[', ']')
        if (value.startsWith("fc") || value.startsWith("fd") || value.startsWith("fe80") || value == "::") return true
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return false }
        val (a, b) = octets
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    /** Validates that a redirect target is no less safe than the original request. */
    fun redirectAllowed(original: String, target: String): Boolean {
        val first = runCatching { URI(original) }.getOrNull() ?: return false
        val second = runCatching { URI(target) }.getOrNull() ?: return false
        if (second.scheme?.lowercase() != "https" && second.scheme?.lowercase() != "http") return false
        if (second.userInfo != null || second.host.isNullOrBlank()) return false
        if (second.host.let(::isPrivateHost) && !second.host.let(::isLoopbackHost)) return false
        // A public origin must not be able to pivot a redirect into a loopback service.
        if (second.host.let(::isLoopbackHost) && !first.host.let(::isLoopbackHost)) return false
        if (first.scheme.equals("https", true) && second.scheme.equals("http", true) && !second.host.let(::isLoopbackHost)) {
            return false
        }
        return true
    }
}

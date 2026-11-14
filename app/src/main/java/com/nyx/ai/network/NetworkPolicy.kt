package com.nyx.ai.network

import com.nyx.ai.autonomy.policy.AutonomyMode
import java.net.URI
import java.util.Locale

enum class NetworkDecision { ALLOW, ASK, DENY }
data class NetworkAssessment(val decision: NetworkDecision, val reason: String, val host: String)

/** Public model fetches never inherit the user's separately selected local inference authority. */
object NetworkPolicy {
    fun assess(url: String, upload: Boolean, mode: AutonomyMode): NetworkAssessment {
        val problem = publicUrlProblem(url)
        val host = runCatching { URI(url.trim()).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (problem != null) return NetworkAssessment(NetworkDecision.DENY, problem, host)
        return when {
            mode == AutonomyMode.MANUAL -> NetworkAssessment(NetworkDecision.ASK, "Manual approval is required for network access.", host)
            upload -> NetworkAssessment(NetworkDecision.ASK, "Uploading data requires explicit approval.", host)
            else -> NetworkAssessment(NetworkDecision.ALLOW, "Public HTTPS read allowed inside the granted scope.", host)
        }
    }

    internal fun publicUrlProblem(url: String): String? {
        if (url.length > 4096 || url.any { it.isISOControl() || it == '\\' }) return "Malformed URL."
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return "Malformed URL."
        if (!parsed.scheme.equals("https", true)) return "Public network access requires HTTPS."
        if (parsed.rawUserInfo != null) return "URLs with embedded credentials are refused."
        val host = parsed.host ?: return "URL has no valid host."
        if (isPrivateHost(host)) return "Private and non-public addresses are refused by network tools."
        if (parsed.port != -1 && parsed.port !in setOf(80, 443)) return "Only ports 80 and 443 are allowed for public hosts."
        return null
    }

    fun isLoopbackHost(host: String): Boolean = NetworkAddressPolicy.isLoopbackHost(host)
    /** Kept for callers; this means any non-public literal/local hostname, not fc/fd text prefixes. */
    fun isPrivateHost(host: String): Boolean = NetworkAddressPolicy.isNonPublicHost(host)

    fun redirectAllowed(original: String, target: String): Boolean =
        publicUrlProblem(original) == null && publicUrlProblem(target) == null
}

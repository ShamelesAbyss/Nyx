package com.nyx.ai.network

import java.net.InetAddress
import java.util.Locale

/** Application transport policy, not an OS firewall. Hostname checks never resolve DNS. */
object NetworkAddressPolicy {
    fun literal(host: String): InetAddress? {
        val value = host.lowercase(Locale.ROOT).trim('[', ']')
        // Only numeric IPv6 or exactly four decimal IPv4 octets may reach getByName.
        // Reject scopes and legacy shorthand/octal forms; OkHttp-normalized literals are checked again.
        if (value.contains(':')) {
            if (!value.matches(Regex("[0-9a-f:.]+"))) return null
            return runCatching { InetAddress.getByName(value) }.getOrNull()
        }
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || (it.toIntOrNull() ?: 256) > 255 }) return null
        return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
    }

    fun isLoopbackHost(host: String): Boolean {
        val value = host.lowercase(Locale.ROOT).trim('[', ']')
        return value == "localhost" || value.endsWith(".localhost") || literal(value)?.isLoopbackAddress == true
    }

    fun isLocalInferenceAddress(address: InetAddress): Boolean = address.isLoopbackAddress || isLanAddress(address)

    /** Private LAN inference is deliberately limited to RFC1918 IPv4 and IPv6 ULA. */
    fun isLanAddress(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        return if (bytes.size == 4) {
            bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168)
        } else bytes.size == 16 && (bytes[0] and 0xfe) == 0xfc
    }

    /** All answers must be public; mixed DNS results fail closed rather than retrying private routes. */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress || isLanAddress(address)) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            return !(b[0] == 0 || b[0] >= 224 || (b[0] == 100 && b[1] in 64..127) ||
                (b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2)) ||
                (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
        }
        // Public IPv6 unicast only (2000::/3); also exclude documentation and transition ranges
        // whose embedded destination is not represented by the outer address.
        return b.size == 16 && (b[0] and 0xe0) == 0x20 &&
            !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) &&
            !(b[0] == 0x20 && b[1] == 0x02) &&
            !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0)
    }

    fun isNonPublicHost(host: String): Boolean {
        val value = host.lowercase(Locale.ROOT).trim('[', ']')
        return isLoopbackHost(value) || value.endsWith(".local") || literal(value)?.let { !isPublic(it) } == true
    }
}

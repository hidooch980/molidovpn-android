package com.molido.vpn

import java.net.URI

/** Hysteria2 authority, without auth/query. No DNS lookup or port-range expansion. */
internal data class Hysteria2Endpoint(
    val address: String,
    val port: Int,
    val serverPorts: List<String>,
) {
    companion object {
        fun parse(authority: String): Hysteria2Endpoint? {
            // Bound untrusted subscription input before splitting; large ranges stay compact.
            if (authority.isEmpty() || authority.length > 2048 ||
                authority.any { it.isWhitespace() || it in "@/?#" }) return null
            val address: String
            val portText: String
            if (authority.startsWith('[')) {
                val end = authority.indexOf(']')
                if (end < 2) return null
                address = authority.substring(1, end)
                if (!address.contains(':') || address.any { it !in "0123456789abcdefABCDEF:." }) return null
                // URI validates IPv6 syntax without resolving the host.
                if (runCatching { URI("http://[$address]").host }.getOrNull() == null) return null
                val suffix = authority.substring(end + 1)
                if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
                portText = if (suffix.isEmpty()) "443" else suffix.drop(1)
            } else {
                // IPv6 literals must be bracketed, including when no port is supplied.
                if (authority.count { it == ':' } > 1) return null
                address = authority.substringBefore(':')
                portText = if (':' in authority) authority.substringAfter(':') else "443"
                if (address.any { it in "[]," }) return null
            }
            if (address.isBlank() || portText.isEmpty()) return null
            val parts = portText.split(',')
            if (parts.size > 64) return null
            val normalized = ArrayList<String>()
            var firstPort = 0
            for (part in parts) {
                val bounds = part.split('-')
                if (bounds.size !in 1..2 || bounds.any { b ->
                        b.isEmpty() || b.length > 5 || b.any { it !in '0'..'9' }
                    }) return null
                val low = bounds[0].toIntOrNull() ?: return null
                val high = bounds.last().toIntOrNull() ?: return null
                if (low !in 1..65535 || high !in low..65535) return null
                if (firstPort == 0) firstPort = low
                // The pinned engine requires start:end even for a singleton.
                normalized.add("$low:$high")
            }
            val hopping = ',' in portText || '-' in portText
            return Hysteria2Endpoint(address, firstPort, if (hopping) normalized.distinct() else emptyList())
        }
    }
}

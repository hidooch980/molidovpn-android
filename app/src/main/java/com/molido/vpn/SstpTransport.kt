package com.molido.vpn

import java.net.IDN
import java.util.Locale

/**
 * Strict SSTP gateway metadata parser.
 *
 * This is deliberately NOT a tunnel engine. SSTP requires TLS + SSTP control +
 * PPP negotiation/authentication and a protected socket integrated with VpnService.
 * Until that native data plane is packaged and tested, the transport must not be
 * offered as a connectable Smart Connect candidate.
 */
data class SstpEndpoint private constructor(
    val hostname: String,
    val port: Int,
) {
    val tlsServerName: String get() = hostname
    val tlsVerificationRequired: Boolean get() = true

    fun authority(): String = "$hostname:$port"

    companion object {
        private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

        /** Accepts hostname or hostname:port only; credentials, URLs and IP literals are rejected. */
        fun parse(input: String): SstpEndpoint? {
            val value = input.trim()
            if (value.isEmpty() || value.length > 300) return null
            if (value.contains("://") || value.any { it.isWhitespace() } ||
                value.any { it in "/\\\\?#@" }) return null

            val colon = value.lastIndexOf(':')
            val hostPart: String
            val port: Int
            if (colon >= 0) {
                if (value.indexOf(':') != colon) return null // IPv6 literals are not accepted.
                hostPart = value.substring(0, colon)
                val portText = value.substring(colon + 1)
                if (portText.isEmpty() || portText.any { !it.isDigit() }) return null
                port = portText.toIntOrNull() ?: return null
            } else {
                hostPart = value
                port = 443
            }
            if (port !in 1..65535 || hostPart.isBlank()) return null

            val ascii = runCatching {
                IDN.toASCII(hostPart.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
                    .lowercase(Locale.ROOT)
            }.getOrNull() ?: return null
            if (ascii.length !in 1..253 || !ascii.contains('.')) return null
            val labels = ascii.split('.')
            if (labels.any { it.length !in 1..63 || !label.matches(it) }) return null
            // Avoid IP-address endpoints: certificate hostname validation needs a DNS identity.
            if (labels.size == 4 && labels.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) return null
            return SstpEndpoint(ascii, port)
        }
    }
}

/** A capability gate, not a claim that SSTP can already establish a VPN session. */
object SstpTransport {
    const val ID = "sstp"
    const val DEFAULT_PORT = 443
    const val STATUS = "profile_validation_only"

    /** False until a pinned SSTP/PPP engine and VpnService data-plane adapter ship together. */
    const val ENGINE_PACKAGED = false
    const val PPP_BACKEND_PACKAGED = false
    const val CONNECTABLE = ENGINE_PACKAGED && PPP_BACKEND_PACKAGED

    fun canBeUsedAsGamingFallback(udpBlocked: Boolean, endpoint: SstpEndpoint?): Boolean =
        CONNECTABLE && udpBlocked && endpoint != null
}

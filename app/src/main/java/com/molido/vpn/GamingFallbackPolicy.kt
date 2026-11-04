package com.molido.vpn

/**
 * Conservative route selector for gaming. Measurements are supplied by the
 * network probe layer; this class does not pretend an HTTP ping is in-game RTT.
 */
enum class GamingTransport { WIREGUARD_UDP, IKEV2, OPENVPN_UDP, SSTP_TCP }

data class GamingPathMeasurement(
    val transport: GamingTransport,
    val medianRttMs: Long?,
    val jitterMs: Long?,
    val packetLossPercent: Double?,
    val udpBlocked: Boolean = false,
    val tlsVerified: Boolean = false,
    val authenticatedTunnel: Boolean = false,
    val payloadVerified: Boolean = false,
)

object GamingFallbackPolicy {
    /**
     * A route is eligible only with complete, finite measurements and verified
     * payload. SSTP is considered only after UDP is known to be blocked and its
     * TLS + authentication + payload checks all passed.
     */
    fun choose(paths: List<GamingPathMeasurement>): GamingPathMeasurement? {
        val eligible = paths.filter { path ->
            val rtt = path.medianRttMs
            val jitter = path.jitterMs
            val loss = path.packetLossPercent
            rtt != null && rtt in 1..60_000 &&
                jitter != null && jitter in 0..60_000 &&
                loss != null && loss.isFinite() && loss in 0.0..100.0 &&
                path.payloadVerified &&
                (path.transport != GamingTransport.SSTP_TCP ||
                    (path.udpBlocked && path.tlsVerified && path.authenticatedTunnel &&
                        SstpTransport.CONNECTABLE))
        }
        return eligible.minWithOrNull(
            compareBy<GamingPathMeasurement> { it.medianRttMs!! + it.jitterMs!! * 2L +
                (it.packetLossPercent!! * 20.0).toLong() }
                .thenBy { when (it.transport) {
                    GamingTransport.WIREGUARD_UDP -> 0
                    GamingTransport.IKEV2 -> 1
                    GamingTransport.OPENVPN_UDP -> 2
                    GamingTransport.SSTP_TCP -> 3
                } }
        )
    }
}

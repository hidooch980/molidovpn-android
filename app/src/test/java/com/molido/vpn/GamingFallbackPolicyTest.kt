package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test

class GamingFallbackPolicyTest {
    private fun path(
        transport: GamingTransport,
        rtt: Long = 30,
        jitter: Long = 3,
        loss: Double = 0.0,
        udpBlocked: Boolean = false,
        tls: Boolean = false,
        auth: Boolean = false,
        payload: Boolean = true,
    ) = GamingPathMeasurement(transport, rtt, jitter, loss, udpBlocked, tls, auth, payload)

    @Test fun prefersMeasuredWireGuardWhenHealthy() {
        val selected = GamingFallbackPolicy.choose(listOf(
            path(GamingTransport.SSTP_TCP, rtt = 20, udpBlocked = true, tls = true, auth = true),
            path(GamingTransport.WIREGUARD_UDP, rtt = 35),
        ))
        assertEquals(GamingTransport.WIREGUARD_UDP, selected?.transport)
    }

    @Test fun neverSelectsSstpWithoutVerifiedTunnelAndUdpBlock() {
        val candidates = listOf(
            path(GamingTransport.SSTP_TCP, udpBlocked = false, tls = true, auth = true),
            path(GamingTransport.SSTP_TCP, udpBlocked = true, tls = false, auth = true),
            path(GamingTransport.SSTP_TCP, udpBlocked = true, tls = true, auth = false),
            path(GamingTransport.SSTP_TCP, udpBlocked = true, tls = true, auth = true, payload = false),
        )
        assertNull(GamingFallbackPolicy.choose(candidates))
    }

    @Test fun selectsLowestMeasuredQualityScoreAmongEligibleUdpPaths() {
        val selected = GamingFallbackPolicy.choose(listOf(
            path(GamingTransport.IKEV2, rtt = 50, jitter = 8, loss = 1.0),
            path(GamingTransport.WIREGUARD_UDP, rtt = 32, jitter = 2, loss = 0.0),
        ))
        assertEquals(GamingTransport.WIREGUARD_UDP, selected?.transport)
    }

    @Test fun rejectsMissingOrInvalidMeasurementsAndUnverifiedPayload() {
        assertNull(GamingFallbackPolicy.choose(listOf(
            GamingPathMeasurement(GamingTransport.WIREGUARD_UDP, null, 1, 0.0, payloadVerified = true),
            path(GamingTransport.IKEV2, rtt = 0),
            path(GamingTransport.OPENVPN_UDP, loss = Double.NaN),
            path(GamingTransport.WIREGUARD_UDP, payload = false),
        )))
    }
}

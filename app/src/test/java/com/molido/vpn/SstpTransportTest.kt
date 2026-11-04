package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test

class SstpTransportTest {
    @Test fun acceptsDnsGatewayAndDefaultPort() {
        val endpoint = SstpEndpoint.parse("vpn.example.com")!!
        assertEquals("vpn.example.com", endpoint.hostname)
        assertEquals(443, endpoint.port)
        assertTrue(endpoint.tlsVerificationRequired)
    }

    @Test fun acceptsExplicitPortAndNormalizesIdn() {
        assertEquals("vpn.example.com:8443", SstpEndpoint.parse("VPN.EXAMPLE.COM:8443")!!.authority())
        assertEquals("xn--bcher-kva.example:443", SstpEndpoint.parse("bücher.example")!!.authority())
    }

    @Test fun rejectsCredentialsUrlsPathsAndQueryStrings() {
        listOf(
            "https://vpn.example.com", "user@vpn.example.com", "vpn.example.com/path",
            "vpn.example.com?token=secret", "vpn.example.com#fragment",
        ).forEach { assertNull(it, SstpEndpoint.parse(it)) }
    }

    @Test fun rejectsMalformedPortsAndIpLiterals() {
        listOf("vpn.example.com:0", "vpn.example.com:65536", "vpn.example.com:abc",
            "192.0.2.10", "192.168.1.2:443", "[2001:db8::1]:443", "localhost")
            .forEach { assertNull(it, SstpEndpoint.parse(it)) }
    }

    @Test fun engineGatePreventsFalseConnectabilityAndGamingFallback() {
        val endpoint = SstpEndpoint.parse("vpn.example.com")
        assertFalse(SstpTransport.CONNECTABLE)
        assertFalse(SstpTransport.canBeUsedAsGamingFallback(udpBlocked = true, endpoint = endpoint))
        assertFalse(SstpTransport.canBeUsedAsGamingFallback(udpBlocked = false, endpoint = endpoint))
    }
}

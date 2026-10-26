package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test

class Hysteria2EndpointTest {
    @Test fun defaultAndSinglePorts() {
        assertEquals(Hysteria2Endpoint("vpn.example", 443, emptyList()), Hysteria2Endpoint.parse("vpn.example"))
        assertEquals(Hysteria2Endpoint("192.0.2.1", 8443, emptyList()), Hysteria2Endpoint.parse("192.0.2.1:8443"))
        assertEquals(Hysteria2Endpoint("2001:db8::1", 443, emptyList()), Hysteria2Endpoint.parse("[2001:db8::1]"))
    }

    @Test fun preservesAllPortsAndRangesWithoutExpandingThem() {
        assertEquals(Hysteria2Endpoint("vpn.example", 443, listOf("443", "20000:30000", "8443")),
            Hysteria2Endpoint.parse("vpn.example:443,20000-30000,8443"))
        assertEquals(Hysteria2Endpoint("2001:db8::1", 20000, listOf("20000:30000", "443")),
            Hysteria2Endpoint.parse("[2001:db8::1]:20000-30000,443"))
        assertEquals(listOf("1:65535"), Hysteria2Endpoint.parse("vpn.example:1-65535")!!.serverPorts)
    }

    @Test fun normalizesAndDeduplicates() {
        assertEquals(listOf("443", "5000:6000"),
            Hysteria2Endpoint.parse("vpn.example:00443,443-443,5000-6000,443")!!.serverPorts)
    }

    @Test fun rejectsInvalidPortInsteadOfSilentlyUsingFirst() {
        for (ports in listOf("", "0", "65536", "-1", "+443", "443,", ",443", "443,,8443",
            "443,0", "443,65536", "5000-4000", "443-", "-443", "1-2-3", "443,garbage",
            "443, 8443", "1:2", "999999999999999999999", "443%2C8443")) {
            assertNull(ports, Hysteria2Endpoint.parse("vpn.example:$ports"))
        }
    }

    @Test fun rejectsMalformedAndOversizedAuthorities() {
        for (authority in listOf("", ":443", "[2001:db8::1", "2001:db8::1", "[2001:db8::1]extra:443",
            "[vpn.example]:443", "[2001:db8::1]:", "vpn.example/a", "x@y:443", "vpn.example:443#x",
            "vpn.example:" + List(65) { "443" }.joinToString(","), "x".repeat(2049))) {
            assertNull(authority, Hysteria2Endpoint.parse(authority))
        }
    }
}

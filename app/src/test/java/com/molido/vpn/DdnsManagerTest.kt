package com.molido.vpn

import org.junit.Assert.assertThrows
import org.junit.Test

class DdnsManagerTest {
    @Test fun acceptsDuckDnsAndNoIpHostnames() {
        DdnsManager.validate(DdnsManager.Config(DdnsManager.Provider.DUCKDNS, "molido.duckdns.org", "12345678"))
        DdnsManager.validate(DdnsManager.Config(DdnsManager.Provider.NOIP, "home.ddns.net", "user:password"))
    }

    @Test fun rejectsMalformedHostnameAndShortCredential() {
        listOf(
            "bad..domain", "-bad.example.com", "bad-.example.com",
            "bad_label.example.com", "bad label.example.com", "example.com.",
            "exämple.com", "a".repeat(64) + ".example.com"
        ).forEach { hostname ->
            assertThrows(IllegalArgumentException::class.java) {
                DdnsManager.validate(DdnsManager.Config(DdnsManager.Provider.DUCKDNS, hostname, "12345678"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DdnsManager.validate(DdnsManager.Config(DdnsManager.Provider.DUCKDNS, "home.duckdns.org", "short"))
        }
    }

    @Test fun validatesPublicIpLiteralsWithoutAcceptingHostnamesOrMalformedOctets() {
        listOf("1.1.1.1", "203.0.113.10", "2001:4860:4860::8888").forEach {
            org.junit.Assert.assertTrue(it, DdnsManager.isIpLiteral(it))
        }
        listOf("example.com", "999.1.1.1", "1.2.3", "1.2.3.4.5", "2001:db8::gg").forEach {
            org.junit.Assert.assertFalse(it, DdnsManager.isIpLiteral(it))
        }
    }

    @Test fun cloudflareRequiresZoneAndRecordIds() {
        assertThrows(IllegalArgumentException::class.java) {
            DdnsManager.validate(DdnsManager.Config(DdnsManager.Provider.CLOUDFLARE, "home.example.com", "12345678"))
        }
        DdnsManager.validate(DdnsManager.Config(
            DdnsManager.Provider.CLOUDFLARE, "home.example.com", "12345678",
            "0123456789abcdef0123456789abcdef", "abcdef0123456789abcdef0123456789"
        ))
    }
}

package com.molido.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeIkev2HostValidationTest {
    @Test fun acceptsValidDnsAndIpAddresses() {
        assertTrue(isValidIkev2Host("vpn.example.com"))
        assertTrue(isValidIkev2Host("vpn-01.example.net"))
        assertTrue(isValidIkev2Host("192.0.2.10"))
        assertTrue(isValidIkev2Host("2001:db8::1"))
    }

    @Test fun rejectsMalformedHostsAndInjectionStrings() {
        listOf(
            "", ".example.com", "example.com.", "vpn..example.com",
            "-bad.example.com", "bad-.example.com", "vpn.example.com;whoami",
            "https://vpn.example.com", "256.1.1.1", "1.2.3", "::::"
        ).forEach { assertFalse("should reject $it", isValidIkev2Host(it)) }
    }
}

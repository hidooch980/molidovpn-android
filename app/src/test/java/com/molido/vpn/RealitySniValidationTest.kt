package com.molido.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealitySniValidationTest {
    @Test fun acceptsDnsNamesAndSubdomains() {
        listOf("example.com", "cdn.example.net", "a-b.example.co.uk", "NODE-1.example.org")
            .forEach { assertTrue(it, RealitySni.isValidHostname(it)) }
    }

    @Test fun rejectsUrlsIpLiteralsAndMalformedLabels() {
        listOf(
            "", "localhost", ".example.com", "example.com.", "https://example.com",
            "example.com:443", "*.example.com", "example..com", "-bad.example",
            "bad-.example", "bad_name.example", "bad name.example", "127.0.0.1",
            "999.999.999.999", "2001:db8::1", "exämple.com"
        ).forEach { assertFalse(it, RealitySni.isValidHostname(it)) }
    }
}

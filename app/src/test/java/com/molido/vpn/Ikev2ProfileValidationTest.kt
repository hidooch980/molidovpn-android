package com.molido.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ikev2ProfileValidationTest {
    @Test fun acceptsDnsNamesAndIpLiterals() {
        assertTrue(Ikev2ProfileValidation.isValidServer("vpn.example.org"))
        assertTrue(Ikev2ProfileValidation.isValidServer("192.0.2.10"))
        assertTrue(Ikev2ProfileValidation.isValidServer("2001:db8::10"))
    }

    @Test fun rejectsMalformedOrUnsafeServerInputs() {
        listOf("", " https://vpn.example.org", "vpn.example.org/path", "bad host", "-bad.example", "vpn..example", "256.1.1.1", "vpn").forEach {
            assertFalse("Expected rejection: $it", Ikev2ProfileValidation.isValidServer(it))
        }
    }

    @Test fun requiresCompleteEapCredentials() {
        assertTrue(Ikev2ProfileValidation.hasEapCredentials("id", "user", "pass"))
        assertFalse(Ikev2ProfileValidation.hasEapCredentials("", "user", "pass"))
        assertFalse(Ikev2ProfileValidation.hasEapCredentials("id", " ", "pass"))
        assertFalse(Ikev2ProfileValidation.hasEapCredentials("id", "user", ""))
    }
}

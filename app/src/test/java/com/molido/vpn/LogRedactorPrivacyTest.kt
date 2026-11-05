package com.molido.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorPrivacyTest {
    @Test
    fun redactsCredentialsEmailsAndShareableVpnProfiles() {
        val privateConfig = "vless://user-secret@edge.example:443?token=abc123#home"
        val input = """
            Authorization: Bearer bearer-secret
            password='two words secret'
            {"access_token":"access-secret","refresh_token":"refresh-secret"}
            support.person@example.com
            $privateConfig
        """.trimIndent()

        val output = LogRedactor.redact(input)

        listOf(
            "bearer-secret",
            "two words secret",
            "access-secret",
            "refresh-secret",
            "support.person@example.com",
            "user-secret",
            "abc123",
            privateConfig,
        ).forEach { assertFalse("secret leaked: $it", output.contains(it)) }

        assertTrue(output.contains("[redacted]"))
        assertTrue(output.contains("mail"))
        assertTrue(output.contains("cfg"))
    }

    @Test
    fun keepsUsefulFailureContextReadable() {
        val output = LogRedactor.redact("TLS handshake failed after 2500ms with HTTP 503")
        assertTrue(output.contains("TLS handshake failed"))
        assertTrue(output.contains("2500ms"))
        assertTrue(output.contains("HTTP 503"))
    }
}

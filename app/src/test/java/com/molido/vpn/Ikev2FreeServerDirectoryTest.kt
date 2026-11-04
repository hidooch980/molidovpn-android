package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ikev2FreeServerDirectoryTest {
    @Test fun extractsOnlyProviderOwnedHostsWithMatchingAccountLinks() {
        val html = """
            <html><body>
              <h2>Free IKEV2 MSCHPv2 Turkey</h2>
              <p>Available Location Istanbul, Turkey tr5.vpnjantit.com EAP Auth</p>
              <a href="/create-free-account?server=tr5&amp;type=IKEV2">Create free account</a>
              <p>tr6.vpnjantit.com</p>
              <a href="/create-free-account?server=tr6&amp;type=IKEV2">Create free account</a>
              <p>malicious.example.net</p>
              <a href="https://evil.example/create-free-account?server=evil">External</a>
            </body></html>
        """.trimIndent()

        val servers = Ikev2FreeServerDirectory.parseCountryHtml(
            "Turkey",
            "https://www.vpnjantit.com/free-ikev2-turkey",
            html,
        )

        assertEquals(listOf("tr5.vpnjantit.com", "tr6.vpnjantit.com"), servers.map { it.host }.sorted())
        assertTrue(servers.all { it.accountUrl.startsWith("https://www.vpnjantit.com/") })
        assertTrue(servers.all { it.listedAvailable })
        assertEquals("vpnjantit", servers.first().identity)
    }

    @Test fun marksProviderUnavailablePageWithoutInventingAvailability() {
        val html = """
            <html><body>
              <p>Unavailable tr5.vpnjantit.com</p>
              <a href="/create-free-account?server=tr5&amp;type=IKEV2">Create account</a>
            </body></html>
        """.trimIndent()
        val servers = Ikev2FreeServerDirectory.parseCountryHtml(
            "Turkey",
            "https://www.vpnjantit.com/free-ikev2-turkey",
            html,
        )
        assertEquals(1, servers.size)
        assertFalse(servers.single().listedAvailable)
    }

    @Test fun rejectsAccountLinksOutsideOfficialProviderHost() {
        val html = """
            <p>Available tr5.vpnjantit.com</p>
            <a href="https://evil.example/create-free-account?server=tr5">Create account</a>
        """.trimIndent()
        assertTrue(
            Ikev2FreeServerDirectory.parseCountryHtml(
                "Turkey",
                "https://www.vpnjantit.com/free-ikev2-turkey",
                html,
            ).isEmpty(),
        )
    }
}

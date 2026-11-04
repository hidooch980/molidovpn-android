package com.molido.vpn

import org.jsoup.Jsoup
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class Ikev2FreeServer(
    val country: String,
    val host: String,
    val identity: String,
    val accountUrl: String,
    val listedAvailable: Boolean,
)

object Ikev2FreeServerDirectory {
    const val DIRECTORY_URL = "https://www.vpnjantit.com/free-ikev2"
    private const val PROVIDER_HOST = "www.vpnjantit.com"
    private val serverPattern = Regex("(?i)\\b([a-z0-9-]+\\.vpnjantit\\.com)\\b")

    /**
     * Reads the provider's public country index and current country pages.
     * Only provider-owned HTTPS URLs are followed. No account credentials are
     * requested, generated, uploaded or cached by this directory.
     */
    fun refresh(): List<Ikev2FreeServer> {
        val index = Jsoup.connect(DIRECTORY_URL)
            .userAgent("MolidoVPN-IKEv2-directory/1.0")
            .timeout(12_000)
            .maxBodySize(2 * 1024 * 1024)
            .get()

        val countryPages = index.select("a[href]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val absolute = link.absUrl("href")
                val slug = href.substringBefore('?').trimEnd('/')
                if (!slug.matches(Regex("^/free-ikev2-[a-z0-9-]+$"))) return@mapNotNull null
                if (!absolute.startsWith("https://$PROVIDER_HOST/")) return@mapNotNull null
                val country = link.text().trim().removePrefix("IKEV2 MSCHPv2 ").trim()
                if (country.isBlank()) return@mapNotNull null
                country to absolute
            }
            .distinctBy { it.second }
            .take(80)

        val pool = Executors.newFixedThreadPool(5)
        return try {
            val tasks = countryPages.map { (country, url) ->
                Callable { readCountry(country, url) }
            }
            pool.invokeAll(tasks).flatMap { future ->
                try { future.get() } catch (_: Exception) { emptyList() }
            }.distinctBy { it.host }.sortedBy { it.country.lowercase() + it.host }
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun parseCountryHtml(country: String, url: String, html: String): List<Ikev2FreeServer> {
        val doc = Jsoup.parse(html, url)
        val text = doc.text()
        val listedAvailable = Regex("(?i)\\bAvailable\\b").containsMatchIn(text) &&
            !Regex("(?i)\\b(Not Available|Unavailable)\\b").containsMatchIn(text)
        val accountLinks = doc.select("a[href*='create-free-account']")
            .mapNotNull { link ->
                val absolute = link.absUrl("href")
                if (absolute.startsWith("https://$PROVIDER_HOST/")) absolute else null
            }
        return serverPattern.findAll(text).mapNotNull { match ->
            val host = match.groupValues[1].lowercase()
            if (host == PROVIDER_HOST || host == "vpnjantit.com") return@mapNotNull null
            val code = host.substringBefore('.')
            val accountUrl = accountLinks.firstOrNull { it.contains("server=$code", ignoreCase = true) }
                ?: return@mapNotNull null
            Ikev2FreeServer(
                country = country,
                host = host,
                identity = "vpnjantit",
                accountUrl = accountUrl,
                listedAvailable = listedAvailable,
            )
        }.distinctBy { it.host }
    }

    private fun readCountry(country: String, url: String): List<Ikev2FreeServer> {
        val doc = Jsoup.connect(url)
            .userAgent("MolidoVPN-IKEv2-directory/1.0")
            .timeout(10_000)
            .maxBodySize(2 * 1024 * 1024)
            .get()
        return parseCountryHtml(country, url, doc.html())
    }
}

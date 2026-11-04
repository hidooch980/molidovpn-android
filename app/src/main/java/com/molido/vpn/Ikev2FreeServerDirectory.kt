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
    const val GITHUB_MIRROR_URL = "https://raw.githubusercontent.com/hidooch980/molidovpn-android/main/data/ikev2/free-servers.json"
    private const val PROVIDER_HOST = "www.vpnjantit.com"
    private val serverPattern = Regex("(?i)\\b([a-z0-9-]+\\.vpnjantit\\.com)\\b")

    /**
     * Reads the provider's public country index and current country pages.
     * Only provider-owned HTTPS URLs are followed. No account credentials are
     * requested, generated, uploaded or cached by this directory.
     */
    fun refresh(): List<Ikev2FreeServer> {
        return try {
            refreshProvider()
        } catch (providerError: Exception) {
            try {
                refreshGitHubMirror()
            } catch (mirrorError: Exception) {
                providerError.addSuppressed(mirrorError)
                throw providerError
            }
        }
    }

    private fun refreshGitHubMirror(): List<Ikev2FreeServer> {
        val raw = Jsoup.connect(GITHUB_MIRROR_URL)
            .userAgent("MolidoVPN-IKEv2-directory/1.0")
            .timeout(10_000)
            .maxBodySize(2 * 1024 * 1024)
            .ignoreContentType(true)
            .execute()
            .body()
        val root = org.json.JSONObject(raw)
        val rows = root.optJSONArray("servers")
            ?: throw IllegalStateException("GitHub IKEv2 mirror has no server list")
        val servers = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val host = row.optString("host").lowercase()
            val accountUrl = row.optString("accountUrl")
            if (!Ikev2ProfileValidation.isValidServer(host) ||
                !host.endsWith(".vpnjantit.com") ||
                !accountUrl.startsWith("https://$PROVIDER_HOST/")) return@mapNotNull null
            Ikev2FreeServer(
                country = row.optString("country"),
                host = host,
                identity = row.optString("identity", "vpnjantit"),
                accountUrl = accountUrl,
                listedAvailable = row.optBoolean("available", false),
            )
        }.distinctBy { it.host }
        require(servers.isNotEmpty()) { "GitHub IKEv2 mirror contains no validated servers" }
        return servers
    }

    private fun refreshProvider(): List<Ikev2FreeServer> {
        val index = Jsoup.connect(DIRECTORY_URL)
            .userAgent("MolidoVPN-IKEv2-directory/1.0")
            .timeout(12_000)
            .maxBodySize(2 * 1024 * 1024)
            .get()
        require(index.location().startsWith("https://$PROVIDER_HOST/")) {
            "IKEv2 directory redirected outside the provider domain"
        }

        val countryPages = index.select("a[href]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val absolute = link.absUrl("href")
                if (!absolute.startsWith("https://$PROVIDER_HOST/")) return@mapNotNull null
                val slug = try { java.net.URI(absolute).path?.trimEnd('/') ?: "" }
                    catch (_: Exception) { return@mapNotNull null }
                if (!slug.matches(Regex("^/free-ikev2-[a-z0-9-]+$"))) return@mapNotNull null
                val country = link.text().trim().removePrefix("IKEV2 MSCHPv2 ").trim()
                if (country.isBlank()) return@mapNotNull null
                country to absolute
            }
            .distinctBy { it.second }
            .take(80)

        require(countryPages.isNotEmpty()) { "The provider index contained no recognized IKEv2 country pages" }
        val pool = Executors.newFixedThreadPool(5)
        val servers = try {
            val tasks = countryPages.map { (country, url) ->
                Callable { readCountry(country, url) }
            }
            pool.invokeAll(tasks).flatMap { future ->
                try { future.get() } catch (_: Exception) { emptyList() }
            }.distinctBy { it.host }.sortedBy { it.country.lowercase() + it.host }
        } finally {
            pool.shutdownNow()
        }
        require(servers.isNotEmpty()) { "The provider returned no valid IKEv2 endpoints; preserve the last known-good cache" }
        return servers
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
        require(doc.location().startsWith("https://$PROVIDER_HOST/")) {
            "IKEv2 country page redirected outside the provider domain"
        }
        return parseCountryHtml(country, url, doc.html())
    }
}

/** Shared non-secret cache used by the foreground picker and periodic refresh job. */
object Ikev2FreeServerCache {
    private const val PREFS = "ikev2_free_directory"
    private const val JSON = "servers_json"
    private const val UPDATED_AT = "updated_at"

    fun save(context: android.content.Context, servers: List<Ikev2FreeServer>) {
        val rows = org.json.JSONArray()
        servers.forEach { item ->
            rows.put(org.json.JSONObject().apply {
                put("country", item.country)
                put("host", item.host)
                put("identity", item.identity)
                put("accountUrl", item.accountUrl)
                put("available", item.listedAvailable)
            })
        }
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString(JSON, rows.toString())
            .putLong(UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: android.content.Context): Pair<List<Ikev2FreeServer>, Long> {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(JSON, null) ?: return emptyList<Ikev2FreeServer>() to 0L
        return try {
            val rows = org.json.JSONArray(raw)
            val result = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val host = row.optString("host")
                val url = row.optString("accountUrl")
                if (!Ikev2ProfileValidation.isValidServer(host) ||
                    !url.startsWith("https://www.vpnjantit.com/")) return@mapNotNull null
                Ikev2FreeServer(
                    country = row.optString("country"),
                    host = host,
                    identity = row.optString("identity", "vpnjantit"),
                    accountUrl = url,
                    listedAvailable = row.optBoolean("available", false),
                )
            }
            result to prefs.getLong(UPDATED_AT, 0L)
        } catch (_: Exception) {
            emptyList<Ikev2FreeServer>() to 0L
        }
    }
}

package com.molido.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings for the "national internet / shutdown" features, delivered without a new build:
 *
 *  * `mirrors.update` — extra places the in-app updater looks for `latest.json` and the APKs
 *    (a Cloudflare Pages/R2 copy, an ArvanCloud bucket, ...). Tried after the worker and GitHub.
 *  * `mirrors.sub`    — extra full URLs of the V2Ray subscription text. Tried after the built-in ones.
 *  * `dns_tunnel`     — the dnstt last-resort mode ([DnsTunnel]). Off unless the owner turns it on.
 *
 * Source order: `remote/resilience.json` from this repo (raw GitHub → jsDelivr → worker), then the same file on every known update mirror. Fallback: last good cache, then the
 * copy bundled into the APK (`assets/resilience-defaults.json`). Every field is validated; a bad
 * edit degrades to the previous values, never to a broken app. Empty lists are always safe.
 */
object ResilienceConfig {

    private const val TAG = "Resilience"
    private const val CACHE_FILE = "resilience.json"
    private const val ASSET = "resilience-defaults.json"
    private const val LAST_CHECK_PREF = "resilience_last_check"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_BODY_CHARS = 32 * 1024
    private const val MAX_MIRRORS = 8
    private const val MAX_RESOLVERS = 16

    val SOURCE_URLS = listOf(
        "https://raw.githubusercontent.com/hidooch980/molidovpn-android/main/remote/resilience.json",
        "https://cdn.jsdelivr.net/gh/hidooch980/molidovpn-android@main/remote/resilience.json",
        "https://molido-sub.hidooch980.workers.dev/remote/resilience.json",
    )

    data class DnsTunnelSettings(
        val enabled: Boolean = false,
        val domain: String = "",
        val pubkey: String = "",
        /** Plain UDP resolvers, `ip` or `ip:port`. Domestic ones belong here. */
        val udpResolvers: List<String> = emptyList(),
        val dohUrls: List<String> = emptyList(),
        val dotAddrs: List<String> = emptyList(),
        /** Also try the phone's own (carrier / Wi-Fi) DNS servers. */
        val useSystemDns: Boolean = true,
        /** Last step of Automatic. Off = only when picked by hand. */
        val inAuto: Boolean = true,
    ) {
        /** Has everything a client needs. Says nothing about the binary being in this build. */
        val usable: Boolean get() = enabled && domain.isNotEmpty() && pubkey.isNotEmpty() &&
            (udpResolvers.isNotEmpty() || dohUrls.isNotEmpty() || dotAddrs.isNotEmpty() || useSystemDns)
    }

    data class Config(
        val updateMirrors: List<String> = emptyList(),
        val subMirrors: List<String> = emptyList(),
        val dnsTunnel: DnsTunnelSettings = DnsTunnelSettings(),
    )

    @Volatile private var cached: Config? = null
    private val refreshing = AtomicBoolean(false)

    // ------------------------------------------------------------------ pure parts (unit tested)

    private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val PUBKEY = Regex("^[0-9a-f]{64}$")
    private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")

    fun isDomain(value: String): Boolean {
        val v = value.trim().trimEnd('.').lowercase()
        if (v.length !in 4..200) return false
        val labels = v.split('.')
        return labels.size >= 2 && labels.all { HOST_LABEL.matches(it) }
    }

    /** `1.2.3.4` or `1.2.3.4:53` (port defaults to 53) → canonical `ip:port`, or null. */
    fun resolver(value: String): String? {
        val v = value.trim()
        val host = v.substringBefore(':')
        val port = if (':' in v) v.substringAfter(':').toIntOrNull() ?: return null else 53
        if (!IPV4.matches(host) || host.split('.').any { it.toInt() > 255 }) return null
        if (port !in 1..65535) return null
        return "$host:$port"
    }

    /** https URL with a host and no credentials / whitespace; trailing slash removed. */
    fun httpsUrl(value: String): String? {
        val v = value.trim().trimEnd('/')
        if (!v.startsWith("https://") || v.length > 300 || v.any { it.isWhitespace() }) return null
        val host = v.removePrefix("https://").substringBefore('/').substringBefore(':')
        if ('@' in v.removePrefix("https://").substringBefore('/')) return null
        return if (isDomain(host) || IPV4.matches(host)) v else null
    }

    private fun strings(array: JSONArray?, cap: Int, map: (String) -> String?): List<String> {
        if (array == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until minOf(array.length(), cap * 4)) {
            map(array.optString(i, ""))?.let(out::add)
            if (out.size >= cap) break
        }
        return out.toList()
    }

    /** Parses one file. Invalid entries are dropped; a missing section is the empty default. */
    fun parse(text: String): Config {
        val json = JSONObject(text)
        val mirrors = json.optJSONObject("mirrors")
        val dns = json.optJSONObject("dns_tunnel")
        val settings = if (dns == null) DnsTunnelSettings() else {
            val domain = dns.optString("domain", "").trim().trimEnd('.').lowercase().takeIf(::isDomain).orEmpty()
            val pubkey = dns.optString("pubkey", "").trim().lowercase().takeIf { PUBKEY.matches(it) }.orEmpty()
            DnsTunnelSettings(
                enabled = dns.optBoolean("enabled", false),
                domain = domain,
                pubkey = pubkey,
                udpResolvers = strings(dns.optJSONArray("udp"), MAX_RESOLVERS, ::resolver),
                dohUrls = strings(dns.optJSONArray("doh"), MAX_RESOLVERS, ::httpsUrl),
                dotAddrs = strings(dns.optJSONArray("dot"), MAX_RESOLVERS) { v ->
                    // DoT is ip:853 (or ip:port); never a bare UDP resolver by mistake.
                    resolver(if (':' in v) v else "$v:853")
                },
                useSystemDns = dns.optBoolean("system_dns", true),
                inAuto = dns.optBoolean("in_auto", true),
            )
        }
        return Config(
            updateMirrors = strings(mirrors?.optJSONArray("update"), MAX_MIRRORS, ::httpsUrl),
            subMirrors = strings(mirrors?.optJSONArray("sub"), MAX_MIRRORS, ::httpsUrl),
            dnsTunnel = settings,
        )
    }

    /**
     * Remote over bundled: mirrors are the union (remote first) so a baked-in mirror survives a
     * remote file that forgot it; the DNS tunnel block is the remote one when it says anything.
     */
    fun merge(remote: Config?, bundled: Config): Config {
        if (remote == null) return bundled
        return Config(
            updateMirrors = (remote.updateMirrors + bundled.updateMirrors).distinct().take(MAX_MIRRORS),
            subMirrors = (remote.subMirrors + bundled.subMirrors).distinct().take(MAX_MIRRORS),
            dnsTunnel = if (remote.dnsTunnel == DnsTunnelSettings()) bundled.dnsTunnel else remote.dnsTunnel,
        )
    }

    /** Where to look for this file: the fixed sources, then `<mirror>/remote/resilience.json`. */
    fun sourceUrls(updateMirrors: List<String>): List<String> =
        (SOURCE_URLS + updateMirrors.map { "$it/remote/resilience.json" }).distinct()

    // ------------------------------------------------------------------ Android side

    private fun bundled(context: Context): Config = try {
        context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
    } catch (_: Exception) {
        Config()
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    fun get(context: Context): Config {
        cached?.let { return it }
        val app = context.applicationContext
        val remote = try {
            cacheFile(app).takeIf { it.exists() }?.readText()?.let(::parse)
        } catch (_: Exception) {
            null
        }
        return merge(remote, bundled(app)).also { cached = it }
    }

    fun updateMirrors(context: Context): List<String> = get(context).updateMirrors
    fun subMirrors(context: Context): List<String> = get(context).subMirrors
    fun dnsTunnel(context: Context): DnsTunnelSettings = get(context).dnsTunnel

    /** Background refresh with a 6 h floor. Safe to call from any trigger. */
    fun refreshIfDue(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val elapsed = System.currentTimeMillis() - prefs.getLong(LAST_CHECK_PREF, 0L)
        if (!force && elapsed in 0 until MIN_INTERVAL_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        Thread({
            try {
                for (url in sourceUrls(get(app).updateMirrors)) {
                    val body = fetch(url) ?: continue
                    val parsed = runCatching { parse(body) }.getOrNull() ?: continue
                    cacheFile(app).writeText(body)
                    cached = merge(parsed, bundled(app))
                    prefs.edit().putLong(LAST_CHECK_PREF, System.currentTimeMillis()).apply()
                    ConnectionLog.record("$TAG: updated from ${URL(url).host} — " +
                        "${parsed.updateMirrors.size} update mirror(s), ${parsed.subMirrors.size} sub mirror(s), " +
                        "dns tunnel ${if (parsed.dnsTunnel.usable) "on" else "off"}")
                    break
                }
            } catch (_: Exception) {
            } finally {
                refreshing.set(false)
            }
        }, "resilience-refresh").apply { isDaemon = true }.start()
    }

    private fun fetch(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "MolidoVPN-Android")
            if (c.responseCode != 200) null
            else c.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() && it.length <= MAX_BODY_CHARS }
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

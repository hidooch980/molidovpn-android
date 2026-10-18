package com.molido.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebTunnel bridges for Tor: the transport Tor's own Connection Assist tries
 * **first** for Iran (then Snowflake, then obfs4). A WebTunnel bridge is an
 * ordinary HTTPS website with a secret path; to a censor it is just a TLS
 * connection to that site, so it cannot be blocked by protocol fingerprint.
 * lyrebird (our `libobfs4proxy.so`) already speaks it, so this only supplies
 * bridge lines.
 *
 * Unlike obfs4/meek/snowflake, Tor ships no fixed built-in WebTunnel bridges:
 * they are handed out by BridgeDB and rotate. So the lines come from three places,
 * merged in this order and de-duplicated:
 *
 *  1. `tor.webtunnel_bridges` in `remote/resilience.json` — the owner can paste
 *     fresh lines (e.g. from @GetBridgesBot `/webtunnel`) without a new build.
 *  2. The last answer from Tor's Moat API (`circumvention/settings`, country `ir`),
 *     cached on disk. Moat answers with the bridges Tor recommends for Iran.
 *  3. A fresh Moat request at connect time (short timeout, skipped when
 *     `tor.moat` is off). From inside Iran this may well be blocked; then 1 and 2
 *     are what we have.
 *
 * No bridge line is compiled into the APK on purpose: BridgeDB bridges are meant
 * to be distributed a few at a time, and publishing them in an app would hand the
 * whole set to the censor.
 */
object TorWebTunnel {

    private const val TAG = "Tor/WebTunnel"
    private const val CACHE_FILE = "tor_webtunnel_moat.txt"
    private const val MAX_LINES = 8
    const val MOAT_URL = "https://bridges.torproject.org/moat/circumvention/settings"

    private val FINGERPRINT = Regex("^[0-9A-Fa-f]{40}$")

    // ------------------------------------------------------------------ pure parts (unit tested)

    /**
     * A usable `webtunnel <addr:port> <FP> url=https://… [ver=…]` line, trimmed, or null.
     * A leading `Bridge ` (torrc syntax) is accepted and dropped.
     */
    fun normalize(raw: String): String? {
        val v = raw.trim().removePrefix("Bridge ").trim()
        if (v.length > 600 || v.contains('\n')) return null
        val parts = v.split(Regex("\\s+"))
        if (parts.size < 4 || parts[0] != "webtunnel") return null
        if (!parts[1].contains(':')) return null
        if (!FINGERPRINT.matches(parts[2])) return null
        val url = parts.drop(3).firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
        if (ResilienceConfig.httpsUrl(url.substringBefore('?')) == null) return null
        return parts.joinToString(" ")
    }

    /** All valid lines from a list, de-duplicated by fingerprint, capped. */
    fun clean(lines: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (l in lines) {
            val n = normalize(l) ?: continue
            if (seen.add(n.split(' ')[2].uppercase())) out += n
            if (out.size >= MAX_LINES) break
        }
        return out
    }

    /** The webtunnel lines in a Moat `circumvention/settings` reply; empty when there are none. */
    fun parseMoat(body: String): List<String> = try {
        val settings = JSONObject(body).optJSONArray("settings") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until settings.length()) {
            val bridges = settings.optJSONObject(i)?.optJSONObject("bridges") ?: continue
            if (bridges.optString("type") != "webtunnel") continue
            val strings = bridges.optJSONArray("bridge_strings") ?: continue
            for (j in 0 until strings.length()) out += strings.optString(j)
        }
        clean(out)
    } catch (_: Exception) {
        emptyList()
    }

    /** The JSON body asked of Moat. */
    fun moatRequest(country: String = "ir"): String =
        JSONObject().put("country", country).put("transports", JSONArray().put("webtunnel")).toString()

    // ------------------------------------------------------------------ Android side

    private fun cacheFile(context: Context) = File(context.applicationContext.filesDir, CACHE_FILE)

    private fun cachedMoat(context: Context): List<String> = try {
        cacheFile(context).takeIf { it.exists() }?.readLines()?.let(::clean).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** Lines available right now without touching the network. */
    fun known(context: Context): List<String> =
        clean(ResilienceConfig.tor(context).webtunnelBridges + cachedMoat(context))

    /**
     * Lines for a connect attempt. Asks Moat first when allowed (bounded by [timeoutMs]),
     * then falls back to whatever is known. Blocking — worker thread only.
     */
    fun bridges(context: Context, timeoutMs: Int = 8_000): List<String> {
        val settings = ResilienceConfig.tor(context)
        if (settings.moat) {
            val fresh = fetchMoat(timeoutMs)
            if (fresh.isNotEmpty()) {
                runCatching { cacheFile(context).writeText(fresh.joinToString("\n")) }
                ConnectionLog.record("$TAG Moat answered with ${fresh.size} bridge(s)")
            } else {
                ConnectionLog.record("$TAG Moat unreachable or empty — using saved lines")
            }
        }
        return known(context)
    }

    private fun fetchMoat(timeoutMs: Int): List<String> = try {
        val c = URL(MOAT_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/vnd.api+json")
            c.outputStream.use { it.write(moatRequest().toByteArray()) }
            if (c.responseCode != 200) emptyList()
            else c.inputStream.bufferedReader().use { it.readText() }.take(64 * 1024).let(::parseMoat)
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

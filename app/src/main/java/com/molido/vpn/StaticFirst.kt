package com.molido.vpn

import java.net.HttpURLConnection
import java.net.URL

/**
 * Small app files read from static copies first, the Worker last.
 *
 * The vpn-sub workflow mirrors the Worker's small endpoints (flags, notice, latest.json,
 * changelog, scores, cfip, the ranked catalog) into `meta/` on its `sub` branch every ~15 min.
 * Phones read GitHub raw, then jsDelivr, and only then the Worker: the free Cloudflare plan is
 * 100k requests/day for the whole account, and the Worker kept running out by the afternoon.
 */
object StaticFirst {
    const val RAW = "https://raw.githubusercontent.com/hidooch980/vpn-sub/sub/meta/"
    const val CDN = "https://cdn.jsdelivr.net/gh/hidooch980/vpn-sub@sub/meta/"
    const val WORKER = "https://molido-sub.hidooch980.workers.dev"

    /** Static copies of [file] first, then the Worker's [workerPath]. */
    fun urls(file: String, workerPath: String): List<String> = listOf(RAW + file, CDN + file, WORKER + workerPath)

    /** The `live` branch copy ([LiveSync]) first, then [urls]. */
    fun liveUrls(file: String, workerPath: String): List<String> =
        listOf(LiveSync.RAW + file, LiveSync.CDN + file) + urls(file, workerPath)

    /** File name of the mirrored `/scores?op=` or `/cfip?op=` copy. */
    fun opFile(kind: String, op: String): String = "$kind-${op.ifEmpty { "all" }}.json"

    /** Sequential: the first 200 whose body passes [accept]; null when all fail. Blocking. */
    fun get(urls: List<String>, timeoutMs: Int = 8_000, accept: (String) -> Boolean = { it.isNotBlank() }): String? {
        for (u in urls) {
            val body = try {
                val c = URL(u).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = timeoutMs
                    c.readTimeout = timeoutMs
                    if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                null
            }
            if (body != null && runCatching { accept(body) }.getOrDefault(false)) return body
        }
        return null
    }
}

/**
 * The owner's Cloudflare edge routes (molido-edge Worker). Out of the normal pool and the race:
 * every proxied connection is a Worker request on the same 100k/day account quota and the Worker
 * CPU cap throttles video. Armed only after a Smart Connect run where nothing connected, and only
 * while the remote `edge_last_resort` flag is on.
 */
object EdgeFallback {
    private const val FILE = "edge-routes.txt"
    private const val ARMED_PREF = "edge_armed_until"
    private const val FETCHED_PREF = "edge_fetched_at"
    const val ARM_MS = 2 * 60 * 60 * 1000L
    private const val REFETCH_MS = 24 * 60 * 60 * 1000L

    /** True for an edge route, by its name (`☁️ MolidoVPN Cloudflare N`, raw or URL-encoded). */
    fun isEdge(line: String): Boolean {
        val name = line.substringAfter('#', "")
        if (name.isEmpty()) return false
        val decoded = runCatching { java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
        return decoded.contains("MolidoVPN Cloudflare")
    }

    fun armed(now: Long, armedUntil: Long, flag: Boolean): Boolean = flag && now < armedUntil

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** Nothing connected: let the next attempts include the edge routes for [ARM_MS]. */
    fun arm(context: android.content.Context) {
        if (!ResilienceConfig.get(context).edgeLastResort) return
        val app = context.applicationContext
        prefs(app).edit().putLong(ARMED_PREF, System.currentTimeMillis() + ARM_MS).apply()
        ConnectionLog.record("Edge fallback armed: nothing else connected")
        Thread({ runCatching { fetchIfDue(app) } }, "edge-fetch").apply { isDaemon = true }.start()
    }

    fun disarm(context: android.content.Context) {
        prefs(context).edit().remove(ARMED_PREF).apply()
    }

    private fun fetchIfDue(context: android.content.Context) {
        val p = prefs(context)
        val f = java.io.File(context.filesDir, FILE)
        if (f.exists() && System.currentTimeMillis() - p.getLong(FETCHED_PREF, 0L) in 0 until REFETCH_MS) return
        val body = StaticFirst.get(listOf(StaticFirst.WORKER + "/edge"), 10_000) {
            V2rayNodes.decodeBody(it).any { l -> l.contains("://") }
        } ?: return
        f.writeText(V2rayNodes.decodeBody(body).joinToString("\n"))
        p.edit().putLong(FETCHED_PREF, System.currentTimeMillis()).apply()
    }

    /** Edge routes to append to the pool, or empty when not armed. */
    fun lines(context: android.content.Context): List<String> {
        val on = armed(System.currentTimeMillis(), prefs(context).getLong(ARMED_PREF, 0L), ResilienceConfig.get(context).edgeLastResort)
        if (!on) return emptyList()
        return runCatching { java.io.File(context.filesDir, FILE).readLines().filter { it.contains("://") } }.getOrDefault(emptyList())
    }
}

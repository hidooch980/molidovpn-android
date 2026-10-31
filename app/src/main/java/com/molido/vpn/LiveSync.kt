package com.molido.vpn

import android.content.Context
import com.molido.vpn.engine.LiveSig
import com.molido.vpn.engine.LiveThrottle
import com.molido.vpn.engine.LiveVersion
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Near-real-time owner changes and releases without polling the Cloudflare Worker.
 *
 * Checks `version.json` on the vpn-sub `live` branch (GitHub raw, then jsDelivr) with ETag, so an
 * unchanged file is a 304 of a few hundred bytes. Triggers: app foreground, network change, connect,
 * every [OPEN_INTERVAL_MS] while the app is open, and a push hint ([LivePush]). Only the parts whose
 * hash moved are downloaded, and they are applied without a reconnect: flags and the notice update
 * the screen, the resilience file swaps the live thresholds, a new server list refreshes the pool
 * used by the next node switch, and a new release goes to the updater.
 */
object LiveSync {
    const val RAW = "https://raw.githubusercontent.com/hidooch980/vpn-sub/live/"
    const val CDN = "https://cdn.jsdelivr.net/gh/hidooch980/vpn-sub@live/"
    const val OPEN_INTERVAL_MS = 10 * 60 * 1000L
    private const val MIN_GAP_MS = 45_000L
    private const val PUSH_GAP_MS = 5_000L
    private const val CATALOG_GAP_MS = 20 * 60 * 1000L
    private const val PREFS = "live_sync"

    /** Set by the open screen; called on a worker thread with what changed. */
    @Volatile var listener: ((Set<LiveVersion.Part>, LiveVersion) -> Unit)? = null

    private val running = AtomicBoolean(false)
    @Volatile private var lastCheckAt = 0L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): LiveVersion? = LiveVersion.parse(prefs(context).getString("version", null))

    /** Cheap, throttled, background. [push] = woken by a push hint (shorter throttle). */
    fun check(context: Context, reason: String, push: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!LiveThrottle.due(lastCheckAt, now, if (push) PUSH_GAP_MS else MIN_GAP_MS)) return
        if (!running.compareAndSet(false, true)) return
        lastCheckAt = now
        val app = context.applicationContext
        Thread({
            try {
                checkNow(app, reason)
            } catch (_: Exception) {
            } finally {
                running.set(false)
            }
        }, "live-sync").apply { isDaemon = true }.start()
    }

    private fun checkNow(context: Context, reason: String) {
        val p = prefs(context)
        var body: String? = null
        for (base in listOf(RAW, CDN)) {
            val url = base + "version.json"
            val r = conditionalGet(url, p.getString("etag_$base", null)) ?: continue
            if (r.first == 304) return // unchanged
            body = r.second
            r.third?.let { p.edit().putString("etag_$base", it).apply() }
            break
        }
        val fresh = LiveVersion.parse(body) ?: return
        val old = current(context)
        if (LiveVersion.isOlder(old, fresh)) return
        val parts = LiveVersion.changes(old, fresh)
        p.edit().putString("version", fresh.toJson()).apply()
        if (parts.isEmpty()) return
        ConnectionLog.record("Live: ${parts.joinToString()} changed ($reason)")
        apply(context, parts, fresh, firstRun = old == null)
    }

    private fun apply(context: Context, parts: Set<LiveVersion.Part>, v: LiveVersion, firstRun: Boolean) {
        if (LiveVersion.Part.FLAGS in parts || LiveVersion.Part.NOTICE in parts) AppRemote.refreshNow(context)
        if (LiveVersion.Part.RESILIENCE in parts && !firstRun) ResilienceConfig.refreshIfDue(context, force = true)
        if (LiveVersion.Part.CATALOG in parts && !firstRun) {
            val p = prefs(context)
            val now = System.currentTimeMillis()
            if (now - p.getLong("catalog_at", 0L) !in 0 until CATALOG_GAP_MS) {
                p.edit().putLong("catalog_at", now).apply()
                V2raySubscription.refreshIfDue(context, force = true)
            }
        }
        if (LiveVersion.Part.RELEASE in parts) {
            prefs(context).edit().putString("release", v.release).putBoolean("critical", v.critical).apply()
        }
        runCatching { listener?.invoke(parts, v) }
    }

    /** Release tag seen by a check and not yet passed to the updater, or null. */
    fun pendingRelease(context: Context): String? {
        val p = prefs(context)
        val tag = p.getString("release", null)?.takeIf { it.isNotEmpty() } ?: return null
        return tag.takeIf { it != p.getString("release_handled", null) }
    }

    fun markReleaseHandled(context: Context, tag: String) {
        prefs(context).edit().putString("release_handled", tag).apply()
    }

    /** A signed owner change from the push channel: applied at once, no download. */
    fun applySigned(context: Context, message: String): Boolean {
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return false
        val payload = o.optString("p").takeIf { it.isNotEmpty() } ?: return false
        if (!LiveSig.verify(PUBLIC_KEY, payload, o.optString("s"))) {
            ConnectionLog.record("Live: push signature rejected")
            return false
        }
        val body = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        val at = body.optLong("at", 0L)
        val p = prefs(context)
        if (body.optString("t") != "live" || at <= p.getLong("push_at", 0L)) return false
        p.edit().putLong("push_at", at).apply()
        AppRemote.applyLive(context, body.optJSONObject("flags")?.toString(), body.optJSONObject("notice")?.toString())
        ConnectionLog.record("Live: owner change applied from push")
        val v = current(context) ?: LiveVersion("push")
        runCatching { listener?.invoke(setOf(LiveVersion.Part.FLAGS, LiveVersion.Part.NOTICE), v) }
        return true
    }

    /** (status, body, etag) or null on a network error. */
    private fun conditionalGet(url: String, etag: String?): Triple<Int, String?, String?>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", "MolidoVPN-Android")
            etag?.let { c.setRequestProperty("If-None-Match", it) }
            when (val code = c.responseCode) {
                304 -> Triple(304, null, etag)
                200 -> Triple(200, c.inputStream.bufferedReader().use { it.readText().take(4_096) }, c.getHeaderField("ETag"))
                else -> Triple(code, null, null).takeIf { false }
            }
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** Public half of the Worker's LIVE_SIGN_KEY (ECDSA P-256, SPKI, base64). */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwh0XBFjfuriczf83Cnm1Ro16JuLxZ0oFAccBsFtUduwRKTGnpVRrnanJaAmUrgrm4xnjl7SujF7qj/7z2odHvg=="
}

/**
 * Optional push channel (free ntfy.sh topic), only while the app is on screen: a held HTTP stream,
 * no polling. A "v" hint makes [LiveSync] check now; a signed owner change is applied directly.
 * Unreachable (ntfy can be filtered): it backs off quietly and the regular checks still work.
 */
object LivePush {
    const val DEFAULT_URL = "https://ntfy.sh/molidovpn-live-971a1da8ec4af75c"

    @Volatile private var thread: Thread? = null
    @Volatile private var conn: HttpURLConnection? = null

    fun start(context: Context) {
        val cfg = ResilienceConfig.get(context).livePush
        if (cfg == null || thread?.isAlive == true) return
        val app = context.applicationContext
        thread = Thread({ loop(app, cfg) }, "live-push").apply { isDaemon = true; start() }
    }

    fun stop() {
        val t = thread
        thread = null
        runCatching { conn?.disconnect() }
        t?.interrupt()
    }

    private fun loop(context: Context, base: String) {
        var backoff = 5_000L
        var since = "${System.currentTimeMillis() / 1000}"
        while (thread === Thread.currentThread()) {
            try {
                val c = URL("$base/json?since=$since").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 10_000
                c.readTimeout = 100_000 // ntfy sends a keepalive every ~45 s
                c.setRequestProperty("User-Agent", "MolidoVPN-Android")
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (thread !== Thread.currentThread()) return
                        backoff = 5_000L
                        val e = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        e.optLong("time", 0L).takeIf { it > 0 }?.let { since = "$it" }
                        if (e.optString("event") != "message") continue
                        val msg = e.optString("message")
                        if (!LiveSync.applySigned(context, msg)) LiveSync.check(context, "push", push = true)
                    }
                }
            } catch (_: Exception) {
            }
            if (thread !== Thread.currentThread()) return
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
            backoff = (backoff * 2).coerceAtMost(120_000L)
        }
    }
}

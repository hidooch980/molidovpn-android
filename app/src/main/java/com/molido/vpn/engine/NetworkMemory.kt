package com.molido.vpn.engine

import org.json.JSONObject

/**
 * Winner and runner-up per network (`cell:43235`, `wifi:<hash>`), so the next connect on the
 * same network starts with what worked there. Stored as one small JSON object; the 16 most
 * recently written networks are kept and entries older than the TTL are ignored.
 */
object NetworkMemory {

    const val MAX_NETWORKS = 16

    data class Entry(
        val winner: String,
        val runnerUp: String? = null,
        val score: Double = 0.0,
        val at: Long = 0L,
    )

    fun read(json: String?, key: String, now: Long, ttlDays: Int): Entry? = try {
        val e = JSONObject(json ?: "{}").optJSONObject(key) ?: return null
        val winner = e.optString("w").takeIf { it.isNotEmpty() } ?: return null
        val at = e.optLong("at", 0L)
        if (now - at > ttlDays * 86_400_000L || at > now + 60_000L) return null
        Entry(winner, e.optString("r").takeIf { it.isNotEmpty() }, e.optDouble("s", 0.0), at)
    } catch (_: Exception) {
        null
    }

    fun write(json: String?, key: String, entry: Entry): String {
        val old = try { JSONObject(json ?: "{}") } catch (_: Exception) { JSONObject() }
        old.remove(key)
        val keys = old.keys().asSequence().toList()
            .sortedBy { old.optJSONObject(it)?.optLong("at", 0L) ?: 0L }
            .takeLast(MAX_NETWORKS - 1)
        val out = JSONObject()
        keys.forEach { out.put(it, old.get(it)) }
        out.put(key, JSONObject().put("w", entry.winner).put("r", entry.runnerUp ?: "")
            .put("s", entry.score).put("at", entry.at))
        return out.toString()
    }

    /** Key for a network. Wi-Fi uses a hash of gateway + DNS (no SSID: that needs location). */
    fun key(isWifi: Boolean, isEthernet: Boolean, mccMnc: String?, wifiFingerprint: String?): String = when {
        isWifi -> "wifi:" + (wifiFingerprint?.takeIf { it.isNotBlank() }?.let { shortHash(it) } ?: "?")
        isEthernet -> "eth"
        !mccMnc.isNullOrBlank() -> "cell:$mccMnc"
        else -> "unknown"
    }

    fun shortHash(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return md.take(4).joinToString("") { "%02x".format(it) }
    }
}

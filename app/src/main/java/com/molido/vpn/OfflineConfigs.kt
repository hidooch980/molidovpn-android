package com.molido.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Configs that still work when nothing remote can be reached (national internet, worker and
 * GitHub filtered, fresh install on a cut-off network).
 *
 *  1. **Known-good cache**: every V2Ray server that actually carried a probe on this phone is
 *     remembered (newest success first, [PER_MODE] per mode, dropped after [MAX_AGE_MS]). Stored
 *     encrypted at rest with the Android Keystore key [SecureStore] already uses — the file is
 *     useless off the device, so a seized backup does not hand out the servers.
 *  2. **Emergency set**: `assets/emergency-configs.txt`, refreshed at build time by
 *     `tools/refresh-emergency-configs.py` from the best-scored servers of the day.
 *
 * [V2raySubscription.nodes] always adds (1) to the pool, and uses (1)+(2) when it has no list at all,
 * so Automatic and the V2Ray mode keep working with no source reachable.
 */
object OfflineConfigs {

    private const val TAG = "OfflineConfigs"
    private const val FILE = "known-good.bin"
    private const val ASSET = "emergency-configs.txt"
    const val PER_MODE = 24
    const val MAX_AGE_MS = 21L * 24 * 60 * 60 * 1000
    /** Don't rewrite the file for a node that was already saved this recently. */
    private const val REWRITE_FLOOR_MS = 10 * 60 * 1000L

    data class Entry(val mode: String, val uri: String, val lastOk: Long, val okCount: Int)

    @Volatile private var memory: List<Entry>? = null
    private val lock = Any()

    // ------------------------------------------------------------------ pure parts (unit tested)

    /** Adds or refreshes [uri], then trims: recent first, at most [perMode] per mode, nothing stale. */
    fun upsert(
        entries: List<Entry>,
        mode: String,
        uri: String,
        now: Long,
        perMode: Int = PER_MODE,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<Entry> {
        val old = entries.firstOrNull { it.mode == mode && it.uri == uri }
        val updated = Entry(mode, uri, now, (old?.okCount ?: 0) + 1)
        return trim(entries.filterNot { it.mode == mode && it.uri == uri } + updated, now, perMode, maxAgeMs)
    }

    fun trim(entries: List<Entry>, now: Long, perMode: Int = PER_MODE, maxAgeMs: Long = MAX_AGE_MS): List<Entry> =
        entries.filter { now - it.lastOk in 0..maxAgeMs }
            .groupBy { it.mode }
            .flatMap { (_, list) -> list.sortedWith(compareByDescending<Entry> { it.lastOk }.thenByDescending { it.okCount }).take(perMode) }
            .sortedByDescending { it.lastOk }

    fun encode(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("m", it.mode).put("u", it.uri).put("t", it.lastOk).put("n", it.okCount)) }
    }.toString()

    fun decode(text: String): List<Entry> = try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("u")
            if (uri.isEmpty() || !uri.contains("://")) null
            else Entry(o.optString("m", "v2ray"), uri, o.optLong("t"), o.optInt("n", 1))
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Lines of the bundled set: share URIs only, `#` comments and blanks skipped. */
    fun emergencyLines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("://") }.toList()

    // ------------------------------------------------------------------ Android side

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun entries(context: Context): List<Entry> {
        memory?.let { return it }
        synchronized(lock) {
            memory?.let { return it }
            val loaded = try {
                val f = file(context)
                if (!f.exists()) emptyList() else decode(SecureStore.decrypt(f.readText()))
            } catch (_: Exception) {
                emptyList()
            }
            return trim(loaded, System.currentTimeMillis()).also { memory = it }
        }
    }

    /** A probe through [uri] succeeded. Cheap when called often (scanner + connects). */
    fun recordSuccess(context: Context, mode: String, uri: String) {
        if (uri.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val current = entries(context)
            val existing = current.firstOrNull { it.mode == mode && it.uri == uri }
            if (existing != null && now - existing.lastOk in 0 until REWRITE_FLOOR_MS) return
            val next = upsert(current, mode, uri, now)
            memory = next
            try {
                file(context).writeText(SecureStore.encrypt(encode(next)))
            } catch (e: Exception) {
                ConnectionLog.record("$TAG save failed: ${e.message}")
            }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            memory = emptyList()
            file(context).delete()
        }
    }

    fun knownGoodLines(context: Context, mode: String = "v2ray"): List<String> =
        entries(context).filter { it.mode == mode && !EdgeFallback.isEdge(it.uri) }.map { it.uri }

    fun emergency(context: Context): List<String> = try {
        context.assets.open(ASSET).bufferedReader().use { emergencyLines(it.readText()) }
    } catch (_: Exception) {
        emptyList()
    }

    /** [pool] plus remembered servers that are not in it, remembered ones first. */
    fun withKnownGood(context: Context, pool: List<V2rayNode>): List<V2rayNode> {
        val extra = V2rayNodes.parse(knownGoodLines(context))
        if (extra.isEmpty()) return pool
        val seen = pool.mapTo(HashSet()) { it.uri }
        val missing = extra.filter { it.uri !in seen }
        return if (missing.isEmpty()) pool else missing + pool
    }

    /** Everything usable with no network source: remembered first, then the bundled set. */
    fun offlineNodes(context: Context): List<V2rayNode> =
        V2rayNodes.parse(knownGoodLines(context) + emergency(context))
}

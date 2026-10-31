package com.molido.vpn.engine

import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What wins where and when: per network and time of day (four 6-hour buckets), the mean reward of
 * each method, kept on the device only. Rewards are Smart Connect scores (0..1, throughput-heavy, so
 * video-capable routes rank higher) and a penalty when a session had to switch away for stalls.
 *
 * [rank] is UCB1: mean + c·√(ln N / n). A method never tried here gets [unseen], a mild optimism, so
 * new methods are explored without ever outranking a proven fast one. Old evidence fades: each update
 * keeps at most [MAX_N] effective samples, so a network that changed is relearned within a few days.
 * Pure (JSON in, JSON out), so it is unit tested.
 */
object MethodBandit {
    const val MAX_KEYS = 32
    const val MAX_N = 20.0
    private const val C = 0.35

    fun bucket(hourOfDay: Int): Int = (hourOfDay.coerceIn(0, 23)) / 6

    fun key(netKey: String, hourOfDay: Int): String = "$netKey@${bucket(hourOfDay)}"

    data class Arm(val n: Double, val mean: Double)

    fun arms(json: String?, key: String): Map<String, Arm> = try {
        val o = JSONObject(json ?: "{}").optJSONObject(key) ?: JSONObject()
        o.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { id ->
            val a = o.getJSONObject(id)
            Arm(a.optDouble("n", 0.0), a.optDouble("m", 0.0))
        }.filterValues { it.n > 0 && !it.mean.isNaN() }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Adds one reward (clamped to 0..1) for [method]. */
    fun record(json: String?, key: String, method: String, reward: Double, now: Long): String {
        val root = try { JSONObject(json ?: "{}") } catch (_: Exception) { JSONObject() }
        val o = root.optJSONObject(key) ?: JSONObject()
        val a = o.optJSONObject(method)
        val n = (a?.optDouble("n", 0.0) ?: 0.0).coerceAtMost(MAX_N - 1)
        val m = a?.optDouble("m", 0.0) ?: 0.0
        val r = reward.coerceIn(0.0, 1.0)
        o.put(method, JSONObject().put("n", n + 1).put("m", m + (r - m) / (n + 1)))
        o.put("_at", now)
        root.put(key, o)
        // Keep the most recently updated keys.
        val keys = root.keys().asSequence().toList()
        if (keys.size > MAX_KEYS) {
            keys.sortedBy { root.optJSONObject(it)?.optLong("_at", 0L) ?: 0L }
                .take(keys.size - MAX_KEYS).forEach { root.remove(it) }
        }
        return root.toString()
    }

    /** UCB1 score per method id in [ids]; higher = try earlier. */
    fun rank(arms: Map<String, Arm>, ids: Collection<String>, unseen: Double = 0.55): Map<String, Double> {
        val total = arms.filterKeys { !it.startsWith("_") }.values.sumOf { it.n }
        return ids.associateWith { id ->
            val a = arms[id]
            if (a == null || a.n <= 0) unseen
            else a.mean + C * sqrt(ln(maxOf(total, 1.0) + 1.0) / a.n)
        }
    }
}

package com.molido.vpn.engine

import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Per-network and time-of-day online learning for connection methods.
 *
 * Rewards are bounded Smart Connect scores (0..1). UCB1 balances proven quality
 * with limited exploration. All persisted values are validated before ranking:
 * malformed or non-finite state must never poison a route decision.
 */
object MethodBandit {
    const val MAX_KEYS = 32
    const val MAX_N = 20.0
    private const val C = 0.35
    private const val DEFAULT_UNSEEN = 0.55
    private val METHOD_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")

    fun bucket(hourOfDay: Int): Int = hourOfDay.coerceIn(0, 23) / 6

    fun key(netKey: String, hourOfDay: Int): String = "$netKey@${bucket(hourOfDay)}"

    data class Arm(val n: Double, val mean: Double)

    fun arms(json: String?, key: String): Map<String, Arm> = try {
        val o = JSONObject(json ?: "{}").optJSONObject(key) ?: JSONObject()
        o.keys().asSequence()
            .filter { it != "_at" && METHOD_ID.matches(it) }
            .associateWith { id ->
                val a = o.getJSONObject(id)
                Arm(a.optDouble("n", 0.0), a.optDouble("m", 0.0))
            }
            .filterValues { arm ->
                arm.n.isFinite() && arm.n in 1.0..MAX_N &&
                    arm.mean.isFinite() && arm.mean in 0.0..1.0
            }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Adds one finite reward (clamped to 0..1) for [method]. Invalid updates are ignored. */
    fun record(json: String?, key: String, method: String, reward: Double, now: Long): String {
        if (!METHOD_ID.matches(method) || !reward.isFinite() || now <= 0L) {
            return json ?: "{}"
        }

        val root = try { JSONObject(json ?: "{}") } catch (_: Exception) { JSONObject() }
        val o = root.optJSONObject(key) ?: JSONObject()
        val a = o.optJSONObject(method)
        val oldN = a?.optDouble("n", 0.0) ?: 0.0
        val oldMean = a?.optDouble("m", DEFAULT_UNSEEN) ?: DEFAULT_UNSEEN
        val n = if (oldN.isFinite() && oldN > 0.0) oldN.coerceAtMost(MAX_N - 1) else 0.0
        val mean = if (oldMean.isFinite()) oldMean.coerceIn(0.0, 1.0) else DEFAULT_UNSEEN
        val boundedReward = reward.coerceIn(0.0, 1.0)

        o.put(method, JSONObject()
            .put("n", n + 1.0)
            .put("m", if (n == 0.0) boundedReward else mean + (boundedReward - mean) / (n + 1.0)))
        o.put("_at", now)
        root.put(key, o)

        // Keep the most recently updated network/time buckets.
        val keys = root.keys().asSequence().toList()
        if (keys.size > MAX_KEYS) {
            keys.sortedBy { root.optJSONObject(it)?.optLong("_at", 0L) ?: 0L }
                .take(keys.size - MAX_KEYS)
                .forEach { root.remove(it) }
        }
        return root.toString()
    }

    /** UCB1 score per method id in [ids]; higher = try earlier. */
    fun rank(
        arms: Map<String, Arm>,
        ids: Collection<String>,
        unseen: Double = DEFAULT_UNSEEN,
    ): Map<String, Double> {
        val validArms = arms.filter { (id, arm) ->
            METHOD_ID.matches(id) && arm.n.isFinite() && arm.n in 1.0..MAX_N &&
                arm.mean.isFinite() && arm.mean in 0.0..1.0
        }
        val total = validArms.values.sumOf { it.n }
        val unseenScore = if (unseen.isFinite()) unseen.coerceIn(0.0, 1.0) else DEFAULT_UNSEEN
        return ids.associateWith { id ->
            val arm = validArms[id]
            if (arm == null) unseenScore
            else arm.mean + C * sqrt(ln(maxOf(total, 1.0) + 1.0) / arm.n)
        }
    }
}

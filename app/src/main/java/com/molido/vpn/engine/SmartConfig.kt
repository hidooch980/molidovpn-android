package com.molido.vpn.engine

import org.json.JSONObject

/**
 * Budgets and caps for Smart Connect, from `smart` in `remote/resilience.json`, so battery and
 * data use can be tuned without a build. Every value is clamped; a missing or bad section is
 * the defaults.
 */
data class SmartConfig(
    val enabled: Boolean = true,
    /** Wall-clock cap for one whole run (probes + trials). */
    val totalBudgetS: Int = 240,
    /** At most this many exclusive trials (each brings a tunnel up). */
    val maxTrials: Int = 8,
    /** Never stop before this many trials succeeded or were tried (when time allows). */
    val minTrials: Int = 3,
    /** Stop early once the best score reaches this and minTrials are done. */
    val goodEnough: Int = 80,
    /** Below this a method does not count as working. */
    val minScore: Int = 15,
    /** Concurrent cheap out-of-tunnel probes. */
    val parallelProbes: Int = 6,
    /** Repeated checks per trial for the stability figure. */
    val stabilityProbes: Int = 3,
    /** Small download per trial when the method exposes a local proxy. */
    val downloadBytes: Int = 1_500_000,
    /** Remembered winners per network expire after this. */
    val memoryTtlDays: Int = 14,
    /** Below this battery % (not charging): half the trials, no background re-evaluation. */
    val batterySaverPct: Int = 20,
    val reeval: Reeval = Reeval(),
) {
    data class Reeval(
        val enabled: Boolean = true,
        /** Seconds between quality samples while connected. */
        val intervalS: Int = 60,
        /** Samples kept. */
        val window: Int = 5,
        /** Failed samples in the window that count as "quality dropped". */
        val failThreshold: Int = 3,
        /** Median latency this many times the connect-time baseline counts as dropped… */
        val latencyFactor: Double = 3.0,
        /** …but only above this absolute floor. */
        val latencyFloorMs: Int = 1_500,
        /** Minimum gap between two re-evaluations. */
        val cooldownS: Int = 600,
        val maxPerHour: Int = 3,
    )

    /** Trials allowed right now, halved on low battery. */
    fun trialsFor(lowBattery: Boolean): Int = if (lowBattery) (maxTrials / 2).coerceAtLeast(2) else maxTrials

    companion object {
        fun parse(json: JSONObject?): SmartConfig {
            if (json == null) return SmartConfig()
            val d = SmartConfig()
            val r = json.optJSONObject("reeval")
            val dr = Reeval()
            return SmartConfig(
                enabled = json.optBoolean("enabled", d.enabled),
                totalBudgetS = json.optInt("total_budget_s", d.totalBudgetS).coerceIn(30, 900),
                maxTrials = json.optInt("max_trials", d.maxTrials).coerceIn(1, 20),
                minTrials = json.optInt("min_trials", d.minTrials).coerceIn(1, 20),
                goodEnough = json.optInt("good_enough", d.goodEnough).coerceIn(1, 100),
                minScore = json.optInt("min_score", d.minScore).coerceIn(0, 99),
                parallelProbes = json.optInt("parallel_probes", d.parallelProbes).coerceIn(1, 16),
                stabilityProbes = json.optInt("stability_probes", d.stabilityProbes).coerceIn(1, 5),
                downloadBytes = json.optInt("download_bytes", d.downloadBytes).coerceIn(0, 4_000_000),
                memoryTtlDays = json.optInt("memory_ttl_days", d.memoryTtlDays).coerceIn(1, 90),
                batterySaverPct = json.optInt("battery_saver_pct", d.batterySaverPct).coerceIn(0, 100),
                reeval = if (r == null) dr else Reeval(
                    enabled = r.optBoolean("enabled", dr.enabled),
                    intervalS = r.optInt("interval_s", dr.intervalS).coerceIn(30, 3_600),
                    window = r.optInt("window", dr.window).coerceIn(2, 20),
                    failThreshold = r.optInt("fail_threshold", dr.failThreshold).coerceIn(1, 20),
                    latencyFactor = r.optDouble("latency_factor", dr.latencyFactor).let { if (it.isNaN()) dr.latencyFactor else it }.coerceIn(1.5, 20.0),
                    latencyFloorMs = r.optInt("latency_floor_ms", dr.latencyFloorMs).coerceIn(200, 20_000),
                    cooldownS = r.optInt("cooldown_s", dr.cooldownS).coerceIn(60, 86_400),
                    maxPerHour = r.optInt("max_per_hour", dr.maxPerHour).coerceIn(0, 12),
                ),
            ).let { if (it.minTrials > it.maxTrials) it.copy(minTrials = it.maxTrials) else it }
        }
    }
}

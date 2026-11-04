package com.molido.vpn.engine

import kotlin.math.ceil
import kotlin.math.ln

/** One ordered probe observation. Failed probes must be retained to measure loss. */
data class GamingProbe(
    val success: Boolean,
    val rttMs: Long? = null,
)

/** A short, time-ordered sample window for comparing candidate game routes. */
data class GamingMeasurement(
    val probes: List<GamingProbe>,
    /** Time to restore a usable route after a detected failure, when measured. */
    val recoveryMs: Long? = null,
)

/** Game-specific quality score. It ranks routes; it does not claim to measure in-game ping. */
object GamingQuality {
    private const val LOSS_WEIGHT = 0.30
    private const val P95_WEIGHT = 0.25
    private const val JITTER_WEIGHT = 0.20
    private const val STABILITY_WEIGHT = 0.15
    private const val RECOVERY_WEIGHT = 0.10

    private fun clamp01(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Nearest-rank p95, preserving a conservative tail-latency signal. */
    fun p95RttMs(probes: List<GamingProbe>): Long? {
        val values = probes.filter { it.success }
            .mapNotNull { it.rttMs?.takeIf { rtt -> rtt > 0 } }
            .sorted()
        if (values.isEmpty()) return null
        val index = (ceil(values.size * 0.95).toInt() - 1).coerceIn(0, values.lastIndex)
        return values[index]
    }

    /** Median absolute change between consecutive valid successful RTT observations. */
    fun jitterMs(probes: List<GamingProbe>): Long? {
        val values = probes.filter { it.success }
            .mapNotNull { it.rttMs?.takeIf { rtt -> rtt > 0 } }
        if (values.size < 2) return null
        return median(values.zipWithNext { a, b -> kotlin.math.abs(b - a) })
    }

    private fun lossScore(lossPercent: Double): Double =
        clamp01(1.0 - lossPercent / 5.0)

    private fun latencyScore(p95Ms: Long?): Double {
        if (p95Ms == null) return 0.5
        if (p95Ms <= 40) return 1.0
        if (p95Ms >= 250) return 0.0
        return clamp01(1.0 - ln(p95Ms / 40.0) / ln(250.0 / 40.0))
    }

    private fun jitterScore(jitterMs: Long?): Double {
        if (jitterMs == null) return 0.5
        if (jitterMs <= 5) return 1.0
        return clamp01(1.0 - (jitterMs - 5.0) / 45.0)
    }

    private fun recoveryScore(recoveryMs: Long?): Double {
        if (recoveryMs == null || recoveryMs <= 0) return 0.5
        if (recoveryMs <= 1_000) return 1.0
        if (recoveryMs >= 15_000) return 0.0
        return clamp01(1.0 - ln(recoveryMs / 1_000.0) / ln(15.0))
    }

    /** Returns a deterministic 0..100 route-ranking score; all-failed windows score zero. */
    fun score(measurement: GamingMeasurement): Double {
        val probes = measurement.probes
        if (probes.isEmpty()) return 0.0
        val successful = probes.count { it.success }
        if (successful == 0) return 0.0

        val lossPercent = (probes.size - successful).toDouble() * 100.0 / probes.size
        val stability = successful.toDouble() / probes.size
        val raw = LOSS_WEIGHT * lossScore(lossPercent) +
            P95_WEIGHT * latencyScore(p95RttMs(probes)) +
            JITTER_WEIGHT * jitterScore(jitterMs(probes)) +
            STABILITY_WEIGHT * stability +
            RECOVERY_WEIGHT * recoveryScore(measurement.recoveryMs)
        return clamp01(raw) * 100.0
    }
}

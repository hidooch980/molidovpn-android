package com.molido.vpn.engine

import kotlin.math.ln

/**
 * One measurement of a method: did it carry traffic, how long a round trip took,
 * and (when the method exposes a local proxy) how fast a small download went.
 */
data class Probe(
    val ok: Boolean,
    val latencyMs: Long? = null,
    val throughputKbps: Long? = null,
    /** Address family that answered fastest ("v4"/"v6"), when the probe tried both. */
    val family: String? = null,
)

/** Everything measured for one method in one Smart Connect run. */
data class Measured(
    val methodId: String,
    val probes: List<Probe>,
    /** Time from "start" to "tunnel up"; null when it never came up. */
    val connectMs: Long? = null,
    /** Exit is inside Iran: works for domestic sites only, ranked below any foreign exit. */
    val iranExit: Boolean = false,
)

/** Relative weight of each quality; they need not sum to 1 (normalised). */
data class Weights(
    val stability: Double = 0.45,
    val latency: Double = 0.15,
    val throughput: Double = 0.30,
    val connect: Double = 0.10,
)

/**
 * Turns a [Measured] into a finite 0..100 score. Pure and deterministic so it is unit tested.
 *
 * Invalid or missing measurements are neutral rather than rewarded. Invalid weights are ignored;
 * an all-zero/invalid weight set falls back to the documented defaults.
 *
 *  - **stability**: share of probes that succeeded.
 *  - **latency**: median of positive successful round trips, log scale.
 *  - **throughput**: median of positive successful transfer rates, log scale.
 *  - **connect**: time to come up, log scale.
 *
 * No successful probe at all → 0, whatever else was measured.
 */
object SmartScore {

    const val IRAN_EXIT_FACTOR = 0.5
    const val MIN_VIDEO_KBPS = 3_000L
    const val SLOW_FACTOR = 0.6

    private fun clamp01(v: Double): Double =
        if (v.isFinite()) v.coerceIn(0.0, 1.0) else 0.0

    private fun safeWeight(v: Double): Double =
        if (v.isFinite() && v > 0.0) v.coerceAtMost(1_000.0) else 0.0

    fun latencyScore(ms: Long?): Double {
        if (ms == null || ms <= 0) return 0.5
        if (ms <= 100) return 1.0
        return clamp01(1.0 - ln(ms / 100.0) / ln(30.0))
    }

    fun throughputScore(kbps: Long?): Double {
        if (kbps == null || kbps < 0) return 0.5
        if (kbps <= 50) return 0.0
        return clamp01(ln(kbps / 50.0) / ln(200.0))
    }

    fun connectScore(ms: Long?): Double {
        if (ms == null || ms < 0) return 0.5
        if (ms <= 2_000) return 1.0
        return clamp01(1.0 - ln(ms / 2_000.0) / ln(45.0))
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun score(m: Measured, w: Weights = Weights()): Double {
        val okProbes = m.probes.filter { it.ok }
        if (okProbes.isEmpty()) return 0.0

        val positiveWeights = Weights(
            stability = safeWeight(w.stability),
            latency = safeWeight(w.latency),
            throughput = safeWeight(w.throughput),
            connect = safeWeight(w.connect),
        )
        val total = positiveWeights.stability + positiveWeights.latency +
            positiveWeights.throughput + positiveWeights.connect
        val effective = if (total.isFinite() && total > 0.0) positiveWeights else Weights()
        val effectiveTotal = effective.stability + effective.latency +
            effective.throughput + effective.connect

        val stability = okProbes.size.toDouble() / m.probes.size
        val lat = median(okProbes.mapNotNull { it.latencyMs?.takeIf { value -> value > 0 } })
        val tput = median(okProbes.mapNotNull { it.throughputKbps?.takeIf { value -> value > 0 } })
        val raw = (
            effective.stability * stability +
                effective.latency * latencyScore(lat) +
                effective.throughput * throughputScore(tput) +
                effective.connect * connectScore(m.connectMs)
            ) / effectiveTotal

        var result = 100.0 * clamp01(raw)
        if (tput != null && tput < MIN_VIDEO_KBPS) result *= SLOW_FACTOR
        if (stability < 1.0) result *= stability
        if (m.iranExit) result *= IRAN_EXIT_FACTOR
        return clamp01(result / 100.0) * 100.0
    }

    /** Short human reason for the UI, e.g. "84 ms · 3/3 stable · 6.2 Mbit/s". */
    fun reason(m: Measured, stableWord: String = "stable", irWord: String = "IR exit"): String {
        val ok = m.probes.count { it.ok }
        val parts = ArrayList<String>()
        median(m.probes.filter { it.ok }.mapNotNull { it.latencyMs?.takeIf { value -> value > 0 } })?.let {
            parts += "$it ms"
        }
        parts += "$ok/${m.probes.size} $stableWord"
        median(m.probes.filter { it.ok }.mapNotNull { it.throughputKbps?.takeIf { value -> value > 0 } })?.let {
            parts += if (it >= 1000) String.format(java.util.Locale.US, "%.1f Mbit/s", it / 1000.0) else "$it kbit/s"
        }
        if (m.iranExit) parts += irWord
        return parts.joinToString(" · ")
    }
}

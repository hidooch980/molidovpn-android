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
    val stability: Double = 0.35,
    val latency: Double = 0.15,
    val throughput: Double = 0.40,
    val connect: Double = 0.10,
)

/**
 * Turns a [Measured] into a 0..100 score. Pure and deterministic so it is unit tested.
 *
 *  - **stability**: share of probes that succeeded (the 2–3 repeated checks).
 *  - **latency**: median of the successful round trips, log scale; 100 ms = 1.0, 3 s = 0.
 *  - **throughput**: log scale; 50 kbit/s = 0, 10 Mbit/s = 1. Unknown counts as neutral
 *    (0.5) so a method that cannot be speed-tested is neither rewarded nor punished.
 *  - **connect**: time to come up, log scale; 2 s = 1.0, 90 s = 0.
 *
 * No successful probe at all → 0, whatever else was measured.
 */
object SmartScore {

    const val IRAN_EXIT_FACTOR = 0.5

    /** Below this measured speed video stalls: the score is cut so the race keeps looking. */
    const val MIN_VIDEO_KBPS = 3_000L
    const val SLOW_FACTOR = 0.6

    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

    fun latencyScore(ms: Long?): Double {
        if (ms == null) return 0.5
        if (ms <= 100) return 1.0
        return clamp01(1.0 - ln(ms / 100.0) / ln(30.0))
    }

    fun throughputScore(kbps: Long?): Double {
        if (kbps == null) return 0.5
        if (kbps <= 50) return 0.0
        return clamp01(ln(kbps / 50.0) / ln(200.0))
    }

    fun connectScore(ms: Long?): Double {
        if (ms == null) return 0.5
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
        val stability = okProbes.size.toDouble() / m.probes.size
        val lat = median(okProbes.mapNotNull { it.latencyMs })
        val tput = median(okProbes.mapNotNull { it.throughputKbps })
        val total = w.stability + w.latency + w.throughput + w.connect
        val raw = (w.stability * stability +
            w.latency * latencyScore(lat) +
            w.throughput * throughputScore(tput) +
            w.connect * connectScore(m.connectMs)) / total
        var s = 100.0 * raw
        if (tput != null && tput < MIN_VIDEO_KBPS) s *= SLOW_FACTOR
        // A route that dropped checks stalls mid-video however fast it was once.
        if (stability < 1.0) s *= stability
        return if (m.iranExit) s * IRAN_EXIT_FACTOR else s
    }

    /** Short human reason for the UI, e.g. "84 ms · 3/3 stable · 6.2 Mbit/s". */
    fun reason(m: Measured, stableWord: String = "stable", irWord: String = "IR exit"): String {
        val ok = m.probes.count { it.ok }
        val parts = ArrayList<String>()
        median(m.probes.filter { it.ok }.mapNotNull { it.latencyMs })?.let { parts += "$it ms" }
        parts += "$ok/${m.probes.size} $stableWord"
        median(m.probes.filter { it.ok }.mapNotNull { it.throughputKbps })?.let {
            parts += if (it >= 1000) String.format(java.util.Locale.US, "%.1f Mbit/s", it / 1000.0) else "$it kbit/s"
        }
        if (m.iranExit) parts += irWord
        return parts.joinToString(" · ")
    }
}

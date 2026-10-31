package com.molido.vpn.engine

/**
 * Video-stall heuristic over the tunnel's byte counters, sampled every few seconds while the
 * screen is on. Pure (time is passed in), so it is unit tested.
 *
 * A video session is "active" when the last [activeWindowMs] saw at least one sample at or above
 * [videoKbps] (players fetch segments in bursts). A sample "stalls" when such a session keeps
 * sending requests (upstream bytes) but almost nothing comes back. A full buffer is not a stall:
 * the player then sends nothing either. [stallMs] of consecutive stalled samples → [Verdict.STALL];
 * a session that stays active but whose median rate sits under [lowKbps] for [lowMs] → [Verdict.SLOW].
 */
class StallDetector(
    private val videoKbps: Long = 1_500,
    private val stallKbps: Long = 120,
    private val minTxBytes: Long = 1_500,
    private val stallMs: Long = 30_000,
    private val lowKbps: Long = 700,
    private val lowMs: Long = 60_000,
    private val activeWindowMs: Long = 60_000,
) {
    enum class Verdict { OK, STALL, SLOW }

    private data class Sample(val at: Long, val kbps: Long, val tx: Long)

    private val samples = ArrayDeque<Sample>()
    private var lastBurstAt = Long.MIN_VALUE / 2
    private var stallSince = -1L

    /** [rx]/[tx]: bytes since the previous sample, over [dtMs]. */
    fun add(rx: Long, tx: Long, dtMs: Long, now: Long): Verdict {
        if (dtMs <= 0) return Verdict.OK
        val kbps = rx * 8 / dtMs // bits per ms == kbit/s
        samples.addLast(Sample(now, kbps, tx))
        while (samples.isNotEmpty() && now - samples.first().at > maxOf(lowMs, activeWindowMs)) samples.removeFirst()
        if (kbps >= videoKbps) lastBurstAt = now
        val active = now - lastBurstAt <= activeWindowMs
        if (!active) {
            stallSince = -1
            return Verdict.OK
        }
        val stalled = kbps < stallKbps && tx >= minTxBytes
        if (stalled) {
            if (stallSince < 0) stallSince = now - dtMs
            if (now - stallSince >= stallMs) {
                reset()
                return Verdict.STALL
            }
        } else {
            stallSince = -1
        }
        val recent = samples.filter { now - it.at <= lowMs && it.tx >= minTxBytes }
        val span = if (recent.isEmpty()) 0L else now - recent.first().at
        if (span >= lowMs - dtMs && recent.size >= 4) {
            val med = SmartScore.median(recent.map { it.kbps }) ?: Long.MAX_VALUE
            if (med < lowKbps) {
                reset()
                return Verdict.SLOW
            }
        }
        return Verdict.OK
    }

    fun reset() {
        samples.clear()
        stallSince = -1
        lastBurstAt = Long.MIN_VALUE / 2
    }
}

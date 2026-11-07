package com.molido.vpn.engine

/**
 * Anti-flap rules for switching routes while connected. Pure, so it is unit tested.
 *
 *  - **Dwell**: no switch within [minDwellMs] of the last one (or of connecting).
 *  - **Sustained**: the problem must have lasted [sustainMs] (the stall detector and the ping
 *    window already measure over time; this is the floor).
 *  - **Clearly better**: a candidate replaces the current route only when it is at least
 *    [margin] (25 %) better than what the current route measures right now.
 */
data class SwitchPolicy(
    val minDwellMs: Long = 5 * 60_000L,
    val sustainMs: Long = 30_000L,
    val margin: Double = 0.25,
) {
    fun dwellOver(lastSwitchAt: Long, now: Long): Boolean =
        minDwellMs >= 0 && now >= lastSwitchAt && now - lastSwitchAt >= minDwellMs

    fun sustained(problemSince: Long, now: Long): Boolean =
        problemSince >= 0 && now >= problemSince && now - problemSince >= sustainMs.coerceAtLeast(0)

    /** [current] null = the current route failed the measurement: any working candidate is better. */
    fun clearlyBetter(candidate: Double, current: Double?): Boolean =
        candidate.isFinite() && candidate > 0 &&
            (current == null || (current.isFinite() && (current <= 0 || candidate >= current * (1 + margin.coerceAtLeast(0.0)))))
}

/**
 * Evidence contract for a live route change. This is deliberately separate from [SwitchPolicy]:
 * callers must pass measurements from the candidate trial and the current live route, not a
 * catalog score or stale cached observation.
 *
 * A health-failure override bypasses dwell only; it never bypasses candidate evidence or the
 * minimum quality improvement. Scores are bounded to 0..100 and improvement is absolute points.
 */
data class SwitchEvidencePolicy(
    val minimumProbes: Int = 3,
    val minimumHealthyProbes: Int = 2,
    val minimumScoreGain: Double = 8.0,
    val healthFailuresForOverride: Int = 2,
) {
    fun hasCandidateEvidence(healthyProbes: Int, totalProbes: Int): Boolean =
        minimumProbes > 0 &&
            totalProbes >= minimumProbes &&
            healthyProbes >= minimumHealthyProbes.coerceIn(1, minimumProbes) &&
            healthyProbes <= totalProbes

    fun qualityImproved(candidateScore: Double, currentScore: Double?): Boolean {
        if (!candidateScore.isFinite() || candidateScore !in 0.0..100.0) return false
        if (currentScore == null) return candidateScore > 0.0
        if (!currentScore.isFinite() || currentScore !in 0.0..100.0) return false
        return candidateScore - currentScore >= minimumScoreGain.coerceAtLeast(0.0)
    }

    fun shouldSwitch(
        candidateScore: Double,
        currentScore: Double?,
        healthyProbes: Int,
        totalProbes: Int,
        consecutiveHealthFailures: Int,
        dwellElapsed: Boolean,
    ): Boolean =
        hasCandidateEvidence(healthyProbes, totalProbes) &&
            qualityImproved(candidateScore, currentScore) &&
            (dwellElapsed || consecutiveHealthFailures >= healthFailuresForOverride.coerceAtLeast(1))
}

/**
 * Stability metrics of a session: drops (the tunnel had to be rebuilt) and seamless switches per
 * connected hour. Kept in memory per session and logged; the numbers the owner asked to watch.
 */
class StabilityStats {
    var connectedMs = 0L
        private set
    var drops = 0
        private set
    var switches = 0
        private set
    private var upSince = -1L

    fun up(now: Long) { if (upSince < 0) upSince = now }
    fun down(now: Long) { if (upSince >= 0) { connectedMs += now - upSince; upSince = -1 } }
    fun drop(now: Long) { drops++; down(now) }
    fun switched() { switches++ }

    fun hours(now: Long): Double = (connectedMs + if (upSince >= 0) now - upSince else 0L) / 3_600_000.0

    fun dropsPerHour(now: Long): Double = hours(now).let { if (it < 1.0 / 60) 0.0 else drops / it }

    fun summary(now: Long): String = String.format(
        java.util.Locale.US, "%.2f h connected, %d drops (%.2f/h), %d seamless switches", hours(now), drops, dropsPerHour(now), switches,
    )
}

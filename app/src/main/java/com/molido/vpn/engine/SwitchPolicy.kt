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
    fun dwellOver(lastSwitchAt: Long, now: Long): Boolean = now - lastSwitchAt >= minDwellMs

    fun sustained(problemSince: Long, now: Long): Boolean = problemSince >= 0 && now - problemSince >= sustainMs

    /** [current] null = the current route failed the measurement: any working candidate is better. */
    fun clearlyBetter(candidate: Double, current: Double?): Boolean =
        candidate > 0 && (current == null || current <= 0 || candidate >= current * (1 + margin))
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

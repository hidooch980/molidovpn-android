package com.molido.vpn.engine

/**
 * Watches a connected tunnel and says when quality dropped enough to re-run Smart Connect.
 * Fed one [Probe] per interval. Pure (time is passed in), so it is unit tested.
 */
class QualityMonitor(private val cfg: SmartConfig.Reeval, private val baselineMs: Long?) {

    enum class Verdict { OK, DEGRADED, COOLDOWN }

    private val window = ArrayDeque<Probe>()
    private val fired = ArrayDeque<Long>()
    private var lastFiredAt = Long.MIN_VALUE / 2

    fun add(p: Probe, now: Long): Verdict {
        window.addLast(p)
        while (window.size > cfg.window) window.removeFirst()
        if (!cfg.enabled || window.size < cfg.window.coerceAtMost(cfg.failThreshold)) return Verdict.OK
        val fails = window.count { !it.ok }
        val med = SmartScore.median(window.filter { it.ok }.mapNotNull { it.latencyMs })
        val slow = med != null && baselineMs != null && baselineMs > 0 &&
            med > baselineMs * cfg.latencyFactor && med > cfg.latencyFloorMs && window.size >= cfg.window
        if (fails < cfg.failThreshold && !slow) return Verdict.OK
        while (fired.isNotEmpty() && now - fired.first() > 3_600_000L) fired.removeFirst()
        if (now - lastFiredAt < cfg.cooldownS * 1_000L || fired.size >= cfg.maxPerHour) return Verdict.COOLDOWN
        lastFiredAt = now
        fired.addLast(now)
        window.clear()
        return Verdict.DEGRADED
    }

    /** An outside signal (video stall) says quality dropped: same cooldown and hourly cap. */
    fun force(now: Long): Verdict {
        if (!cfg.enabled) return Verdict.OK
        while (fired.isNotEmpty() && now - fired.first() > 3_600_000L) fired.removeFirst()
        if (now - lastFiredAt < cfg.cooldownS * 1_000L || fired.size >= cfg.maxPerHour) return Verdict.COOLDOWN
        lastFiredAt = now
        fired.addLast(now)
        window.clear()
        return Verdict.DEGRADED
    }
}

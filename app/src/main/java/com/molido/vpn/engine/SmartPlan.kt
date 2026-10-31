package com.molido.vpn.engine

/** How costly a method is to test, which decides when and how often it is tried. */
enum class Cost {
    /** Seconds to test, little data: tried first, probes run in parallel. */
    CHEAP,
    /** Tor bootstraps, Psiphon: tens of seconds, staged after the cheap ones, one at a time. */
    EXPENSIVE,
}

/** A method as Smart Connect sees it. [coreName] is what the service starts. */
data class Candidate(
    val id: String,
    val coreName: String,
    val label: String,
    val cost: Cost,
    val budgetMs: Long,
)

/** The winner and the method kept ready for instant failover. */
data class Pick(val winner: String?, val runnerUp: String?)

/**
 * The race-then-pick rules, pure so they are unit tested:
 *  1. [order]: who is tried, in what order.
 *  2. [shouldStop]: when enough is known (good result, budget or trial cap).
 *  3. [pick]: best score wins, second best is the runner-up.
 */
object SmartPlan {

    /**
     * Remembered winner for this network first, its runner-up second. Then cheap methods by
     * their out-of-tunnel probe (reachable and fast first, unprobed next, unreachable last),
     * then [shared] preference (operator-wide reports, 0..1) as a tie-break, then expensive
     * methods in their given order. A probe failure only demotes — it never removes a method,
     * because an out-of-tunnel TCP probe can fail where the real transport works.
     */
    fun order(
        candidates: List<Candidate>,
        memory: NetworkMemory.Entry? = null,
        preprobe: Map<String, Probe> = emptyMap(),
        shared: Map<String, Double> = emptyMap(),
        /** Cheap methods to try after the other cheap ones (e.g. WARP family for an Iranian user: IR exit). */
        demote: Set<String> = emptySet(),
        /** On-device learned rank for this network and time of day ([MethodBandit.rank]); higher first. */
        learned: Map<String, Double> = emptyMap(),
    ): List<Candidate> {
        fun probeRank(c: Candidate): Int = when (val p = preprobe[c.id]) {
            null -> 1
            else -> if (p.ok) 0 else 2
        }
        val pinned = listOfNotNull(memory?.winner, memory?.runnerUp)
            .mapNotNull { id -> candidates.firstOrNull { it.id == id } }
            .distinct()
        val rest = candidates.filter { it !in pinned }
        val cheap = rest.filter { it.cost == Cost.CHEAP }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<Candidate>> { if (it.value.id in demote) 1 else 0 }
                    .thenBy { probeRank(it.value) }
                    // 0.1-wide bins: clear differences in what won here decide, near-ties fall to latency.
                    .thenByDescending { learned[it.value.id]?.let { v -> (v * 10).toInt() } ?: 0 }
                    .thenBy { preprobe[it.value.id]?.takeIf { p -> p.ok }?.latencyMs ?: Long.MAX_VALUE }
                    .thenByDescending { shared[it.value.id] ?: 0.0 }
                    .thenBy { it.index },
            ).map { it.value }
        val expensive = rest.filter { it.cost == Cost.EXPENSIVE }
            .withIndex()
            .sortedWith(compareBy<IndexedValue<Candidate>> { probeRank(it.value) }.thenBy { it.index })
            .map { it.value }
        return pinned + cheap + expensive
    }

    fun shouldStop(
        scores: Map<String, Double>,
        trialsDone: Int,
        elapsedMs: Long,
        cfg: SmartConfig,
        lowBattery: Boolean = false,
    ): Boolean {
        if (elapsedMs >= cfg.totalBudgetS * 1_000L) return true
        if (trialsDone >= cfg.trialsFor(lowBattery)) return true
        val best = scores.values.maxOrNull() ?: 0.0
        return best >= cfg.goodEnough && trialsDone >= cfg.minTrials.coerceAtMost(cfg.trialsFor(lowBattery))
    }

    /** Highest score wins; ties go to the earlier-tried method (insertion order). */
    fun pick(scores: Map<String, Double>, minScore: Int): Pick {
        val ranked = scores.entries.withIndex()
            .filter { it.value.value >= minScore && it.value.value > 0.0 }
            .sortedWith(compareByDescending<IndexedValue<Map.Entry<String, Double>>> { it.value.value }.thenBy { it.index })
            .map { it.value.key }
        return Pick(ranked.getOrNull(0), ranked.getOrNull(1))
    }
}

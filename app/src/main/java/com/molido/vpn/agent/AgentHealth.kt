package com.molido.vpn.agent

/** Stable health states for the local Agent diagnostics surface. */
enum class AgentHealthState {
    HEALTHY,
    DEGRADED,
    ERROR,
    NOT_RUN,
    DISABLED,
    CLOCK_SKEW,
}

/** A privacy-safe summary; it contains skill ids and timing only, never network payloads. */
data class AgentHealthItem(
    val skillId: String,
    val state: AgentHealthState,
    val ageMs: Long?,
    val detailCode: String?,
)

data class AgentHealthSummary(
    val items: List<AgentHealthItem>,
    val healthy: Int,
    val degraded: Int,
    val errors: Int,
    val notRun: Int,
    val disabled: Int,
) {
    val total: Int get() = items.size
}

/**
 * Converts persisted AgentRuntime snapshots into deterministic health indicators.
 * A provider call returning is treated as a heartbeat, not proof that remote data changed.
 */
object AgentHealth {
    const val DEFAULT_STALE_AFTER_MS = 30 * 60 * 1000L

    fun summarize(
        statuses: List<AgentSkillStatus>,
        now: Long,
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    ): AgentHealthSummary {
        val items = statuses.map { status ->
            val lastSignal = maxOf(status.lastCallReturnedAt, status.lastErrorAt)
            val age = lastSignal.takeIf { it > 0L }?.let { now - it }
            val (state, detail) = when {
                status.blockedReason == "agent_disabled" ->
                    AgentHealthState.DISABLED to "agent_disabled"
                status.lastErrorAt > status.lastCallReturnedAt && status.lastErrorAt > 0L ->
                    AgentHealthState.ERROR to status.lastError
                status.lastCallReturnedAt <= 0L && status.lastErrorAt <= 0L && status.dispatchCount == 0L ->
                    AgentHealthState.NOT_RUN to status.blockedReason
                lastSignal > now ->
                    AgentHealthState.CLOCK_SKEW to "future_timestamp"
                status.lastCallReturnedAt <= 0L && status.lastErrorAt > 0L ->
                    AgentHealthState.ERROR to status.lastError
                status.lastCallReturnedAt <= 0L ->
                    AgentHealthState.DEGRADED to "no_provider_return"
                age != null && age > staleAfterMs ->
                    AgentHealthState.DEGRADED to "stale"
                else ->
                    AgentHealthState.HEALTHY to null
            }
            AgentHealthItem(status.id, state, age?.takeIf { it >= 0L }, detail)
        }
        return AgentHealthSummary(
            items = items,
            healthy = items.count { it.state == AgentHealthState.HEALTHY },
            degraded = items.count { it.state == AgentHealthState.DEGRADED || it.state == AgentHealthState.CLOCK_SKEW },
            errors = items.count { it.state == AgentHealthState.ERROR },
            notRun = items.count { it.state == AgentHealthState.NOT_RUN },
            disabled = items.count { it.state == AgentHealthState.DISABLED },
        )
    }
}

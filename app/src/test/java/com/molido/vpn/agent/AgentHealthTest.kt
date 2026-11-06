package com.molido.vpn.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentHealthTest {
    private fun status(
        id: String = "server-discovery",
        dispatches: Long = 1,
        returned: Long = 90_000,
        errorAt: Long = 0,
        error: String? = null,
        blocked: String? = "not_due",
    ) = AgentSkillStatus(
        id = id,
        mode = AgentSkillMode.AUTOMATIC,
        dispatchCount = dispatches,
        lastDispatchAt = 90_000,
        lastCallReturnedAt = returned,
        lastErrorAt = errorAt,
        lastError = error,
        blockedReason = blocked,
    )

    @Test fun recentProviderReturnIsHealthyEvenWhenNextRunIsNotDue() {
        val item = AgentHealth.summarize(listOf(status()), 100_000).items.single()
        assertEquals(AgentHealthState.HEALTHY, item.state)
        assertNull(item.detailCode)
    }

    @Test fun newerErrorOverridesEarlierSuccessfulReturn() {
        val item = AgentHealth.summarize(
            listOf(status(returned = 80_000, errorAt = 95_000, error = "IOException")),
            100_000,
        ).items.single()
        assertEquals(AgentHealthState.ERROR, item.state)
        assertEquals("IOException", item.detailCode)
    }

    @Test fun oldProviderReturnIsDegraded() {
        val item = AgentHealth.summarize(listOf(status(returned = 1_000)), 2_000_000).items.single()
        assertEquals(AgentHealthState.DEGRADED, item.state)
        assertEquals("stale", item.detailCode)
    }

    @Test fun neverDispatchedIsNotRun() {
        val item = AgentHealth.summarize(
            listOf(status(dispatches = 0, returned = 0, blocked = "offline")),
            100_000,
        ).items.single()
        assertEquals(AgentHealthState.NOT_RUN, item.state)
    }

    @Test fun disabledAgentIsReportedSeparately() {
        val item = AgentHealth.summarize(
            listOf(status(returned = 0, blocked = "agent_disabled")),
            100_000,
        ).items.single()
        assertEquals(AgentHealthState.DISABLED, item.state)
    }

    @Test fun futureTimestampIsClockSkewNotHealthy() {
        val item = AgentHealth.summarize(listOf(status(returned = 110_000)), 100_000).items.single()
        assertEquals(AgentHealthState.CLOCK_SKEW, item.state)
    }
}

package com.molido.vpn.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGuardianPolicyTest {
    private fun snapshot(
        state: GuardianTunnelState = GuardianTunnelState.DISCONNECTED,
        serviceScheduled: Boolean = false,
        inFlight: Boolean = false,
        attempts: Int = 0,
        lastRequestAt: Long = 0L,
        now: Long = 100_000L,
    ) = GuardianSnapshot(state, serviceScheduled, inFlight, attempts, lastRequestAt, now)

    @Test fun connectedTunnelIsNeverDisturbed() {
        assertEquals(
            GuardianDecision.NO_ACTION,
            AgentGuardianPolicy.decide(snapshot(state = GuardianTunnelState.CONNECTED)),
        )
    }

    @Test fun explicitUserStopAlwaysWins() {
        assertEquals(
            GuardianDecision.NO_ACTION,
            AgentGuardianPolicy.decide(snapshot(state = GuardianTunnelState.USER_STOPPED)),
        )
    }

    @Test fun existingServiceRecoveryPreventsDuplicateAgentRequest() {
        assertEquals(
            GuardianDecision.WAIT_FOR_SERVICE,
            AgentGuardianPolicy.decide(snapshot(serviceScheduled = true)),
        )
    }

    @Test fun inFlightRequestIsSingleFlight() {
        assertEquals(
            GuardianDecision.WAIT_FOR_SERVICE,
            AgentGuardianPolicy.decide(snapshot(inFlight = true)),
        )
    }

    @Test fun connectingOrUnknownStateDoesNotTriggerRecovery() {
        assertEquals(
            GuardianDecision.WAIT_FOR_SERVICE,
            AgentGuardianPolicy.decide(snapshot(state = GuardianTunnelState.CONNECTING)),
        )
        assertEquals(
            GuardianDecision.WAIT_FOR_SERVICE,
            AgentGuardianPolicy.decide(snapshot(state = GuardianTunnelState.UNKNOWN)),
        )
    }

    @Test fun firstDisconnectedObservationCanDelegateToService() {
        assertEquals(
            GuardianDecision.REQUEST_SERVICE_RECOVERY,
            AgentGuardianPolicy.decide(snapshot()),
        )
    }

    @Test fun cooldownPreventsRapidRepeatedRequests() {
        assertEquals(
            GuardianDecision.WAIT_FOR_SERVICE,
            AgentGuardianPolicy.decide(snapshot(lastRequestAt = 80_001L, now = 100_000L)),
        )
    }

    @Test fun exhaustedBudgetEscalatesInsteadOfRetryingForever() {
        assertEquals(
            GuardianDecision.ESCALATE_TO_USER,
            AgentGuardianPolicy.decide(snapshot(attempts = AgentGuardianPolicy.DEFAULT_MAX_ATTEMPTS)),
        )
    }

    @Test fun futureTimestampFailsClosed() {
        assertEquals(
            GuardianDecision.WAIT_FOR_SERVICE,
            AgentGuardianPolicy.decide(snapshot(lastRequestAt = 110_000L, now = 100_000L)),
        )
    }
}

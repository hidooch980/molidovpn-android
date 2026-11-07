package com.molido.vpn.agent

/**
 * Read-only inputs for the Agent's recovery supervisor.
 *
 * The snapshot must be supplied by the VPN service owner. The Agent never
 * infers tunnel state from catalog heartbeats or starts a VPN service itself.
 */
enum class GuardianTunnelState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    USER_STOPPED,
    UNKNOWN,
}

enum class GuardianDecision {
    NO_ACTION,
    WAIT_FOR_SERVICE,
    REQUEST_SERVICE_RECOVERY,
    ESCALATE_TO_USER,
}

data class GuardianSnapshot(
    val tunnelState: GuardianTunnelState,
    val serviceRecoveryScheduled: Boolean,
    val recoveryRequestInFlight: Boolean,
    val recoveryAttempts: Int,
    val lastRecoveryRequestAtMs: Long,
    val nowMs: Long,
)

/**
 * Pure, deterministic policy for deciding whether the Agent may ask the
 * existing VPN service to recover. It does not start/stop tunnels or perform
 * network operations. The service remains the sole lifecycle authority.
 */
object AgentGuardianPolicy {
    const val DEFAULT_COOLDOWN_MS = 30_000L
    const val DEFAULT_MAX_ATTEMPTS = 3

    fun decide(
        snapshot: GuardianSnapshot,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): GuardianDecision {
        require(cooldownMs >= 0L) { "cooldown must not be negative" }
        require(maxAttempts >= 0) { "maxAttempts must not be negative" }

        return when (snapshot.tunnelState) {
            GuardianTunnelState.CONNECTED,
            GuardianTunnelState.USER_STOPPED -> GuardianDecision.NO_ACTION

            GuardianTunnelState.CONNECTING,
            GuardianTunnelState.UNKNOWN -> GuardianDecision.WAIT_FOR_SERVICE

            GuardianTunnelState.DISCONNECTED -> when {
                snapshot.serviceRecoveryScheduled -> GuardianDecision.WAIT_FOR_SERVICE
                snapshot.recoveryRequestInFlight -> GuardianDecision.WAIT_FOR_SERVICE
                snapshot.recoveryAttempts >= maxAttempts -> GuardianDecision.ESCALATE_TO_USER
                snapshot.lastRecoveryRequestAtMs > snapshot.nowMs -> GuardianDecision.WAIT_FOR_SERVICE
                snapshot.lastRecoveryRequestAtMs > 0L &&
                    snapshot.nowMs - snapshot.lastRecoveryRequestAtMs < cooldownMs ->
                    GuardianDecision.WAIT_FOR_SERVICE
                else -> GuardianDecision.REQUEST_SERVICE_RECOVERY
            }
        }
    }
}

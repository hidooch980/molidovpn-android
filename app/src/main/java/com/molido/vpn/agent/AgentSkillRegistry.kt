package com.molido.vpn.agent

/**
 * Built-in Molido agent skills. Automatic skills are coordinated by AgentRuntime;
 * delegated skills are owned by the existing Smart Connect and JobScheduler engines.
 */
enum class AgentSkillMode { AUTOMATIC, ON_DEMAND, BACKGROUND }

data class AgentSkill(
    val id: String,
    val title: String,
    val description: String,
    val mode: AgentSkillMode,
    val intervalMs: Long = 0L,
    val requiresInternet: Boolean = true,
    val allowMetered: Boolean = true,
    val requiresBatteryNotLow: Boolean = false,
    val requiresTunnelIdle: Boolean = false,
)

data class AgentEnvironment(
    val online: Boolean,
    val metered: Boolean,
    val batteryNotLow: Boolean,
    val tunnelIdle: Boolean,
)

data class AgentCheckpoint(val lastDispatchAt: Long = 0L)

object AgentSkillRegistry {
    const val FIVE_MINUTES_MS = 5 * 60 * 1000L

    val all: List<AgentSkill> = listOf(
        AgentSkill(
            id = "server-discovery",
            title = "Server discovery",
            description = "Collect, merge and refresh supported server catalogs.",
            mode = AgentSkillMode.AUTOMATIC,
            intervalMs = FIVE_MINUTES_MS,
        ),
        AgentSkill(
            id = "policy-manager",
            title = "Network policy manager",
            description = "Refresh routing, resilience and edge policy from trusted sources.",
            mode = AgentSkillMode.AUTOMATIC,
            intervalMs = FIVE_MINUTES_MS,
        ),
        AgentSkill(
            id = "app-update-manager",
            title = "App update manager",
            description = "Fetch validated remote mode flags and admin announcements.",
            mode = AgentSkillMode.AUTOMATIC,
            intervalMs = FIVE_MINUTES_MS,
        ),
        AgentSkill(
            id = "connection-optimizer",
            title = "Connection optimizer",
            description = "Use Smart Connect measurements and local network learning to rank transports.",
            mode = AgentSkillMode.ON_DEMAND,
        ),
        AgentSkill(
            id = "network-health",
            title = "Network health scanner",
            description = "Probe a bounded set of nodes under the existing battery/network-safe background job.",
            mode = AgentSkillMode.BACKGROUND,
            intervalMs = 60 * 60 * 1000L,
            allowMetered = false,
            requiresBatteryNotLow = true,
            requiresTunnelIdle = true,
        ),
        AgentSkill(
            id = "privacy-guard",
            title = "Privacy guard",
            description = "Keep learning local and limit remote collection to anonymous network health signals.",
            mode = AgentSkillMode.ON_DEMAND,
            requiresInternet = false,
        ),
    )

    fun automaticSkills(): List<AgentSkill> =
        all.filter { it.mode == AgentSkillMode.AUTOMATIC }

    /**
     * Returns a stable, machine-readable reason why a skill cannot be dispatched.
     * Keeping this policy pure makes it testable and lets the UI explain decisions.
     */
    fun blockedReason(
        skill: AgentSkill,
        checkpoint: AgentCheckpoint,
        now: Long,
        environment: AgentEnvironment,
    ): String? {
        if (skill.mode != AgentSkillMode.AUTOMATIC) return "not_automatic"
        if (skill.intervalMs <= 0L) return "invalid_interval"
        if (checkpoint.lastDispatchAt > 0L) {
            // Wall-clock rollback must not turn a persisted checkpoint into an immediate retry.
            if (now < checkpoint.lastDispatchAt) return "clock_rollback"
            if (now - checkpoint.lastDispatchAt < skill.intervalMs) return "not_due"
        }
        if (skill.requiresInternet && !environment.online) return "offline"
        if (!skill.allowMetered && environment.metered) return "metered_network"
        if (skill.requiresBatteryNotLow && !environment.batteryNotLow) return "battery_low"
        if (skill.requiresTunnelIdle && !environment.tunnelIdle) return "tunnel_active"
        return null
    }

    fun isDue(
        skill: AgentSkill,
        checkpoint: AgentCheckpoint,
        now: Long,
        environment: AgentEnvironment,
    ): Boolean = blockedReason(skill, checkpoint, now, environment) == null
}

package com.molido.vpn.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillRegistryTest {
    private val online = AgentEnvironment(
        online = true,
        metered = false,
        batteryNotLow = true,
        tunnelIdle = true,
    )

    @Test
    fun automaticSkillsAreUniqueAndFiveMinuteBounded() {
        val skills = AgentSkillRegistry.automaticSkills()
        assertEquals(skills.size, skills.map { it.id }.toSet().size)
        assertTrue(skills.isNotEmpty())
        assertTrue(skills.all { it.intervalMs == 5 * 60 * 1000L })
    }

    @Test
    fun dueAtFiveMinutesAndNotBefore() {
        val skill = AgentSkillRegistry.automaticSkills().first()
        assertFalse(
            AgentSkillRegistry.isDue(skill, AgentCheckpoint(10_000L), 309_999L, online),
        )
        assertTrue(
            AgentSkillRegistry.isDue(skill, AgentCheckpoint(10_000L), 310_000L, online),
        )
    }

    @Test
    fun networkAndBatteryGatesAreRespected() {
        val skill = AgentSkill(
            id = "gated-test",
            title = "Gated test",
            description = "Exercises the automatic skill safety gates.",
            mode = AgentSkillMode.AUTOMATIC,
            intervalMs = AgentSkillRegistry.FIVE_MINUTES_MS,
            allowMetered = false,
            requiresBatteryNotLow = true,
            requiresTunnelIdle = true,
        )
        val checkpoint = AgentCheckpoint(0L)
        assertFalse(
            AgentSkillRegistry.isDue(skill, checkpoint, 1_000_000L, online.copy(metered = true)),
        )
        assertFalse(
            AgentSkillRegistry.isDue(skill, checkpoint, 1_000_000L, online.copy(batteryNotLow = false)),
        )
        assertFalse(
            AgentSkillRegistry.isDue(skill, checkpoint, 1_000_000L, online.copy(tunnelIdle = false)),
        )
        assertTrue(AgentSkillRegistry.isDue(skill, checkpoint, 1_000_000L, online))
    }

    @Test
    fun offlineNetworkBlocksAutomaticSkills() {
        val skill = AgentSkillRegistry.automaticSkills().first()
        assertFalse(
            AgentSkillRegistry.isDue(
                skill, AgentCheckpoint(), 1_000_000L,
                online.copy(online = false),
            ),
        )
    }
}

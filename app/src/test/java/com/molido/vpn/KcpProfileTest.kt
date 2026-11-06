package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class KcpProfileTest {
    @Test
    fun defaultsMatchCurrentXrayKcpDefaults() {
        val profile = KcpProfile()
        assertEquals(1350, profile.mtu)
        assertEquals(50, profile.tti)
        assertEquals(5, profile.uplinkCapacity)
        assertEquals(20, profile.downlinkCapacity)
        assertEquals(1.0, profile.cwndMultiplier, 0.0)
        assertEquals(2_097_152, profile.maxSendingWindow)
    }

    @Test
    fun parsesCurrentXrayKcpSettingsFromJson() {
        val profile = KcpProfile.fromJson(
            JSONObject()
                .put("mtu", 1300)
                .put("tti", 20)
                .put("uplinkCapacity", 10)
                .put("downlinkCapacity", 50)
                .put("cwndMultiplier", 2.0)
                .put("maxSendingWindow", 1_048_576),
        )!!
        assertEquals(1300, profile.mtu)
        assertEquals(20, profile.tti)
        assertEquals(10, profile.uplinkCapacity)
        assertEquals(50, profile.downlinkCapacity)
        assertEquals(2.0, profile.cwndMultiplier, 0.0)
        assertEquals(1_048_576, profile.maxSendingWindow)
    }

    @Test
    fun rejectsOutOfRangeAndMalformedSettings() {
        assertNull(KcpProfile.fromQuery(mapOf("mtu" to "500")))
        assertNull(KcpProfile.fromQuery(mapOf("tti" to "101")))
        assertNull(KcpProfile.fromQuery(mapOf("uplinkcapacity" to "-1")))
        assertNull(KcpProfile.fromQuery(mapOf("cwndmultiplier" to "0")))
        assertNull(KcpProfile.fromQuery(mapOf("maxsendingwindow" to "100")))
        assertNull(KcpProfile.fromQuery(mapOf("mtu" to "not-a-number")))
    }

    @Test
    fun rejectsRemovedLegacyHeaderAndSeedFields() {
        assertNull(
            KcpProfile.fromJson(
                JSONObject().put("header", JSONObject().put("type", "srtp")),
            ),
        )
        assertNull(KcpProfile.fromJson(JSONObject().put("seed", "legacy-secret")))
    }

    @Test
    fun rejectsNonFiniteAndOversizedJsonValues() {
        assertNull(KcpProfile.fromJson(JSONObject().put("cwndMultiplier", Double.NaN)))
        assertNull(KcpProfile.fromJson(JSONObject().put("cwndMultiplier", Double.POSITIVE_INFINITY)))
        assertNull(KcpProfile.fromJson(JSONObject().put("maxSendingWindow", 16 * 1024 * 1024 + 1)))
        assertNull(KcpProfile.fromJson(JSONObject().put("mtu", "not-a-number")))
    }

    @Test
    fun currentSettingsJsonDoesNotEmitRemovedLegacyFields() {
        val json = KcpProfile().toJson()
        assertTrue(json.has("mtu"))
        assertTrue(json.has("tti"))
        assertTrue(json.has("cwndMultiplier"))
        assertFalse(json.has("header"))
        assertFalse(json.has("seed"))
    }
}

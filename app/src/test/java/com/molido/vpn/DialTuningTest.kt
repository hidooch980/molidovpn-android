package com.molido.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DialTuningTest {
    private fun tls(security: String = "tls") = JSONObject().put("protocol", "vless")
        .put("streamSettings", JSONObject().put("network", "ws").put("security", security))

    @Test fun familyPick() {
        assertEquals("v6", DialTuning.pickFamily(100, 105))
        assertEquals("v4", DialTuning.pickFamily(100, 140))
        assertEquals("v4", DialTuning.pickFamily(100, null))
        assertEquals("v6", DialTuning.pickFamily(null, 90))
        assertNull(DialTuning.pickFamily(null, null))
    }

    @Test fun familyBecomesDomainStrategy() {
        val v6 = DialTuning.apply(tls(), family = "v6", fragment = false)
        assertEquals("UseIPv6v4", v6.getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy"))
        val v4 = DialTuning.apply(tls(), family = "v4", fragment = false)
        assertEquals("UseIPv4", v4.getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy"))
        assertFalse(DialTuning.apply(tls(), family = null, fragment = false).getJSONObject("streamSettings").has("sockopt"))
    }

    @Test fun upstreamHopKeepsItsDialer() {
        val o = tls()
        o.getJSONObject("streamSettings").put("sockopt", JSONObject().put("dialerProxy", "up"))
        assertFalse(DialTuning.apply(o, "v6", false).getJSONObject("streamSettings").getJSONObject("sockopt").has("domainStrategy"))
    }

    @Test fun fragmentOnlyOnTlsNeverReality() {
        assertTrue(DialTuning.apply(tls(), null, true).getJSONObject("streamSettings").has("finalmask"))
        assertFalse(DialTuning.apply(tls("reality"), null, true).getJSONObject("streamSettings").has("finalmask"))
        assertFalse(DialTuning.apply(tls("none"), null, true).getJSONObject("streamSettings").has("finalmask"))
        assertFalse(DialTuning.apply(tls(), null, false).getJSONObject("streamSettings").has("finalmask"))
        val mask = DialTuning.fragmentMask(DialTuning.Fragment())
        assertTrue(FragmentMasks.valid(mask.toString()))
    }

    @Test fun remoteToggles() {
        val c = ResilienceConfig.parse(File("../remote/resilience.json").readText())
        assertTrue(c.dualStack)
        assertEquals(DialTuning.Fragment(), c.v2rayFragment)
        assertNull(ResilienceConfig.parse("""{"v2ray_fragment":{"enabled":false}}""").v2rayFragment)
        assertFalse(ResilienceConfig.parse("""{"dual_stack":false}""").dualStack)
        val bad = ResilienceConfig.parse("""{"v2ray_fragment":{"packets":"x;rm","lengths":["a"]}}""").v2rayFragment!!
        assertEquals(DialTuning.Fragment(), bad)
    }
}

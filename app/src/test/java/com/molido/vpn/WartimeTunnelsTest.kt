package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WartimeTunnelsTest {

    private val wt1 = "webtunnel [2001:db8:1b9a:81a:32f4:f5f1:5bb7:30ae]:443 5842D953EDA0A2CCFC424D88CDA6BBA095E405BF " +
        "url=https://www-11.example-holidays.ch/live-T1I0wFh2nRPqafHb.asp ver=0.0.5"
    private val wt2 = "webtunnel 192.0.2.7:443 F1E12F288D154AB17EB3BC0B58DF212E01EE8422 url=https://download.example.net/G76Io0Gy ver=0.0.3"

    // ---------------------------------------------------------------- WebTunnel lines

    @Test
    fun webtunnelLineAcceptedAndBridgePrefixDropped() {
        assertEquals(wt1, TorWebTunnel.normalize(wt1))
        assertEquals(wt2, TorWebTunnel.normalize("  Bridge $wt2  "))
    }

    @Test
    fun webtunnelLineRejectsJunk() {
        assertNull(TorWebTunnel.normalize("obfs4 1.2.3.4:443 5842D953EDA0A2CCFC424D88CDA6BBA095E405BF cert=x"))
        assertNull(TorWebTunnel.normalize("webtunnel 192.0.2.7:443 NOTAFINGERPRINT url=https://a.example.net/x"))
        assertNull(TorWebTunnel.normalize("webtunnel 192.0.2.7:443 F1E12F288D154AB17EB3BC0B58DF212E01EE8422 url=http://a.example.net/x"))
        assertNull(TorWebTunnel.normalize("webtunnel 192.0.2.7:443 F1E12F288D154AB17EB3BC0B58DF212E01EE8422 ver=0.0.3"))
        assertNull(TorWebTunnel.normalize("webtunnel 192.0.2.7 F1E12F288D154AB17EB3BC0B58DF212E01EE8422 url=https://a.example.net/x"))
    }

    @Test
    fun cleanDeduplicatesByFingerprint() {
        val sameFp = wt2.replace("download.example.net", "other.example.net")
        assertEquals(listOf(wt1, wt2), TorWebTunnel.clean(listOf(wt1, "junk", wt2, sameFp, wt1)))
    }

    @Test
    fun moatReplyParsedForWebtunnelOnly() {
        val body = """{"settings":[
            {"bridges":{"type":"webtunnel","source":"bridgedb","bridge_strings":["$wt1","$wt2"]}},
            {"bridges":{"type":"obfs4","source":"bridgedb","bridge_strings":["obfs4 1.2.3.4:443 5842D953EDA0A2CCFC424D88CDA6BBA095E405BF cert=x iat-mode=0"]}}
        ]}"""
        assertEquals(listOf(wt1, wt2), TorWebTunnel.parseMoat(body))
        assertTrue(TorWebTunnel.parseMoat("""{"errors":[{"code":404}]}""").isEmpty())
        assertTrue(TorWebTunnel.parseMoat("not json").isEmpty())
    }

    @Test
    fun moatRequestAsksIranWebtunnel() {
        val req = org.json.JSONObject(TorWebTunnel.moatRequest())
        assertEquals("ir", req.getString("country"))
        assertEquals("webtunnel", req.getJSONArray("transports").getString(0))
    }

    // ---------------------------------------------------------------- resilience.json `tor`

    @Test
    fun torSectionMissingGivesDefaults() {
        val c = ResilienceConfig.parse("""{"mirrors":{}}""")
        assertEquals(ResilienceConfig.TorSettings(), c.tor)
        assertEquals(listOf("direct", "webtunnel", "snowflake", "meek", "obfs4"), c.tor.order)
        assertTrue(c.tor.webtunnel && c.tor.moat)
        assertEquals(180, c.tor.autoBudgetS)
    }

    @Test
    fun torSectionParsedAndValidated() {
        val sf = "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://1098762253.rsc.cdn77.org front=www.phpmyadmin.net,cdn.zk.mk utls-imitate=hellorandomizedalpn"
        val c = ResilienceConfig.parse("""{"tor":{
            "webtunnel": false, "moat": false,
            "order": ["WebTunnel","bogus","obfs4","obfs4","direct"],
            "auto_budget_s": 5000,
            "webtunnel_bridges": ["$wt1", "nonsense"],
            "snowflake_bridges": ["$sf", "snowflake 8.8.8.8:80 8838024498816A039FCBBAB14E6F40A0843051FA url=https://x.example.org"]
        }}""")
        assertFalse(c.tor.webtunnel)
        assertFalse(c.tor.moat)
        assertEquals(listOf("webtunnel", "obfs4", "direct"), c.tor.order)
        assertEquals(600, c.tor.autoBudgetS)
        assertEquals(listOf(wt1), c.tor.webtunnelBridges)
        assertEquals(listOf(sf), c.tor.snowflakeBridges)
    }

    @Test
    fun torOrderAllInvalidFallsBackToDefault() {
        val c = ResilienceConfig.parse("""{"tor":{"order":["x","y"],"auto_budget_s":1}}""")
        assertEquals(ResilienceConfig.DEFAULT_TOR_ORDER, c.tor.order)
        assertEquals(30, c.tor.autoBudgetS)
    }

    @Test
    fun remoteTorBlockWinsOnlyWhenItSaysSomething() {
        val bundled = ResilienceConfig.Config(tor = ResilienceConfig.TorSettings(webtunnelBridges = listOf(wt1)))
        assertEquals(bundled.tor, ResilienceConfig.merge(ResilienceConfig.Config(), bundled).tor)
        val remote = ResilienceConfig.Config(tor = ResilienceConfig.TorSettings(webtunnel = false))
        assertFalse(ResilienceConfig.merge(remote, bundled).tor.webtunnel)
    }

    @Test
    fun shippedConfigUsesWartimeOrder() {
        val c = ResilienceConfig.parse(File("../remote/resilience.json").readText())
        assertEquals("direct", c.tor.order.first())
        assertEquals("webtunnel", c.tor.order[1])
        assertTrue(c.tor.order.indexOf("snowflake") < c.tor.order.indexOf("obfs4"))
        // No BridgeDB line is published in the repo; Moat delivers them at runtime.
        assertTrue(c.tor.webtunnelBridges.isEmpty())
        assertTrue(c.tor.moat)
    }

    // ---------------------------------------------------------------- Tor ladder

    @Test
    fun ladderFollowsConfigAndDropsImpossibleWebtunnel() {
        val m = TorManager.TorMode.entries
        fun keys(order: List<String>, wt: Boolean) = TorManager.ladderModes(order, wt).map { it.key }
        assertEquals(listOf("direct", "webtunnel", "snowflake", "meek", "obfs4"), keys(ResilienceConfig.DEFAULT_TOR_ORDER, true))
        assertEquals(listOf("direct", "snowflake", "meek", "obfs4"), keys(ResilienceConfig.DEFAULT_TOR_ORDER, false))
        // auto/manual can never be rungs.
        assertEquals(listOf("meek"), keys(listOf("auto", "manual", "meek"), true))
        // Only an impossible WebTunnel left → the classic ladder rather than nothing.
        assertEquals(listOf("direct", "meek", "obfs4", "snowflake"), keys(listOf("webtunnel"), false))
        assertTrue(m.any { it.key == "webtunnel" })
    }

    @Test
    fun automaticRungsFitTheAutomaticBudget() {
        val sum = ResilienceConfig.DEFAULT_TOR_ORDER
            .map { k -> TorManager.TorMode.entries.first { it.key == k } }
            .sumOf { TorManager.quickTimeoutS(it) }
        // Direct + WebTunnel + Snowflake must start well inside the default budget.
        val firstThree = listOf("direct", "webtunnel", "snowflake")
            .map { k -> TorManager.TorMode.entries.first { it.key == k } }
            .sumOf { TorManager.quickTimeoutS(it) }
        assertTrue(firstThree < ResilienceConfig.DEFAULT_TOR_AUTO_BUDGET_S)
        assertTrue(sum > 0)
        // Full (user-picked Tor) timeouts are unchanged.
        assertEquals(150L, TorManager.fullTimeoutS(TorManager.TorMode.DIRECT))
        assertEquals(300L, TorManager.fullTimeoutS(TorManager.TorMode.WEBTUNNEL))
    }
}

package com.molido.vpn

import com.molido.vpn.engine.Candidate
import com.molido.vpn.engine.Cost
import com.molido.vpn.engine.Measured
import com.molido.vpn.engine.NetworkMemory
import com.molido.vpn.engine.Probe
import com.molido.vpn.engine.QualityMonitor
import com.molido.vpn.engine.SmartConfig
import com.molido.vpn.engine.SmartPlan
import com.molido.vpn.engine.SmartScore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmartConnectTest {

    private fun ok(ms: Long, kbps: Long? = null) = Probe(true, ms, kbps)
    private val fail = Probe(false)

    // ---------------------------------------------------------------- scoring

    @Test
    fun noSuccessScoresZero() {
        assertEquals(0.0, SmartScore.score(Measured("a", listOf(fail, fail))), 0.0)
        assertEquals(0.0, SmartScore.score(Measured("a", emptyList())), 0.0)
    }

    @Test
    fun stableBeatsFlakyAtSameLatency() {
        val stable = SmartScore.score(Measured("a", listOf(ok(150), ok(160), ok(155))))
        val flaky = SmartScore.score(Measured("b", listOf(ok(150), fail, fail)))
        assertTrue("$stable > $flaky", stable > flaky)
    }

    @Test
    fun fasterAndQuickerDownloadScoresHigher() {
        val fast = SmartScore.score(Measured("a", listOf(ok(90, 8_000), ok(95), ok(100))))
        val slow = SmartScore.score(Measured("b", listOf(ok(1_800, 120), ok(1_900), ok(2_000))))
        assertTrue(fast > slow)
        assertTrue(fast in 80.0..100.0)
    }

    @Test
    fun iranExitIsHalved() {
        val probes = listOf(ok(100), ok(100), ok(100))
        val abroad = SmartScore.score(Measured("a", probes))
        val ir = SmartScore.score(Measured("a", probes, iranExit = true))
        assertEquals(abroad * SmartScore.IRAN_EXIT_FACTOR, ir, 1e-9)
    }

    @Test
    fun componentScalesAreBoundedAndMonotonic() {
        assertEquals(1.0, SmartScore.latencyScore(50), 0.0)
        assertEquals(0.0, SmartScore.latencyScore(10_000), 0.0)
        assertTrue(SmartScore.latencyScore(300) > SmartScore.latencyScore(900))
        assertEquals(0.5, SmartScore.throughputScore(null), 0.0)
        assertEquals(1.0, SmartScore.throughputScore(50_000), 0.0)
        assertTrue(SmartScore.connectScore(5_000) > SmartScore.connectScore(60_000))
        assertEquals(150L, SmartScore.median(listOf(100, 200)))
        assertNull(SmartScore.median(emptyList()))
    }

    @Test
    fun reasonIsHumanReadable() {
        val r = SmartScore.reason(Measured("a", listOf(ok(84, 6_200), ok(90), ok(80))))
        assertEquals("84 ms · 3/3 stable · 6.2 Mbit/s", r)
    }

    // ---------------------------------------------------------------- ordering

    private val warp = Candidate("wireguard", "wireguard", "WARP", Cost.CHEAP, 10_000)
    private val v2 = Candidate("v2ray", "v2ray", "V2Ray", Cost.CHEAP, 16_000)
    private val shard = Candidate("shard", "shard", "SHARD", Cost.CHEAP, 16_000)
    private val psi = Candidate("psiphon", "psiphon", "Psiphon", Cost.EXPENSIVE, 45_000)
    private val tor = Candidate("tor", "tor", "Tor", Cost.EXPENSIVE, 180_000)
    private val all = listOf(warp, v2, shard, psi, tor)

    @Test
    fun expensiveMethodsAreStagedAfterCheapOnes() {
        assertEquals(listOf("wireguard", "v2ray", "shard", "psiphon", "tor"), SmartPlan.order(all).map { it.id })
        // Even a reachable Tor stays behind unprobed cheap methods.
        val o = SmartPlan.order(all, preprobe = mapOf("tor" to ok(50))).map { it.id }
        assertTrue(o.indexOf("tor") > o.indexOf("shard"))
    }

    @Test
    fun probeResultsReorderCheapMethodsButNeverDropThem() {
        val pre = mapOf("shard" to ok(80), "v2ray" to ok(300), "wireguard" to fail)
        val o = SmartPlan.order(all, preprobe = pre).map { it.id }
        assertEquals(listOf("shard", "v2ray", "wireguard", "psiphon", "tor"), o)
    }

    @Test
    fun networkMemoryPinsWinnerAndRunnerUpFirst() {
        val mem = NetworkMemory.Entry(winner = "tor", runnerUp = "v2ray")
        assertEquals(listOf("tor", "v2ray", "wireguard", "shard", "psiphon"), SmartPlan.order(all, mem).map { it.id })
        // A remembered method that no longer exists is ignored.
        val gone = NetworkMemory.Entry(winner = "masque")
        assertEquals(SmartPlan.order(all).map { it.id }, SmartPlan.order(all, gone).map { it.id })
    }

    @Test
    fun demotedWarpFamilyGoesBehindForeignExits() {
        val o = SmartPlan.order(all, demote = setOf("wireguard")).map { it.id }
        assertEquals(listOf("v2ray", "shard", "wireguard", "psiphon", "tor"), o)
    }

    @Test
    fun sharedReportsBreakTies() {
        val o = SmartPlan.order(all, shared = mapOf("shard" to 0.9)).map { it.id }
        assertEquals("shard", o.first())
    }

    // ---------------------------------------------------------------- stop + pick

    @Test
    fun stopsWhenGoodEnoughAfterMinTrials() {
        val cfg = SmartConfig(goodEnough = 80, minTrials = 2, maxTrials = 8, totalBudgetS = 240)
        assertFalse(SmartPlan.shouldStop(mapOf("a" to 90.0), 1, 1_000, cfg))
        assertTrue(SmartPlan.shouldStop(mapOf("a" to 90.0, "b" to 10.0), 2, 1_000, cfg))
        assertFalse(SmartPlan.shouldStop(mapOf("a" to 70.0, "b" to 10.0), 2, 1_000, cfg))
    }

    @Test
    fun stopsOnBudgetAndTrialCapAndHalvesOnLowBattery() {
        val cfg = SmartConfig(goodEnough = 99, minTrials = 1, maxTrials = 6, totalBudgetS = 60)
        assertTrue(SmartPlan.shouldStop(mapOf("a" to 10.0), 1, 60_000, cfg))
        assertTrue(SmartPlan.shouldStop(mapOf("a" to 10.0), 6, 1_000, cfg))
        assertFalse(SmartPlan.shouldStop(mapOf("a" to 10.0), 3, 1_000, cfg))
        assertTrue(SmartPlan.shouldStop(mapOf("a" to 10.0), 3, 1_000, cfg, lowBattery = true))
    }

    @Test
    fun pickTakesBestAndRunnerUpAboveMinimum() {
        val p = SmartPlan.pick(linkedMapOf("warp" to 40.0, "v2ray" to 88.0, "tor" to 61.0, "psi" to 9.0), minScore = 15)
        assertEquals("v2ray", p.winner)
        assertEquals("tor", p.runnerUp)
        val none = SmartPlan.pick(linkedMapOf("a" to 0.0, "b" to 10.0), minScore = 15)
        assertNull(none.winner)
        assertNull(none.runnerUp)
        // Ties: earlier-tried wins.
        assertEquals("a", SmartPlan.pick(linkedMapOf("a" to 50.0, "b" to 50.0), 15).winner)
    }

    // ---------------------------------------------------------------- memory

    @Test
    fun memoryRoundTripsExpiresAndCaps() {
        val now = 1_000_000_000_000L
        var json: String? = null
        json = NetworkMemory.write(json, "cell:43235", NetworkMemory.Entry("v2ray", "tor", 88.0, now))
        val e = NetworkMemory.read(json, "cell:43235", now + 1000, 14)!!
        assertEquals("v2ray", e.winner)
        assertEquals("tor", e.runnerUp)
        assertNull(NetworkMemory.read(json, "cell:43235", now + 15 * 86_400_000L, 14))
        assertNull(NetworkMemory.read(json, "wifi:abcd", now, 14))
        assertNull(NetworkMemory.read("garbage", "x", now, 14))
        for (i in 0 until 30) json = NetworkMemory.write(json, "net$i", NetworkMemory.Entry("w", null, 1.0, now + i))
        assertEquals(NetworkMemory.MAX_NETWORKS, JSONObject(json!!).length())
        assertTrue(JSONObject(json).has("net29"))
        assertFalse(JSONObject(json).has("net0"))
    }

    @Test
    fun networkKeys() {
        assertEquals("cell:43235", NetworkMemory.key(false, false, "43235", null))
        assertEquals("eth", NetworkMemory.key(false, true, null, null))
        val a = NetworkMemory.key(true, false, "43235", "192.168.1.1,8.8.8.8")
        assertTrue(a.startsWith("wifi:") && a.length == 13)
        assertEquals(a, NetworkMemory.key(true, false, null, "192.168.1.1,8.8.8.8"))
        assertTrue(a != NetworkMemory.key(true, false, null, "10.0.0.1"))
        assertEquals("unknown", NetworkMemory.key(false, false, "", null))
    }

    // ---------------------------------------------------------------- quality monitor

    @Test
    fun monitorFiresOnFailuresThenCoolsDown() {
        val cfg = SmartConfig.Reeval(window = 5, failThreshold = 3, cooldownS = 600, maxPerHour = 3)
        val m = QualityMonitor(cfg, baselineMs = 100)
        var t = 0L
        assertEquals(QualityMonitor.Verdict.OK, m.add(ok(110), t))
        assertEquals(QualityMonitor.Verdict.OK, m.add(fail, t))
        assertEquals(QualityMonitor.Verdict.OK, m.add(fail, t))
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.add(fail, t))
        t += 60_000
        repeat(2) { m.add(fail, t) }
        assertEquals(QualityMonitor.Verdict.COOLDOWN, m.add(fail, t))
        t += 601_000
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.add(fail, t))
    }

    @Test
    fun monitorFiresWhenLatencyBlowsUpAgainstBaseline() {
        val cfg = SmartConfig.Reeval(window = 4, failThreshold = 3, latencyFactor = 3.0, latencyFloorMs = 1_000)
        val m = QualityMonitor(cfg, baselineMs = 200)
        repeat(3) { assertEquals(QualityMonitor.Verdict.OK, m.add(ok(2_500), 0)) }
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.add(ok(2_600), 0))
        // Slow but under the floor never fires.
        val m2 = QualityMonitor(cfg, baselineMs = 100)
        repeat(6) { assertEquals(QualityMonitor.Verdict.OK, m2.add(ok(900), 0)) }
    }

    @Test
    fun monitorRespectsHourlyCapAndDisable() {
        val cfg = SmartConfig.Reeval(window = 2, failThreshold = 1, cooldownS = 60, maxPerHour = 2)
        val m = QualityMonitor(cfg, null)
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.add(fail, 0))
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.add(fail, 61_000))
        assertEquals(QualityMonitor.Verdict.COOLDOWN, m.add(fail, 122_000))
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.add(fail, 3_700_000))
        val off = QualityMonitor(cfg.copy(enabled = false), null)
        repeat(5) { assertEquals(QualityMonitor.Verdict.OK, off.add(fail, it * 100_000L)) }
    }

    // ---------------------------------------------------------------- config

    @Test
    fun smartConfigParsesClampsAndDefaults() {
        assertEquals(SmartConfig(), SmartConfig.parse(null))
        val c = SmartConfig.parse(JSONObject("""{"max_trials":99,"min_trials":50,"good_enough":0,"parallel_probes":3,
            "reeval":{"interval_s":5,"latency_factor":"x","max_per_hour":1}}"""))
        assertEquals(20, c.maxTrials)
        assertEquals(20, c.minTrials)
        assertEquals(1, c.goodEnough)
        assertEquals(3, c.parallelProbes)
        assertEquals(30, c.reeval.intervalS)
        assertEquals(3.0, c.reeval.latencyFactor, 0.0)
        assertEquals(1, c.reeval.maxPerHour)
        assertEquals(4, SmartConfig(maxTrials = 8).trialsFor(lowBattery = true))
        assertEquals(2, SmartConfig(maxTrials = 3).trialsFor(lowBattery = true))
    }

    @Test
    fun shippedResilienceHasSmartSection() {
        val c = ResilienceConfig.parse(File("../remote/resilience.json").readText())
        assertTrue(c.smart.enabled)
        assertEquals(SmartConfig(), c.smart)
        // One good method is not a race: at least three methods compete before stopping.
        assertFalse(SmartPlan.shouldStop(mapOf("fast" to 90.0), 1, 1_000, c.smart))
        assertTrue(SmartPlan.shouldStop(mapOf("fast" to 90.0), 3, 1_000, c.smart))
        assertFalse(SmartPlan.shouldStop(mapOf("slow" to 79.0), 1, 1_000, c.smart))
        assertFalse(SmartPlan.shouldStop(mapOf("failed" to 0.0), 1, 1_000, c.smart))
    }
}

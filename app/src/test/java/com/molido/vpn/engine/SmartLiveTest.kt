package com.molido.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLiveTest {
    // ---- video-stall heuristic
    private fun feed(d: StallDetector, start: Long, n: Int, rxKbps: Long, tx: Long): StallDetector.Verdict {
        for (i in 1..n) {
            val v = d.add(rxKbps * 5_000 / 8, tx, 5_000, start + i * 5_000L)
            if (v != StallDetector.Verdict.OK) return v
        }
        return StallDetector.Verdict.OK
    }

    @Test fun stallAfterVideoBurstWhenRequestsGetNoAnswer() {
        val d = StallDetector()
        assertEquals(StallDetector.Verdict.OK, feed(d, 0, 3, 6_000, 20_000))
        // 25 s of requests with nothing back: not yet (sustained 30 s rule)
        assertEquals(StallDetector.Verdict.OK, feed(d, 15_000, 5, 20, 4_000))
        assertEquals(StallDetector.Verdict.STALL, feed(d, 40_000, 2, 20, 4_000))
    }

    @Test fun fullBufferIsNotAStall() {
        val d = StallDetector()
        feed(d, 0, 3, 6_000, 20_000)
        assertEquals(StallDetector.Verdict.OK, feed(d, 15_000, 12, 0, 0))
    }

    @Test fun noVideoSessionNoVerdict() {
        assertEquals(StallDetector.Verdict.OK, feed(StallDetector(), 0, 20, 30, 3_000))
    }

    @Test fun sustainedLowThroughputDuringVideo() {
        val d = StallDetector()
        var now = 0L
        var v = StallDetector.Verdict.OK
        // one burst a minute keeps the session "active"; the rest crawls at ~400 kbit/s
        for (i in 1..40) {
            now += 5_000
            val kbps = if (i % 12 == 1) 2_000L else 400L
            v = d.add(kbps * 5_000 / 8, 3_000, 5_000, now)
            if (v != StallDetector.Verdict.OK) break
        }
        assertEquals(StallDetector.Verdict.SLOW, v)
    }

    // ---- learning per network and time of day
    @Test fun banditLearnsWinnerAndExploresUnseen() {
        var j: String? = null
        val k = MethodBandit.key("cell:43235", 21)
        assertEquals("cell:43235@3", k)
        repeat(6) { j = MethodBandit.record(j, k, "v2ray", 0.9, 1L) }
        repeat(6) { j = MethodBandit.record(j, k, "psiphon", 0.3, 1L) }
        val r = MethodBandit.rank(MethodBandit.arms(j, k), listOf("v2ray", "psiphon", "tor"))
        assertTrue(r.getValue("v2ray") > r.getValue("tor"))
        assertTrue(r.getValue("tor") > r.getValue("psiphon"))
        assertTrue(MethodBandit.arms(j, MethodBandit.key("cell:43235", 9)).isEmpty())
    }

    @Test fun banditForgetsSlowly() {
        var j: String? = null
        repeat(40) { j = MethodBandit.record(j, "k", "a", 1.0, 1L) }
        repeat(20) { j = MethodBandit.record(j, "k", "a", 0.0, 1L) }
        val a = MethodBandit.arms(j, "k").getValue("a")
        assertTrue("recent failures must dominate, mean=${a.mean}", a.mean < 0.5)
        assertTrue(a.n <= MethodBandit.MAX_N)
    }

    @Test fun learnedRankOrdersCheapMethods() {
        val c = listOf(Candidate("a", "a", "A", Cost.CHEAP, 1), Candidate("b", "b", "B", Cost.CHEAP, 1))
        assertEquals(listOf("b", "a"), SmartPlan.order(c, learned = mapOf("a" to 0.2, "b" to 0.9)).map { it.id })
    }

    // ---- stability rules
    @Test fun switchPolicyNeedsDwellSustainAndMargin() {
        val p = SwitchPolicy()
        assertFalse(p.dwellOver(0, 60_000))
        assertTrue(p.dwellOver(0, 5 * 60_000))
        assertFalse(p.sustained(0, 20_000))
        assertTrue(p.sustained(0, 30_000))
        assertFalse(p.clearlyBetter(1_200.0, 1_000.0))
        assertTrue(p.clearlyBetter(1_250.0, 1_000.0))
        assertTrue(p.clearlyBetter(300.0, null))
        assertFalse(p.clearlyBetter(0.0, null))
    }

    @Test fun stabilityWeighsMostInTheScore() {
        val w = Weights()
        assertTrue(w.stability > w.throughput && w.stability > w.latency)
        val flaky = Measured("f", listOf(Probe(true, 100, 20_000), Probe(false), Probe(true, 100)))
        val steady = Measured("s", List(3) { Probe(true, 200, if (it == 0) 6_000 else null) })
        assertTrue(SmartScore.score(steady) > SmartScore.score(flaky))
    }

    @Test fun dropsPerHour() {
        val s = StabilityStats()
        s.up(0); s.drop(30 * 60_000L); s.up(30 * 60_000L); s.switched()
        assertEquals(1.0, s.hours(60 * 60_000L), 1e-9)
        assertEquals(1.0, s.dropsPerHour(60 * 60_000L), 1e-9)
        assertTrue(s.summary(60 * 60_000L).contains("1 seamless"))
    }

    @Test fun monitorForceRespectsCooldown() {
        val m = QualityMonitor(SmartConfig.Reeval(cooldownS = 300), null)
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.force(1_000_000))
        assertEquals(QualityMonitor.Verdict.COOLDOWN, m.force(1_000_000 + 60_000))
        assertEquals(QualityMonitor.Verdict.DEGRADED, m.force(1_000_000 + 301_000))
    }

    // ---- live updates
    @Test fun liveVersionChangesOnlyMovedParts() {
        val a = LiveVersion("1", flags = "f1", notice = "n1", release = "v3.2.1", resilience = "r1", catalog = "c1")
        assertEquals(LiveVersion.Part.values().toSet(), LiveVersion.changes(null, a))
        assertTrue(LiveVersion.changes(a, a).isEmpty())
        val b = a.copy(v = "2", flags = "f2", catalog = "c2")
        assertEquals(setOf(LiveVersion.Part.FLAGS, LiveVersion.Part.CATALOG), LiveVersion.changes(a, b))
        assertTrue(LiveVersion.changes(a, a.copy(v = "3", flags = "")).isEmpty())
        assertEquals(a, LiveVersion.parse(a.toJson()))
        assertEquals(null, LiveVersion.parse("{}"))
    }

    @Test fun throttle() {
        assertFalse(LiveThrottle.due(1_000, 30_000, 45_000))
        assertTrue(LiveThrottle.due(1_000, 50_000, 45_000))
    }

    @Test fun workerSignatureVerifies() {
        // Signed by WebCrypto (as the Worker does) with a throwaway key.
        val pub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0/VeiJcFkuWi9BaB4oWKEvc7nxn3+0adJL8Lt+3WLzMIKRAdC+v1ZK7wx00lxGuseJD80EllB6gupkhS2b2uGQ=="
        val payload = "{\"t\":\"live\",\"at\":1,\"flags\":{\"disabled\":[\"tor\"]},\"notice\":{}}"
        val sig = "GNfQb3RP/69JBTEXmBEw5D2Mv9ciDLQXid483hwMUVHSISDWWwxllCOzocuS28IWQ6Ga6FLUX0jVBf9xHGmGKA=="
        assertTrue(LiveSig.verify(pub, payload, sig))
        assertFalse(LiveSig.verify(pub, payload.replace("tor", "v2ray"), sig))
        assertFalse(LiveSig.verify(pub, payload, "AAAA"))
    }

    @Test fun p1363ToDerHandlesHighBitAndLeadingZeros() {
        val raw = ByteArray(64).also { it[0] = 0; it[1] = 0x7f; it[32] = 0x80.toByte() }
        val der = LiveSig.p1363ToDer(raw)
        assertEquals(0x30, der[0].toInt())
        assertEquals(31, der[3].toInt())
        assertEquals(33, der[4 + 31 + 1].toInt())
    }
}

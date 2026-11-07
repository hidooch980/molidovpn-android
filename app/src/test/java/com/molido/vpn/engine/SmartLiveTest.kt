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
    @Test fun staleCdnCopyNeverStepsBack() {
        val now = LiveVersion("2", flags = "f2", release = "v3.2.158", at = 200)
        val stale = LiveVersion("1", flags = "f1", release = "v3.2.156", at = 100)
        assertTrue(LiveVersion.isOlder(now, stale))
        assertTrue(LiveVersion.changes(now, stale).isEmpty())
        assertFalse(LiveVersion.isOlder(stale, now))
        assertFalse(LiveVersion.isOlder(null, stale))
        assertFalse(LiveVersion.isOlder(now, LiveVersion("3", at = 0)))
    }

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

    @Test fun invalidWeightsAndMeasurementsProduceFiniteBoundedScores() {
        val measured = Measured(
            "invalid",
            listOf(Probe(ok = true, latencyMs = -1, throughputKbps = -1)),
            connectMs = -1,
        )
        val invalidWeights = Weights(
            stability = Double.NaN,
            latency = Double.POSITIVE_INFINITY,
            throughput = -1.0,
            connect = Double.NaN,
        )
        val score = SmartScore.score(measured, invalidWeights)
        assertTrue("score must be finite: $score", score.isFinite())
        assertTrue("score must remain within 0..100: $score", score in 0.0..100.0)

        val zeroWeights = SmartScore.score(
            measured,
            Weights(stability = 0.0, latency = 0.0, throughput = 0.0, connect = 0.0),
        )
        assertTrue(zeroWeights.isFinite())
        assertTrue(zeroWeights in 0.0..100.0)
    }

    @Test fun noSuccessfulProbeRemainsZeroWithInvalidWeights() {
        val score = SmartScore.score(
            Measured("dead", listOf(Probe(ok = false))),
            Weights(Double.NaN, -1.0, Double.POSITIVE_INFINITY, 0.0),
        )
        assertEquals(0.0, score, 0.0)
    }


    @Test fun banditRejectsNonFiniteRewardsAndInvalidMethodIds() {
        val original = """{"cell:43235@3":{"v2ray":{"n":2.0,"m":0.8},"_at":100}}"""
        assertEquals(original, MethodBandit.record(original, "cell:43235@3", "v2ray", Double.NaN, 200))
        assertEquals(original, MethodBandit.record(original, "cell:43235@3", "v2ray", Double.POSITIVE_INFINITY, 200))
        assertEquals(original, MethodBandit.record(original, "cell:43235@3", "contains space", 0.2, 200))
        assertEquals(original, MethodBandit.record(original, "cell:43235@3", "v2ray", 0.2, 0))
    }

    @Test fun banditRankingIgnoresCorruptArmsAndBoundsUnseenScore() {
        val arms = mapOf(
            "healthy" to MethodBandit.Arm(4.0, 0.8),
            "nan-mean" to MethodBandit.Arm(3.0, Double.NaN),
            "infinite-count" to MethodBandit.Arm(Double.POSITIVE_INFINITY, 0.9),
            "out-of-range" to MethodBandit.Arm(2.0, 4.0),
        )
        val ranked = MethodBandit.rank(
            arms,
            listOf("healthy", "nan-mean", "infinite-count", "out-of-range", "new"),
            unseen = Double.NaN,
        )
        assertTrue(ranked.values.all { it.isFinite() })
        assertTrue(ranked.getValue("healthy") > ranked.getValue("new"))
        assertEquals(0.55, ranked.getValue("nan-mean"), 1e-9)
        assertEquals(0.55, ranked.getValue("infinite-count"), 1e-9)
        assertEquals(0.55, ranked.getValue("out-of-range"), 1e-9)
        assertEquals(0.55, ranked.getValue("new"), 1e-9)
    }



    // ---- evidence-gated live route switching
    @Test fun switchEvidenceRequiresThreeProbesAndTwoHealthy() {
        val p = SwitchEvidencePolicy()
        assertFalse(p.shouldSwitch(80.0, 70.0, 1, 3, 0, true))
        assertFalse(p.shouldSwitch(80.0, 70.0, 2, 2, 0, true))
        assertTrue(p.shouldSwitch(80.0, 70.0, 2, 3, 0, true))
    }

    @Test fun switchEvidenceRequiresEightAbsoluteScorePoints() {
        val p = SwitchEvidencePolicy()
        assertFalse(p.shouldSwitch(77.9, 70.0, 3, 3, 0, true))
        assertTrue(p.shouldSwitch(78.0, 70.0, 3, 3, 0, true))
    }

    @Test fun healthFailureBypassesDwellButNotCandidateEvidence() {
        val p = SwitchEvidencePolicy()
        assertFalse(p.shouldSwitch(90.0, 70.0, 1, 3, 2, false))
        assertFalse(p.shouldSwitch(75.0, 70.0, 3, 3, 2, false))
        assertTrue(p.shouldSwitch(80.0, 70.0, 2, 3, 2, false))
        assertFalse(p.shouldSwitch(80.0, 70.0, 2, 3, 1, false))
    }

    @Test fun switchEvidenceRejectsInvalidScoresAndProbeCounts() {
        val p = SwitchEvidencePolicy()
        assertFalse(p.shouldSwitch(Double.NaN, 70.0, 3, 3, 2, true))
        assertFalse(p.shouldSwitch(101.0, 70.0, 3, 3, 2, true))
        assertFalse(p.shouldSwitch(80.0, Double.POSITIVE_INFINITY, 3, 3, 2, true))
        assertFalse(p.shouldSwitch(80.0, 70.0, 4, 3, 2, true))
        assertFalse(p.shouldSwitch(80.0, 70.0, -1, 3, 2, true))
    }

    @Test fun legacySwitchPolicyRejectsNonFiniteMeasurementsAndClockRollback() {
        val p = SwitchPolicy()
        assertFalse(p.clearlyBetter(Double.POSITIVE_INFINITY, 10.0))
        assertFalse(p.clearlyBetter(20.0, Double.NaN))
        assertFalse(p.dwellOver(10_000, 9_000))
        assertFalse(p.sustained(10_000, 9_000))
    }
}

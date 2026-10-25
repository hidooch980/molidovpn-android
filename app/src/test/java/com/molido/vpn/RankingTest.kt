package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class RankingTest {

    @Test
    fun unknownRemoteScoreIsNeutralPrior() {
        assertEquals(0.5, ShardHealth.UNKNOWN_REMOTE_SCORE, 0.0)
        assertEquals(0.5, ShardHealth.remoteTieScore(emptyMap(), "abc"), 0.0)
        assertEquals(0.9, ShardHealth.remoteTieScore(mapOf("abc" to 0.9), "abc"), 0.0)
    }

    @Test
    fun untriedNodeWithNoReportsBeatsKnownBadNode() {
        // Same local rank (all untried); "bad" failed 8/8 in Iran (worker score 1/10), "new" has no reports.
        val remote = mapOf("bad" to 0.1, "good" to 0.9)
        val nodes = listOf("bad", "new", "good")
        val out = ShardHealth.orderByLocalThenRemote(nodes, { 500_000 }, { ShardHealth.remoteTieScore(remote, it) })
        assertEquals(listOf("good", "new", "bad"), out)
    }

    @Test
    fun localEvidenceStillWins() {
        val remote = mapOf("a" to 0.1, "b" to 0.9)
        val local = mapOf("a" to 800, "b" to 500_000) // a worked here at 800 ms, b never tried
        val out = ShardHealth.orderByLocalThenRemote(listOf("b", "a"), { local.getValue(it) }, { ShardHealth.remoteTieScore(remote, it) })
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun stableWithinTies() {
        val out = ShardHealth.orderByLocalThenRemote(listOf("x", "y", "z"), { 1 }, { 0.5 })
        assertEquals(listOf("x", "y", "z"), out)
    }

    @Test
    fun heartbeatStaysInsideWorkerOnlineWindow() {
        val periodS = MolidoVpnService.HEARTBEAT_EVERY_TICKS * 30
        assert(periodS >= 900) { "heartbeat >= 15 min: the free Worker plan is 100k requests/day" }
        assert(periodS + 30 < 1200) { "must stay inside the worker's 1200 s online window" }
    }
}

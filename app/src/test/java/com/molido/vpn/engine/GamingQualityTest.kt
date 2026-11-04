package com.molido.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingQualityTest {
    private fun ok(rtt: Long) = GamingProbe(success = true, rttMs = rtt)
    private val lost = GamingProbe(success = false)

    @Test fun noSamplesAndAllLossScoreZero() {
        assertEquals(0.0, GamingQuality.score(GamingMeasurement(emptyList())), 0.0)
        assertEquals(0.0, GamingQuality.score(GamingMeasurement(listOf(lost, lost))), 0.0)
    }

    @Test fun stableLowLatencyRouteBeatsHighJitterAndLoss() {
        val stable = GamingMeasurement(listOf(ok(32), ok(34), ok(33), ok(35), ok(34)), 500)
        val unstable = GamingMeasurement(listOf(ok(35), lost, ok(110), ok(25), lost), 8_000)
        assertTrue(GamingQuality.score(stable) > GamingQuality.score(unstable))
    }

    @Test fun p95UsesTailRatherThanOnlyBestPing() {
        val lowVariance = listOf(20L, 21L, 22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L)
            .map(::ok)
        val spike = (listOf(20L, 21L, 22L, 23L, 24L, 25L, 26L, 27L, 28L) + 300L).map(::ok)
        assertTrue(GamingQuality.p95RttMs(spike)!! > GamingQuality.p95RttMs(lowVariance)!!)
    }

    @Test fun jitterMeasuresConsecutiveRttVariation() {
        assertEquals(2L, GamingQuality.jitterMs(listOf(ok(30), ok(32), ok(31))))
        assertEquals(null, GamingQuality.jitterMs(listOf(ok(30))))
    }

    @Test fun scoreIsBoundedAndMissingOptionalMetricsAreNeutral() {
        val score = GamingQuality.score(GamingMeasurement(listOf(ok(45), ok(46), ok(44))))
        assertTrue(score in 0.0..100.0)
        assertTrue(GamingQuality.score(GamingMeasurement(listOf(ok(40), ok(41), ok(40)), 500)) >
            GamingQuality.score(GamingMeasurement(listOf(ok(150), ok(190), ok(160)), 8_000)))
    }
}

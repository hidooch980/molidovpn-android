package com.molido.vpn.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThroughputTest {
    private fun m(kbps: Long, ms: Long = 160) =
        Measured("x", List(3) { Probe(true, ms, if (it == 0) kbps else null) })

    @Test fun slowLowLatencyRouteIsNotGoodEnough() {
        val s = SmartScore.score(m(1_100))
        assertTrue("1.1 Mbit/s must stay below goodEnough, was $s", s < SmartConfig().goodEnough)
    }

    @Test fun fastRouteBeatsSlowerLowerPingRoute() {
        assertTrue(SmartScore.score(m(8_000, 400)) > SmartScore.score(m(1_100, 120)))
    }

    @Test fun fastRouteIsGoodEnough() {
        assertTrue(SmartScore.score(m(10_000, 150)) >= SmartConfig().goodEnough)
    }
}

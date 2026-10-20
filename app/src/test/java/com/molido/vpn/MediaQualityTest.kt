package com.molido.vpn

import com.molido.vpn.engine.Measured
import com.molido.vpn.engine.Probe
import com.molido.vpn.engine.SmartScore
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQualityTest {
    private fun stable(id: String, ms: Long, kbps: Long?) =
        Measured(id, List(3) { Probe(true, ms, kbps) })

    @Test fun fastVideoTransferBeatsLowPingWithVerySlowDownload() {
        val video = SmartScore.score(stable("video", 400, 15_000))
        val slow = SmartScore.score(stable("slow", 80, 200))
        assertTrue(video > slow)
    }

    @Test fun stableCallBeatsFastButRepeatedlyFailingRoute() {
        val stable = SmartScore.score(stable("call", 200, 1_000))
        val flaky = SmartScore.score(Measured("flaky", listOf(
            Probe(true, 50, 50_000), Probe(false), Probe(false)
        )))
        assertTrue(stable > flaky)
    }

    @Test fun bandwidthWithoutEvidenceStaysBetweenMeasuredExtremes() {
        val low = SmartScore.score(stable("low", 200, 50))
        val unknown = SmartScore.score(stable("unknown", 200, null))
        val high = SmartScore.score(stable("high", 200, 10_000))
        assertTrue(low < unknown && unknown < high)
    }
}

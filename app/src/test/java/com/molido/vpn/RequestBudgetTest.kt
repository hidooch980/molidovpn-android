package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RequestBudgetTest {
    @Test fun staticCopiesComeBeforeTheWorker() {
        val urls = StaticFirst.urls("flags.json", "/app/flags.json")
        assertTrue(urls[0].startsWith("https://raw.githubusercontent.com/"))
        assertTrue(urls[1].startsWith("https://cdn.jsdelivr.net/"))
        assertTrue(urls.last().contains("workers.dev"))
        assertEquals("scores-all.json", StaticFirst.opFile("scores", ""))
        assertEquals("cfip-mci.json", StaticFirst.opFile("cfip", "mci"))
    }

    @Test fun releaseMetadataAsksTheWorkerLast() {
        val urls = UpdateSources.releaseJsonUrls(emptyList())
        assertTrue(urls.first().startsWith(LiveSync.RAW))
        assertEquals(UpdateSources.WORKER_RELEASE_URL, urls.last())
    }

    @Test fun aboutOneSessionInTenHeartbeats() {
        val n = (0 until 10_000).count { ConnectionReports.heartbeatSampled("session-$it-abcdef") }
        assertTrue("sampled $n of 10000", n in 800..1200)
    }

    @Test fun edgeRoutesAreRecognisedByName() {
        assertTrue(EdgeFallback.isEdge("vless://id@1.2.3.4:443?type=ws#%E2%98%81%EF%B8%8F%20MolidoVPN%20Cloudflare%203"))
        assertTrue(EdgeFallback.isEdge("vless://id@1.2.3.4:443?type=ws#☁️ MolidoVPN Cloudflare 1"))
        assertFalse(EdgeFallback.isEdge("vless://id@1.2.3.4:443?type=ws#MolidoVPN DE 1"))
        assertFalse(EdgeFallback.isEdge("vless://id@1.2.3.4:443?type=ws"))
    }

    @Test fun edgeOnlyWhenArmedAndFlagged() {
        assertTrue(EdgeFallback.armed(1_000, 2_000, true))
        assertFalse(EdgeFallback.armed(3_000, 2_000, true))
        assertFalse(EdgeFallback.armed(1_000, 2_000, false))
    }

    @Test fun shippedResilienceKeepsEdgeAsLastResortFlag() {
        val c = ResilienceConfig.parse(File("../remote/resilience.json").readText())
        assertTrue(c.edgeLastResort)
        assertFalse(ResilienceConfig.parse("""{"edge_last_resort": false}""").edgeLastResort)
    }
}

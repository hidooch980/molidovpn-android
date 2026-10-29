package com.molido.vpn

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AdaptiveTlsRetryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun node(type: String = "tcp", scheme: String = "vless"): ShardNode =
        V2rayNodes.toShardNode(V2rayNodes.parseOne(
            "$scheme://11111111-1111-4111-8111-111111111111@192.0.2.1:443?security=tls&type=$type&sni=tls.example&host=cdn.example&fp=edge&path=%2Fstream"
        )!!)

    @Before fun reset() = RetryTweaks.clearForTests()
    @After fun cleanup() = RetryTweaks.clearForTests()

    @Test fun successfulProbeKeepsExactSettingsForLiveLaunchAndNextConnect() {
        val original = node()
        assertNull(RetryTweaks.prepare(context, original).retryProfile!!.fingerprint)
        repeat(2) { RetryTweaks.recordFailure(context, original) }
        val candidate = RetryTweaks.prepare(context, original)
        val probe = ShardConfigs.outbound(context, candidate, "same", mux = false)
        assertEquals("chrome", probe.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("fingerprint"))
        RetryTweaks.recordSuccess(context, candidate)
        val live = ShardConfigs.outbound(context, candidate, "same", mux = false)
        assertJsonEquivalent(probe, live)
        assertEquals("chrome", RetryTweaks.prepare(context, original).retryProfile!!.fingerprint)
        assertEquals("tls.example", live.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
        assertFalse(live.getJSONObject("streamSettings").getJSONObject("tlsSettings").getBoolean("allowInsecure"))
    }

    @Test fun failedLearnedProfileFallsBackAndOriginalSuccessClearsIt() {
        val original = node()
        repeat(2) { RetryTweaks.failed("a", original, 1000) }
        val candidate = RetryTweaks.prepare("a", original, 1001)
        RetryTweaks.succeeded("a", candidate, 1002)
        RetryTweaks.failed("a", candidate, 1003)
        val fallback = RetryTweaks.prepare("a", original, 1004)
        assertNull(fallback.retryProfile!!.fingerprint)
        RetryTweaks.succeeded("a", fallback, 1005)
        assertNull(RetryTweaks.prepare("a", original, 1006).retryProfile!!.fingerprint)
    }

    @Test fun measurementsExpireAndCannotCrossNetworksOrHandover() {
        val original = node()
        repeat(2) { RetryTweaks.failed("mci", original, 1000) }
        val candidate = RetryTweaks.prepare("mci", original, 1001)
        assertEquals("chrome", candidate.retryProfile!!.fingerprint)
        RetryTweaks.succeeded("irancell", candidate, 1002)
        RetryTweaks.failed("irancell", candidate, 1003)
        assertNull(RetryTweaks.prepare("irancell", original, 1004).retryProfile!!.fingerprint)
        RetryTweaks.succeeded("mci", candidate, 1005)
        assertEquals("chrome", RetryTweaks.prepare("mci", original, 1006).retryProfile!!.fingerprint)
        assertNull(RetryTweaks.prepare("mci", original, 1005 + 6 * 60 * 60 * 1000L).retryProfile!!.fingerprint)
    }

    @Test fun wsAndXhttpPublisherParametersAndInterlockedTlsStayIntact() {
        for (type in listOf("ws", "xhttp")) {
            val original = node(type)
            repeat(10) { RetryTweaks.failed(type, original) }
            val candidate = RetryTweaks.prepare(type, original)
            assertNull(candidate.retryProfile!!.fingerprint)
            assertNull(candidate.retryProfile!!.path)
            assertEquals(original.v2ray, candidate.v2ray)
            val rendered = ShardConfigs.outbound(context, candidate, "preserved", mux = false)
            val stream = rendered.getJSONObject("streamSettings")
            assertEquals("edge", stream.getJSONObject("tlsSettings").getString("fingerprint"))
            assertEquals("tls.example", stream.getJSONObject("tlsSettings").getString("serverName"))
        }
        val pinned = node().copy(v2ray = null, fingerprint = "unsafe", cipherSuites = "pinned")
        repeat(10) { RetryTweaks.failed("pinned", pinned) }
        assertNull(RetryTweaks.prepare("pinned", pinned).retryProfile!!.fingerprint)
    }

    @Test fun wsEarlyDataSnapshotIsAlsoRetainedAfterSuccess() {
        val original = node("ws", "trojan")
        repeat(3) { RetryTweaks.failed("trojan", original, 1000) }
        val candidate = RetryTweaks.prepare("trojan", original, 1001)
        assertEquals("/stream?ed=2048", candidate.retryProfile!!.path)
        RetryTweaks.succeeded("trojan", candidate, 1002)
        assertEquals(candidate.retryProfile, RetryTweaks.prepare("trojan", original, 1003).retryProfile)
        assertEquals("/stream", original.v2ray!!.path)
    }
}

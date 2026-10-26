package com.molido.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class VlessTransportTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun uri(type: String, query: String = "") =
        "vless://$id@192.0.2.1:443?encryption=none&security=tls&type=$type&host=cdn.example&sni=tls.example&fp=firefox&$query"
    private fun render(node: V2rayNode) = V2rayNodes.outbound(node, node.address, node.port, "test")

    @Test fun keepsXhttpTransportAndEntireExtra() {
        val extra = """{"xmux":{"maxConnections":1},"xPaddingBytes":"100-1000","headers":{"X-Test":"value"}}"""
        val node = V2rayNodes.parseOne(uri("xhttp", "mode=stream-up&path=${encode("/upload?token=a+b")}&alpn=h2&extra=${encode(extra)}"))!!
        val stream = render(node).getJSONObject("streamSettings")
        val xhttp = stream.getJSONObject("xhttpSettings")
        assertEquals("stream-up", xhttp.getString("mode"))
        assertEquals("/upload?token=a+b", xhttp.getString("path"))
        assertEquals("cdn.example", xhttp.getString("host"))
        assertTrue(JSONObject(extra).similar(xhttp.getJSONObject("extra")))
        assertEquals("tls.example", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("firefox", stream.getJSONObject("tlsSettings").getString("fingerprint"))
        assertEquals("h2", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        assertFalse(stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"))
        assertFalse(V2rayNodes.edgeExpandable(node))
    }

    @Test fun vlessWsAndXhttpNeverReceiveRetryRewrites() {
        for (type in listOf("ws", "websocket", "xhttp", "splithttp")) {
            val node = V2rayNodes.parseOne(uri(type, "path=%2Ftunnel%3Fed%3D1024&alpn=http%2F1.1"))!!
            val shard = V2rayNodes.toShardNode(node)
            for (failures in listOf(0, 1, 2, 3, 10, 50)) {
                assertNull("$type fingerprint retry $failures", RetryTweaks.fingerprintFor(shard, failures))
                assertNull("$type early-data retry $failures", RetryTweaks.earlyDataPath(shard, failures))
            }
        }
    }

    @Test fun wsKeepsPublisherEarlyDataAndAliasesNormalize() {
        val node = V2rayNodes.parseOne(uri("websocket", "path=%2Fws%3Fed%3D1024&alpn=http%2F1.1"))!!
        val stream = render(node).getJSONObject("streamSettings")
        assertEquals("ws", node.network)
        assertEquals("/ws?ed=1024", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example", stream.getJSONObject("wsSettings").getString("host"))
        assertEquals("xhttp", V2rayNodes.parseOne(uri("splithttp"))!!.network)
    }

    @Test fun invalidExplicitXhttpCannotSilentlyBecomeDefaults() {
        for (query in listOf("mode=typo", "extra=${encode("not-json")}", "extra=${encode("[]")}",
            "extra=${encode("{\"headers\":{\"Host\":\"wrong.example\"}}")}",
            "mode=stream-one&extra=${encode("{\"downloadSettings\":{}}")}")) {
            assertNull(query, V2rayNodes.parseOne(uri("xhttp", query)))
        }
        assertEquals(1, V2rayNodes.parse(listOf(uri("xhttp", "mode=typo"), uri("ws"))).size)
    }

    @Test fun incompatibleFlowAndRealityWsAreRejected() {
        for (type in listOf("xhttp", "ws")) {
            assertNull(V2rayNodes.parseOne(uri(type, "flow=xtls-rprx-vision")))
        }
        val reality = uri("ws").replace("security=tls", "security=reality") + "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertNull(V2rayNodes.parseOne(reality))
        assertNull(V2rayNodes.parseOne(uri("xhttp").replace("fp=firefox", "fp=unknown")))
    }

    @Test fun supportedFingerprintPreservedAndUnsafeNotAcceptedForReality() {
        for (fp in listOf("unsafe", "randomizednoalpn", "hellochrome_133")) {
            val node = V2rayNodes.parseOne(uri("xhttp").replace("fp=firefox", "fp=$fp"))!!
            assertEquals(fp, render(node).getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("fingerprint"))
        }
        assertNull(V2rayNodes.parseOne(uri("xhttp").replace("fp=firefox", "fp=unsafe")
            .replace("security=tls", "security=reality") + "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test fun preservesH3AndSeparateDownloadTransport() {
        val extra = """{"downloadSettings":{"address":"192.0.2.2","port":443,"network":"xhttp","security":"tls",
            "tlsSettings":{"serverName":"download.example","alpn":["h2"]},"xhttpSettings":{"path":"/x"}}}"""
        val node = V2rayNodes.parseOne(uri("xhttp", "mode=stream-up&path=%2Fx&alpn=h3&extra=${encode(extra)}"))!!
        val stream = render(node).getJSONObject("streamSettings")
        assertEquals("h3", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        val download = stream.getJSONObject("xhttpSettings").getJSONObject("extra").getJSONObject("downloadSettings")
        assertTrue(JSONObject(extra).getJSONObject("downloadSettings").similar(download))
    }

    @Test fun otherTransportRetryBehaviorIsUnchanged() {
        val tcp = V2rayNodes.toShardNode(V2rayNodes.parseOne(uri("tcp"))!!)
        assertEquals("chrome", RetryTweaks.fingerprintFor(tcp, 2))
        val trojan = V2rayNodes.toShardNode(V2rayNodes.parseOne(uri("ws", "path=%2Fws").replace("vless://", "trojan://"))!!)
        assertEquals("/ws?ed=2048", RetryTweaks.earlyDataPath(trojan, 3))
    }

    @Test fun pinnedXrayAcceptsActualRenderedOutbounds() {
        val binary = System.getenv("MOLIDO_XRAY")
        assumeTrue("Set MOLIDO_XRAY to validate real engine configs", !binary.isNullOrEmpty())
        val links = mutableListOf(uri("ws", "path=%2Fws%3Fed%3D1024&alpn=http%2F1.1"))
        for (mode in listOf("auto", "packet-up", "stream-up", "stream-one")) {
            links.add(uri("xhttp", "mode=$mode&path=%2Fx&alpn=h2&extra=${encode("{\"xmux\":{\"maxConnections\":1}}")}"))
        }
        links.add(uri("xhttp", "alpn=h3&mode=stream-up").replace("fp=firefox", "fp=unsafe"))
        for (link in links) {
            val node = V2rayNodes.parseOne(link)!!
            val file = File.createTempFile("vless-xray-", ".json")
            var process: Process? = null
            try {
                file.writeText(JSONObject().put("outbounds", JSONArray().put(render(node))).toString())
                process = ProcessBuilder(binary!!, "run", "-test", "-config", file.absolutePath).redirectErrorStream(true).start()
                assertTrue("Xray config test timed out", process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            } finally {
                process?.destroyForcibly()
                file.delete()
            }
        }
    }
}

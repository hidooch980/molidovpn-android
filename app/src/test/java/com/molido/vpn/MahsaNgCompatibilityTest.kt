package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder

/**
 * Compatibility contract for standard share links exported by MahsaNG.
 *
 * These are synthetic documentation fixtures, not donated/private server
 * credentials. Parsing a link is not evidence that its endpoint is reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MahsaNgCompatibilityTest {
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Test fun acceptsStandardVlessRealityVisionTcpShareLink() {
        val link = "vless://$uuid@edge.example:443?encryption=none&security=reality&type=tcp&flow=xtls-rprx-vision&sni=front.example&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123456789abcdef#Mahsa-compat"
        val node = V2rayNodes.parseOne(link)
        assertNotNull(node)
        assertEquals("vless", node!!.protocol)
        assertEquals("reality", node.security)
        assertEquals("xtls-rprx-vision", node.flow)
        assertEquals("front.example", node.sni)
        assertEquals("Mahsa-compat", node.name)
    }

    @Test fun acceptsStandardVlessWebSocketTlsAndTrojanTlsLinks() {
        val vless = "vless://$uuid@edge.example:443?encryption=none&security=tls&type=ws&host=cdn.example&sni=cdn.example&path=${enc("/ws")}#mahsa-ws"
        val trojan = "trojan://fixture-password@edge.example:443?security=tls&type=ws&host=cdn.example&sni=cdn.example&path=${enc("/trojan")}#mahsa-trojan"
        val v = V2rayNodes.parseOne(vless)
        val t = V2rayNodes.parseOne(trojan)
        assertNotNull(v)
        assertNotNull(t)
        assertEquals("ws", v!!.network)
        assertEquals("tls", v.security)
        assertEquals("trojan", t!!.protocol)
        assertEquals("ws", t.network)
    }

    @Test fun acceptsStandardVmessAndShadowsocksShareLinks() {
        val vmessJson = """{"v":"2","ps":"mahsa-vmess","add":"edge.example","port":"443","id":"$uuid","aid":"0","net":"ws","type":"none","host":"cdn.example","path":"/vmess","tls":"tls","sni":"cdn.example","scy":"auto"}"""
        val vmess = "vmess://" + android.util.Base64.encodeToString(vmessJson.toByteArray(), android.util.Base64.NO_WRAP)
        val ssPayload = android.util.Base64.encodeToString("aes-256-gcm:fixture-password".toByteArray(), android.util.Base64.NO_WRAP)
        val ss = "ss://$ssPayload@edge.example:8388#mahsa-ss"
        val v = V2rayNodes.parseOne(vmess)
        val s = V2rayNodes.parseOne(ss)
        assertNotNull(v)
        assertNotNull(s)
        assertEquals("vmess", v!!.protocol)
        assertEquals("ws", v.network)
        assertEquals("shadowsocks", s!!.protocol)
        assertEquals("aes-256-gcm", s.method)
    }

    @Test fun rejectsMahsaPrivateOrProprietarySchemesWithoutAnAdapter() {
        for (link in listOf(
            "psiphon://synthetic-test-only",
            "wireguard-noise://synthetic-test-only",
            "mahsa://synthetic-test-only",
            "unknown://synthetic-test-only"
        )) {
            assertNull("Must fail closed for $link", V2rayNodes.parseOne(link))
        }
    }

    @Test fun malformedAndIncompleteStandardLinksAreRejected() {
        assertNull(V2rayNodes.parseOne("vless://missing-host"))
        assertNull(V2rayNodes.parseOne("trojan://@edge.example:443?security=tls"))
        assertNull(V2rayNodes.parseOne("ss://not-a-valid-payload"))
    }
}

package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression coverage for common NekoBox/Xray share-link exports.
 *
 * These are parser/renderer compatibility tests only. They do not claim that a
 * remote server is reachable or that an end-to-end VPN session has been tested.
 */
class NekoBoxShareCompatibilityTest {
    private val uuid = "11111111-1111-4111-8111-111111111111"

    @Test fun importsVlessRealityVisionTcpShareLink() {
        val link = "vless://$uuid@node.example:443?encryption=none&security=reality&type=tcp&flow=xtls-rprx-vision&sni=cdn.example&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123456789abcdef#NekoBox"
        val node = V2rayNodes.parseOne(link)

        assertNotNull(node)
        assertEquals("vless", node!!.protocol)
        assertEquals("reality", node.security)
        assertEquals("xtls-rprx-vision", node.flow)
        assertEquals("cdn.example", node.sni)
        assertEquals("NekoBox", node.name)

        val outbound = V2rayNodes.outbound(node, node.address, node.port, "compat")
        assertEquals("vless", outbound.getString("protocol"))
        assertEquals(uuid, outbound.getJSONObject("settings")
            .getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0).getString("id"))
        assertEquals("reality", outbound.getJSONObject("streamSettings")
            .getJSONObject("realitySettings").getString("serverName"))
    }

    @Test fun importsTrojanTlsWebSocketShareLink() {
        val link = "trojan://p%40ssword@node.example:443?security=tls&type=ws&host=cdn.example&sni=tls.example&path=%2Fgateway#Trojan"
        val node = V2rayNodes.parseOne(link)

        assertNotNull(node)
        assertEquals("trojan", node!!.protocol)
        assertEquals("p@ssword", node.credential)
        assertEquals("ws", node.network)
        assertEquals("/gateway", node.path)

        val outbound = V2rayNodes.outbound(node, node.address, node.port, "compat")
        assertEquals("trojan", outbound.getString("protocol"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("/gateway", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example", stream.getJSONObject("wsSettings").getString("host"))
    }

    @Test fun rejectsUnsupportedOrUnsafeShareLinkInsteadOfSilentlyDowngrading() {
        assertNull(V2rayNodes.parseOne("vless://$uuid@node.example:443?security=reality&type=ws&pbk=key&sni=cdn.example"))
        assertNull(V2rayNodes.parseOne("vless://$uuid@node.example:443?security=tls&type=tcp&encryption=aes-128-gcm"))
        assertNull(V2rayNodes.parseOne("trojan://password@node.example:443?security=unknown&type=tcp"))
    }
}

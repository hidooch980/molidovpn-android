package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test

class Hysteria2ImportTest {
    @Test fun shareLinkRendersAllPortsThroughExistingSidecar() {
        val uri = "hy2://user%3Ap%40ss@vpn.example:443,20000-30000,8443/?sni=tls.example&obfs=salamander&obfs-password=secret#My%20VPN"
        val node = V2rayNodes.parseOne(uri)!!
        assertEquals("hysteria2", node.protocol)
        assertEquals("user:p@ss", node.credential)
        assertEquals("My VPN", node.name)
        assertEquals(443, V2rayNodes.toShardNode(node).port)
        val outbound = SingBox.outbound(node, "test")
        assertFalse(outbound.has("server_port"))
        assertEquals(listOf("443", "20000:30000", "8443"),
            outbound.getJSONArray("server_ports").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("user:p@ss", outbound.getString("password"))
        assertEquals("secret", outbound.getJSONObject("obfs").getString("password"))
        assertEquals("tls.example", outbound.getJSONObject("tls").getString("server_name"))
        assertFalse(outbound.getJSONObject("tls").optBoolean("insecure"))
        assertFalse(V2rayNodes.edgeExpandable(node))
    }

    @Test fun ipv6RangeOnlyAndDefaultPortRoundTrip() {
        val range = V2rayNodes.parseOne("hysteria2://p@[2001:db8::1]:20000-30000")!!
        assertEquals("2001:db8::1", range.address)
        assertEquals("20000:30000", SingBox.outbound(range, "v6").getJSONArray("server_ports").getString(0))
        for (authority in listOf("vpn.example", "[2001:db8::1]", "192.0.2.1:443")) {
            val node = V2rayNodes.parseOne("hy2://p@$authority")!!
            val out = SingBox.outbound(node, "single")
            assertEquals(443, out.getInt("server_port"))
            assertFalse(out.has("server_ports"))
        }
    }

    @Test fun malformedHoppingLinkCannotPoisonValidSubscriptionNodes() {
        val nodes = V2rayNodes.parse(listOf("hy2://p@vpn.example:443,garbage", "hy2://p@vpn.example:443,0",
            "hy2://p@vpn.example:443,6000-5000", "hy2://p@vpn.example:443", "hy2://p@vpn.example:443#duplicate"))
        assertEquals(1, nodes.size)
        assertTrue(nodes.single().serverPorts.isEmpty())
    }

    @Test fun existingTuicAndAnyTlsKeepSinglePortAndTlsPolicy() {
        for (uri in listOf("tuic://id:password@vpn.example:8443?allow_insecure=1", "anytls://password@vpn.example:8443?insecure=1")) {
            val out = SingBox.outbound(V2rayNodes.parseOne(uri)!!, "old")
            assertEquals(8443, out.getInt("server_port"))
            assertFalse(out.has("server_ports"))
            assertTrue(out.getJSONObject("tls").getBoolean("insecure"))
        }
    }
}

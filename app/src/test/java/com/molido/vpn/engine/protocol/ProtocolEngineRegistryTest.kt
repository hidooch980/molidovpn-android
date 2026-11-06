package com.molido.vpn.engine.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtocolEngineRegistryTest {
    @Test fun exposesTenInitialWholeDeviceEngines() {
        val ids = ProtocolEngineRegistry.initialTen().map { it.id }.toSet()
        assertEquals(
            setOf(
                ProtocolEngineId.VLESS, ProtocolEngineId.VMESS, ProtocolEngineId.TROJAN,
                ProtocolEngineId.SHADOWSOCKS, ProtocolEngineId.HYSTERIA2, ProtocolEngineId.TUIC,
                ProtocolEngineId.ANYTLS, ProtocolEngineId.WIREGUARD, ProtocolEngineId.IKEV2,
                ProtocolEngineId.SSTP,
            ),
            ids,
        )
    }

    @Test fun mtprotoIsNotAdvertisedAsWholeDeviceVpn() {
        val engine = ProtocolEngineRegistry.find(ProtocolEngineId.MTPROTO_PROXY)!!
        assertFalse(engine.implemented)
        assertFalse(engine.wholeDeviceVpn)
        assertFalse(ProtocolEngineRegistry.isSelectable(ProtocolEngineId.MTPROTO_PROXY))
    }

    @Test fun torIsASeparateProxyRuntimeNotOneOfTheInitialTen() {
        val tor = ProtocolEngineRegistry.find(ProtocolEngineId.TOR)!!
        assertEquals(ProtocolRuntime.TOR_PROCESS, tor.runtime)
        assertFalse(tor.wholeDeviceVpn)
        assertFalse(ProtocolEngineRegistry.initialTen().any { it.id == ProtocolEngineId.TOR })
    }

    @Test fun shareSchemesResolveOnlyToExistingRuntimeFamilies() {
        assertEquals(ProtocolRuntime.XRAY, ProtocolEngineRegistry.runtimeForShareScheme("vless"))
        assertEquals(ProtocolRuntime.XRAY, ProtocolEngineRegistry.runtimeForShareScheme("ss"))
        assertEquals(ProtocolRuntime.SING_BOX, ProtocolEngineRegistry.runtimeForShareScheme("hy2"))
        assertNull(ProtocolEngineRegistry.runtimeForShareScheme("mtproto"))
        assertNull(ProtocolEngineRegistry.runtimeForShareScheme("unknown"))
    }

    @Test fun everyInitialEngineHasAnImplementedRuntime() {
        ProtocolEngineRegistry.initialTen().forEach {
            assertTrue(it.implemented)
            assertNotEquals(ProtocolRuntime.NOT_IMPLEMENTED, it.runtime)
        }
    }
}

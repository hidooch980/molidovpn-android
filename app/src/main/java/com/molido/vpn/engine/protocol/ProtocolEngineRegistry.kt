package com.molido.vpn.engine.protocol

/**
 * Stable inventory and dispatch boundary for the protocol engines shipped by Molido.
 *
 * This registry is intentionally not a tunnel launcher. A descriptor is returned only
 * when the existing parser/engine path is present; actual session creation remains in
 * the existing VPN service and engine-specific adapters. Unknown or unavailable
 * engines fail closed.
 */
enum class ProtocolEngineId {
    VLESS, VMESS, TROJAN, SHADOWSOCKS,
    HYSTERIA2, TUIC, ANYTLS,
    WIREGUARD, IKEV2, SSTP,
    TOR, MTPROTO_PROXY,
}

enum class ProtocolRuntime {
    XRAY, SING_BOX, AETHER, ANDROID_PLATFORM, TOR_PROCESS, NOT_IMPLEMENTED,
}

data class ProtocolEngineDescriptor(
    val id: ProtocolEngineId,
    val displayName: String,
    val runtime: ProtocolRuntime,
    val implemented: Boolean,
    val requiresServerSupport: Boolean = true,
    val wholeDeviceVpn: Boolean = true,
    val experimental: Boolean = false,
)

object ProtocolEngineRegistry {
    private val descriptors = listOf(
        ProtocolEngineDescriptor(ProtocolEngineId.VLESS, "VLESS", ProtocolRuntime.XRAY, true),
        ProtocolEngineDescriptor(ProtocolEngineId.VMESS, "VMess", ProtocolRuntime.XRAY, true),
        ProtocolEngineDescriptor(ProtocolEngineId.TROJAN, "Trojan", ProtocolRuntime.XRAY, true),
        ProtocolEngineDescriptor(ProtocolEngineId.SHADOWSOCKS, "Shadowsocks", ProtocolRuntime.XRAY, true),
        ProtocolEngineDescriptor(ProtocolEngineId.HYSTERIA2, "Hysteria 2", ProtocolRuntime.SING_BOX, true),
        ProtocolEngineDescriptor(ProtocolEngineId.TUIC, "TUIC", ProtocolRuntime.SING_BOX, true),
        ProtocolEngineDescriptor(ProtocolEngineId.ANYTLS, "AnyTLS", ProtocolRuntime.SING_BOX, true),
        ProtocolEngineDescriptor(ProtocolEngineId.WIREGUARD, "WireGuard", ProtocolRuntime.AETHER, true),
        ProtocolEngineDescriptor(ProtocolEngineId.IKEV2, "IKEv2", ProtocolRuntime.ANDROID_PLATFORM, true),
        ProtocolEngineDescriptor(ProtocolEngineId.SSTP, "SSTP", ProtocolRuntime.ANDROID_PLATFORM, true),
        ProtocolEngineDescriptor(
            ProtocolEngineId.TOR, "Tor", ProtocolRuntime.TOR_PROCESS, true,
            wholeDeviceVpn = false, experimental = true,
        ),
        ProtocolEngineDescriptor(
            ProtocolEngineId.MTPROTO_PROXY, "MTProto Proxy", ProtocolRuntime.NOT_IMPLEMENTED,
            implemented = false, wholeDeviceVpn = false,
        ),
    )

    private val byId = descriptors.associateBy { it.id }

    fun all(): List<ProtocolEngineDescriptor> = descriptors.toList()

    fun find(id: ProtocolEngineId): ProtocolEngineDescriptor? = byId[id]

    /** Only the ten initial whole-device/profile engines are eligible for selection. */
    fun initialTen(): List<ProtocolEngineDescriptor> =
        descriptors.filter { it.wholeDeviceVpn && it.implemented && it.id != ProtocolEngineId.TOR }

    fun isSelectable(id: ProtocolEngineId): Boolean =
        byId[id]?.let { it.implemented && it.runtime != ProtocolRuntime.NOT_IMPLEMENTED } == true

    fun runtimeForShareScheme(scheme: String): ProtocolRuntime? = when (scheme.lowercase()) {
        "vless", "vmess", "trojan", "ss" -> ProtocolRuntime.XRAY
        "hysteria2", "hy2", "tuic", "anytls" -> ProtocolRuntime.SING_BOX
        else -> null
    }
}

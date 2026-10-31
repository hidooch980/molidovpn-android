package com.molido.vpn.methods

import android.content.Context
import com.molido.vpn.AmneziaConfig
import com.molido.vpn.CoreConfig
import com.molido.vpn.MolidoVpnService
import com.molido.vpn.MyConfigs
import com.molido.vpn.ResilienceConfig
import com.molido.vpn.ShardSubscription
import com.molido.vpn.V2raySubscription
import com.molido.vpn.engine.Candidate
import com.molido.vpn.engine.Cost

/**
 * Every method Smart Connect may use, in the default (pre-measurement) order. This replaces
 * the two hard-coded Automatic phase lists; filtering (owner switches, chosen country, UDP
 * blocked, proxy-only mode) stays in the engine because it depends on the session.
 */
object MethodRegistry {

    /** Methods whose exit is a WARP account (an Iranian user's exit is then IR). */
    const val V2RAY_FRAGMENT = "v2ray-frag"
    val WARP_FAMILY = setOf("wireguard", AmneziaConfig.PROTOCOL, "masque", "gool")

    /** UDP transports: useless when UDP is blocked on this network. */
    val UDP = setOf("wireguard", AmneziaConfig.PROTOCOL, "masque")

    fun all(context: Context): List<TunnelMethod> {
        val proxy = CoreConfig.proxyOnly(context)
        val amneziaEndpoints = (if (AmneziaConfig.isImported(context)) AmneziaConfig.endpointCount(context)
            else AmneziaConfig.BUILTIN_ENDPOINTS.size).coerceIn(1, 4)
        val v2rayCount = V2raySubscription.cachedCount(context)
        return listOfNotNull(
            PlainMethod(Candidate("wireguard", "wireguard", "WARP", Cost.CHEAP, 10_000L)),
            PlainMethod(Candidate(AmneziaConfig.PROTOCOL, AmneziaConfig.PROTOCOL, "AmneziaWG", Cost.CHEAP, 6_000L + 8_000L * amneziaEndpoints)),
            PlainMethod(Candidate("masque", "masque", "MASQUE", Cost.CHEAP, if (CoreConfig.mimArmed(context)) 20_000L else 12_000L)),
            if (proxy) null else HandshakeMethod(Candidate("shard", "shard", "SHARD", Cost.CHEAP, 16_000L)) { ctx ->
                if (ShardSubscription.cachedCount(ctx) <= 0) emptyList()
                else ShardSubscription.nodes(ctx).take(8).map { NetProbe.Target(it.address, it.port) }
            },
            // V2Ray pool: direct (Reality/TCP), CDN (ws/grpc/xhttp behind a CDN) and Hysteria2/TUIC
            // via sing-box. ShardManager races the servers in parallel inside the trial; this probe
            // only checks that some of them answer a TCP handshake from this network.
            if (proxy) null else HandshakeMethod(Candidate("v2ray", "v2ray", "V2Ray", Cost.CHEAP, 16_000L)) { ctx ->
                if (v2rayCount <= 0) emptyList()
                else V2raySubscription.nodes(ctx)
                    .filter { it.protocol !in setOf("hysteria2", "tuic") }
                    .take(8).map { NetProbe.Target(it.address, it.port) }
            },
            // MahsaNG-style: the same pool with the TLS ClientHello fragmented on the dial. A separate
            // method so fast unfragmented routes are never slowed; remote "v2ray_fragment".
            if (proxy || v2rayCount <= 0 || com.molido.vpn.ResilienceConfig.get(context).v2rayFragment == null) null
            else PlainMethod(Candidate(V2RAY_FRAGMENT, "v2ray", "V2Ray + fragment", Cost.CHEAP, 16_000L)),
            if (proxy || !MyConfigs.hasProxyNodes(context)) null
            else PlainMethod(Candidate(MyConfigs.PROTOCOL, MyConfigs.PROTOCOL, "My configs", Cost.CHEAP, 16_000L)),
            PlainMethod(Candidate("gool", "gool", "WARP-on-WARP", Cost.CHEAP, 30_000L)),
            PlainMethod(Candidate(MolidoVpnService.CHAIN_PROTOCOL_MARKER.lowercase(), MolidoVpnService.CHAIN_PROTOCOL_MARKER.lowercase(),
                "Psiphon over WARP", Cost.EXPENSIVE, 40_000L)),
            if (proxy || v2rayCount <= 0) null
            else PlainMethod(Candidate(MolidoVpnService.V2RAY_PSIPHON_PROTOCOL, MolidoVpnService.V2RAY_PSIPHON_PROTOCOL,
                "V2Ray over Psiphon", Cost.EXPENSIVE, 90_000L)),
            PlainMethod(Candidate("psiphon", "psiphon", "Psiphon + Conduit", Cost.EXPENSIVE, 45_000L)),
            // Tor: the trial runs its own ladder (Direct → WebTunnel → Snowflake → Meek → obfs4).
            // Pre-probe: TLS to the CDN fronts that Meek and Snowflake ride and to Tor's Moat API.
            if (proxy) null else HandshakeMethod(
                Candidate("tor", "tor", "Tor", Cost.EXPENSIVE, ResilienceConfig.tor(context).autoBudgetS * 1_000L),
            ) { _ ->
                listOf(
                    NetProbe.Target("www.phpmyadmin.net", 443, "www.phpmyadmin.net"),
                    NetProbe.Target("www.cdn77.com", 443, "www.cdn77.com"),
                    NetProbe.Target("bridges.torproject.org", 443, "bridges.torproject.org"),
                )
            },
        )
    }
}

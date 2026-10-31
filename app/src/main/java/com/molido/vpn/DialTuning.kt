package com.molido.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-session dial tweaks applied to rendered V2Ray outbounds.
 *
 *  - **Address family** ([family]): Smart Connect probes each server over IPv4 and IPv6 and
 *    remembers the faster family per network. "v6" dials IPv6 first with IPv4 fallback
 *    (`UseIPv6v4`); "v4" dials IPv4 only (`UseIPv4`), which also stops stalls on AAAA records
 *    where the phone has no working IPv6. null leaves the core's default.
 *  - **TLS fragment** ([fragment]): the MahsaNG-style ClientHello fragmentation on the dial to the
 *    server (xray `finalmask.tcp` fragment mask), for TLS servers only (never REALITY: its
 *    handshake must look untouched, and never plain TCP). Used by Smart Connect's "V2Ray +
 *    fragment" method, so fast unfragmented routes are not slowed down.
 */
object DialTuning {
    @Volatile var family: String? = null
    @Volatile var fragment: Boolean = false

    data class Fragment(
        val packets: String = "tlshello",
        val lengths: List<String> = listOf("10-20"),
        val delays: List<String> = listOf("10-20"),
    )

    @Volatile var fragmentSettings: Fragment = Fragment()

    fun domainStrategy(family: String?): String? = when (family) {
        "v6" -> "UseIPv6v4"
        "v4" -> "UseIPv4"
        else -> null
    }

    fun fragmentMask(f: Fragment): JSONObject = JSONObject().put("tcp", JSONArray().put(
        JSONObject().put("type", "fragment").put("settings", JSONObject().apply {
            put("packets", f.packets)
            put("lengths", JSONArray(f.lengths))
            put("delays", JSONArray(f.delays))
        })
    ))

    /** Applies the session tweaks to [outbound] in place. Pure apart from the volatile inputs. */
    const val KEEPALIVE_IDLE_S = 30
    const val KEEPALIVE_INTERVAL_S = 15

    fun apply(outbound: JSONObject, family: String? = this.family, fragment: Boolean = this.fragment,
              settings: Fragment = fragmentSettings, keepAlive: Boolean = true): JSONObject {
        val proto = outbound.optString("protocol")
        if (proto == "socks" || proto == "freedom" || proto == "blackhole") return outbound
        val stream = outbound.optJSONObject("streamSettings")
        domainStrategy(family)?.let { ds ->
            val s = stream ?: JSONObject().also { outbound.put("streamSettings", it) }
            val sockopt = s.optJSONObject("sockopt") ?: JSONObject().also { s.put("sockopt", it) }
            if (!sockopt.has("dialerProxy") && !sockopt.has("domainStrategy")) sockopt.put("domainStrategy", ds)
        }
        // Stability: carrier NATs drop idle TCP after a few minutes, which surfaces as a stall on the
        // next request. Kernel keepalive (30 s idle, 15 s interval) keeps the mapping without app traffic.
        if (keepAlive && stream != null && stream.optString("network", "tcp") !in setOf("kcp", "quic", "hysteria")) {
            val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            if (!sockopt.has("dialerProxy") && !sockopt.has("tcpKeepAliveIdle")) {
                sockopt.put("tcpKeepAliveIdle", KEEPALIVE_IDLE_S).put("tcpKeepAliveInterval", KEEPALIVE_INTERVAL_S)
            }
        }
        if (fragment && stream != null && stream.optString("security") == "tls" &&
            stream.optString("network", "tcp") != "kcp" && !stream.has("finalmask")
        ) {
            stream.put("finalmask", fragmentMask(settings))
        }
        return outbound
    }

    /** Winning family from per-family handshake times; v6 only when clearly not worse (≤ 1.1×). */
    fun pickFamily(v4Ms: Long?, v6Ms: Long?): String? = when {
        v4Ms == null && v6Ms == null -> null
        v6Ms == null -> "v4"
        v4Ms == null -> "v6"
        v6Ms <= v4Ms * 1.1 -> "v6"
        else -> "v4"
    }
}

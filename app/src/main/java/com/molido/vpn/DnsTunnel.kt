package com.molido.vpn

import android.content.Context
import java.io.File

/**
 * DNS tunnel (dnstt) — last-resort transport for a national-internet shutdown in which only DNS
 * still leaves the country. **Plumbing only in this build, gated off.**
 *
 * What exists now:
 *  * settings from `remote/resilience.json` → `dns_tunnel` ([ResilienceConfig.DnsTunnelSettings]):
 *    domain, server public key, resolvers (domestic UDP, DoH, DoT, the phone's own DNS) — changeable
 *    without a release; `enabled` is false by default;
 *  * [candidates]: the order resolvers would be tried in, and [clientArgs]: the exact dnstt-client
 *    command line for each (both unit tested);
 *  * `tools/build-dnstt.sh`: builds the pinned dnstt client as `jniLibs/<abi>/libdnstt.so`
 *    (verified locally for arm64-v8a and armeabi-v7a, ~9 MB each). NOT run by CI yet, so release
 *    APKs do not contain it and [binaryPresent] is false there.
 *
 * TODO (next PR, once the owner has a dnstt server — see docs/DNS-TUNNEL-SETUP.md):
 *  1. call tools/build-dnstt.sh in both release workflows (+~4 MB per APK, compressed);
 *  2. MolidoVpnService.startDnsTunnel(): TUN → `libdnstt.so <clientArgs> 127.0.0.1:[LOCAL_PORT]` per
 *     candidate until a SOCKS probe passes → TorSocksFront(listen, LOCAL_PORT, dns relay) →
 *     Tun2SocksManager(dnsOnlyUdpgw = true); a small UDP→TCP DNS relay through the tunnel; teardown
 *     and watchdog branches next to Tor's;
 *  3. Auto: `AutoCandidate("dnstt", ...)` at the end of phase B when [usable] && settings.inAuto;
 *     a "dnstt" key in [AppRemote] so the panel can switch it off; a mode-page entry.
 */
object DnsTunnel {

    const val PROTOCOL = "dnstt"
    const val BINARY = "libdnstt.so"
    /** Local end of the tunnel; free in the app's port plan (1819–1825 are taken). */
    const val LOCAL_PORT = 1826

    enum class Kind { DOH, DOT, UDP }

    data class Candidate(val kind: Kind, val address: String)

    fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)

    fun binaryPresent(context: Context): Boolean = binary(context).exists()

    /** Turned on remotely, fully configured, and the client is in this APK. */
    fun usable(context: Context): Boolean = ResilienceConfig.dnsTunnel(context).usable && binaryPresent(context)

    /**
     * DoH/DoT first (encrypted, works while foreign resolvers are still reachable), then the
     * phone's own DNS servers (what keeps working inside a national-only network), then the
     * configured domestic UDP resolvers. Deduped, order kept.
     */
    fun candidates(settings: ResilienceConfig.DnsTunnelSettings, systemDns: List<String>): List<Candidate> {
        val out = LinkedHashSet<Candidate>()
        settings.dohUrls.forEach { out.add(Candidate(Kind.DOH, it)) }
        settings.dotAddrs.forEach { out.add(Candidate(Kind.DOT, it)) }
        if (settings.useSystemDns) systemDns.mapNotNull(ResilienceConfig::resolver).forEach { out.add(Candidate(Kind.UDP, it)) }
        settings.udpResolvers.forEach { out.add(Candidate(Kind.UDP, it)) }
        return out.toList()
    }

    fun clientArgs(candidate: Candidate, settings: ResilienceConfig.DnsTunnelSettings, localPort: Int = LOCAL_PORT): List<String> =
        listOf(
            when (candidate.kind) {
                Kind.DOH -> "-doh"
                Kind.DOT -> "-dot"
                Kind.UDP -> "-udp"
            },
            candidate.address,
            "-pubkey", settings.pubkey,
            settings.domain,
            "127.0.0.1:$localPort",
        )
}

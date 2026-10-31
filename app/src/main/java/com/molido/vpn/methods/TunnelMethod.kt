package com.molido.vpn.methods

import android.content.Context
import com.molido.vpn.engine.Candidate
import com.molido.vpn.engine.Probe

/**
 * One way out (a "method plugin"). Every transport the app has is described by one of these:
 * what the service starts ([Candidate.coreName]), how costly it is to test, and — when it can be
 * judged without bringing a tunnel up — a cheap out-of-tunnel [preProbe] that Smart Connect runs
 * for all methods in parallel before the exclusive trials.
 *
 * Bringing a tunnel up is NOT part of this interface: Android allows one VPN TUN per app and the
 * native core is a single instance, so real trials are serialised by the connection engine
 * ([com.molido.vpn.MolidoVpnService] Smart Connect) and only probes run concurrently.
 */
interface TunnelMethod {
    val candidate: Candidate

    val id: String get() = candidate.id

    /**
     * Out-of-tunnel reachability probe, blocking, bounded by [timeoutMs]. Null = this method
     * cannot be judged without a tunnel (UDP transports, Psiphon's own protocol ladder).
     * A failed probe only demotes the method in the trial order; it never removes it.
     */
    fun preProbe(context: Context, timeoutMs: Int): Probe? = null
}

/** A method with no meaningful pre-probe. */
class PlainMethod(override val candidate: Candidate) : TunnelMethod

/** A method whose pre-probe is a parallel TCP/TLS handshake to [targets]. */
class HandshakeMethod(
    override val candidate: Candidate,
    private val targets: (Context) -> List<NetProbe.Target>,
) : TunnelMethod {
    override fun preProbe(context: Context, timeoutMs: Int): Probe? {
        val list = targets(context)
        if (list.isEmpty()) return null
        return NetProbe.bestDual(list, timeoutMs, com.molido.vpn.NetworkV6.hasGlobalIpv6(context))
    }
}

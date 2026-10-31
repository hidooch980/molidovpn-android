package com.molido.vpn.methods

import com.molido.vpn.engine.Probe
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Small, bounded TCP / TLS handshake probes used by method pre-probes. */
object NetProbe {

    data class Target(val host: String, val port: Int, val tlsSni: String? = null)

    /** Handshake time in ms, or null. TLS when [Target.tlsSni] is set (certificate not checked: reachability only). */
    fun handshake(t: Target, timeoutMs: Int): Long? {
        val started = System.nanoTime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(t.host, t.port), timeoutMs)
                s.soTimeout = timeoutMs
                if (t.tlsSni != null) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket(s, t.host, t.port, false) as SSLSocket).use { tls ->
                        tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(t.tlsSni)) }
                        tls.soTimeout = timeoutMs
                        tls.startHandshake()
                    }
                }
            }
            (System.nanoTime() - started) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Dual-stack: resolves A and AAAA for each target and probes one address of each family
     * ([v6] false skips IPv6, e.g. no global IPv6 on this network). The probe's latency is the
     * fastest answer; [Probe.family] is the family that won ([com.molido.vpn.DialTuning.pickFamily]).
     */
    fun bestDual(targets: List<Target>, timeoutMs: Int, v6: Boolean): Probe {
        val list = targets.take(8)
        val jobs = ArrayList<Pair<String, Target>>()
        for (t in list) {
            val addrs = runCatching { java.net.InetAddress.getAllByName(t.host).toList() }.getOrDefault(emptyList())
            addrs.firstOrNull { it is java.net.Inet4Address }?.let { jobs += "v4" to t.copy(host = it.hostAddress ?: t.host) }
            if (v6) addrs.firstOrNull { it is java.net.Inet6Address }?.let { jobs += "v6" to t.copy(host = it.hostAddress ?: t.host) }
        }
        if (jobs.isEmpty()) return best(list, timeoutMs)
        val pool = Executors.newFixedThreadPool(jobs.size.coerceIn(1, 12))
        return try {
            val futures = jobs.map { (fam, t) -> fam to pool.submit(Callable { handshake(t, timeoutMs) }) }
            val times = futures.mapNotNull { (fam, f) ->
                runCatching { f.get(timeoutMs * 2L + 500, TimeUnit.MILLISECONDS) }.getOrNull()?.let { fam to it }
            }
            val v4 = times.filter { it.first == "v4" }.minOfOrNull { it.second }
            val v6ms = times.filter { it.first == "v6" }.minOfOrNull { it.second }
            Probe(times.isNotEmpty(), times.minOfOrNull { it.second },
                family = if (v6) com.molido.vpn.DialTuning.pickFamily(v4, v6ms) else v4?.let { "v4" })
        } finally {
            pool.shutdownNow()
        }
    }

    /** Probes up to 8 targets in parallel; ok when any answered, latency = fastest. */
    fun best(targets: List<Target>, timeoutMs: Int): Probe {
        val list = targets.take(8)
        val pool = Executors.newFixedThreadPool(list.size.coerceIn(1, 8))
        return try {
            val futures = list.map { t -> pool.submit(Callable { handshake(t, timeoutMs) }) }
            val times = futures.mapNotNull { f ->
                runCatching { f.get(timeoutMs * 2L + 500, TimeUnit.MILLISECONDS) }.getOrNull()
            }
            val ok = times.isNotEmpty()
            Probe(ok, times.minOrNull())
        } finally {
            pool.shutdownNow()
        }
    }
}

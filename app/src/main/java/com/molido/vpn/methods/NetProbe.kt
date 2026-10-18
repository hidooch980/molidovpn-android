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

package com.molido.vpn

import android.content.Context
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * Settings > Speed test: 10 MB download and 5 MB upload against
 * speed.cloudflare.com, plus an HTTP round-trip estimate (median of 3 requests).
 *
 * The app UID is intentionally excluded from Android's TUN to prevent routing
 * loops. Therefore this test MUST use a verified local tunnel SOCKS listener.
 * Native TUN modes without such a listener are reported as unsupported instead
 * of silently measuring the carrier connection.
 */
object SpeedTest {

    private const val DOWN_BYTES = 10_000_000
    private const val UP_BYTES = 5_000_000
    private const val BASE = "https://speed.cloudflare.com"

    data class Result(
        val pingMs: Long?,
        val downMbps: Double?,
        val upMbps: Double?,
        val viaTunnel: Boolean,
        val error: String? = null,
    )

    private fun accepts(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (_: Exception) {
        false
    }

    /** Returns only a local listener that is accepting connections now. */
    private fun socksPort(context: Context): Int? {
        if (!TunnelStatus.isActive()) return null
        val candidate = when {
            ShardManager.isRunning -> ShardManager.listenPort
            TunnelStatus.isProxyMode -> CoreConfig.proxyListenPort(context)
            TunnelStatus.isNativeTunMode -> return null
            else -> listOf(CoreConfig.SOCKS_PORT, TorManager.FRONT_SOCKS_PORT)
                .firstOrNull { accepts(it) } ?: return null
        }
        return candidate.takeIf { accepts(it) }
    }

    /** Blocking; call off the main thread. Never falls back to the carrier link. */
    fun run(context: Context): Result {
        if (!TunnelStatus.isActive()) {
            return Result(null, null, null, false, "VPN_NOT_CONNECTED")
        }
        val port = socksPort(context) ?: return Result(
            null, null, null, false,
            if (TunnelStatus.isNativeTunMode) "TUNNEL_MEASUREMENT_UNAVAILABLE"
            else "TUNNEL_PROXY_UNAVAILABLE",
        )
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

        val pings = (0 until 3).mapNotNull { timed(proxy, "$BASE/__down?bytes=0") }
        val ping = pings.sorted().getOrNull(pings.size / 2).takeIf { pings.size >= 2 }
        val down = try { download(proxy) } catch (_: Exception) { null }
        val up = try { upload(proxy) } catch (_: Exception) { null }
        val error = when {
            down == null && up == null -> "DOWNLOAD_AND_UPLOAD_FAILED"
            down == null -> "DOWNLOAD_FAILED"
            up == null -> "UPLOAD_FAILED"
            ping == null -> "PING_UNAVAILABLE"
            else -> null
        }
        return Result(ping, down, up, true, error)
    }

    private fun open(proxy: Proxy, url: String): HttpURLConnection =
        (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            useCaches = false
            instanceFollowRedirects = false
        }

    internal fun isSuccessfulHttpStatus(status: Int): Boolean = status in 200..299

    internal fun calculateMbps(bytes: Long, elapsedMs: Long): Double? =
        if (bytes <= 0L || elapsedMs <= 0L) null
        else bytes * 8.0 / 1_000_000.0 / (elapsedMs / 1000.0)

    private fun timed(proxy: Proxy, url: String): Long? = try {
        val started = SystemClock.elapsedRealtime()
        val connection = open(proxy, url)
        try {
            if (!isSuccessfulHttpStatus(connection.responseCode)) return null
            connection.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (input.read(buffer) >= 0) Unit
            }
            (SystemClock.elapsedRealtime() - started).takeIf { it > 0L }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun download(proxy: Proxy): Double? {
        val connection = open(proxy, "$BASE/__down?bytes=$DOWN_BYTES")
        try {
            if (!isSuccessfulHttpStatus(connection.responseCode)) return null
            val started = SystemClock.elapsedRealtime()
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > DOWN_BYTES) return null
                }
            }
            // A truncated or oversized response is not a valid speed sample.
            if (total != DOWN_BYTES.toLong()) return null
            return calculateMbps(total, SystemClock.elapsedRealtime() - started)
        } finally {
            connection.disconnect()
        }
    }

    private fun upload(proxy: Proxy): Double? {
        val connection = open(proxy, "$BASE/__up")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(UP_BYTES)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            val chunk = ByteArray(64 * 1024)
            val started = SystemClock.elapsedRealtime()
            connection.outputStream.use { output ->
                var remaining = UP_BYTES
                while (remaining > 0) {
                    val count = minOf(remaining, chunk.size)
                    output.write(chunk, 0, count)
                    remaining -= count
                }
            }
            if (!isSuccessfulHttpStatus(connection.responseCode)) return null
            return calculateMbps(UP_BYTES.toLong(), SystemClock.elapsedRealtime() - started)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Small download through a local SOCKS port, for Smart Connect's throughput figure.
     * Returns kbit/s, or null on failure. Blocking, bounded by [timeoutMs].
     */
    fun quickDownloadKbps(port: Int, bytes: Int, timeoutMs: Int): Long? {
        if (port !in 1..65535 || bytes < 4_096 || timeoutMs <= 0 || !accepts(port)) return null
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val connection = URL("$BASE/__down?bytes=$bytes").openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            val started = System.nanoTime()
            var total = 0L
            try {
                if (!isSuccessfulHttpStatus(connection.responseCode)) return null
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if ((System.nanoTime() - started) / 1_000_000 > timeoutMs) break
                    }
                }
            } finally {
                connection.disconnect()
            }
            val elapsedMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            if (total < 4_096) null else total * 8 / elapsedMs
        } catch (_: Exception) {
            null
        }
    }
}

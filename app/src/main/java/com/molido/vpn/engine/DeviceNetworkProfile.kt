package com.molido.vpn.engine

import org.json.JSONObject
import kotlin.math.abs

/** Local-only physical-link context and quality observations; contains no raw identifiers or traffic content. */
object DeviceNetworkProfile {
    enum class Transport { WIFI, CELLULAR, ETHERNET, OTHER }

    data class Link(
        val transport: Transport, val validated: Boolean, val metered: Boolean, val roaming: Boolean,
        val downstreamKbps: Int, val upstreamKbps: Int, val hasDefaultRoute: Boolean,
    )
    data class Candidate(val link: Link, val isActive: Boolean)
    data class Policy(val parallelProbes: Int, val downloadBytes: Int)
    data class Observation(
        val rttMedianMs: Long?, val jitterMedianMs: Long?, val packetLoss: Double?,
        val throughputMedianKbps: Long?, val successfulProbes: Int, val totalProbes: Int, val observedAtMs: Long,
    )
    data class Profile(
        val transport: Transport, val validated: Boolean, val metered: Boolean, val roaming: Boolean,
        val downstreamHintKbps: Int, val upstreamHintKbps: Int,
        val rttMedianMs: Long?, val jitterMedianMs: Long?, val packetLoss: Double?,
        val throughputMedianKbps: Long?, val successfulProbes: Int, val totalProbes: Int, val updatedAtMs: Long,
    )

    /** allNetworks ordering is unspecified: prefer active, validated, default-route physical links. */
    fun choose(candidates: List<Candidate>): Link? = candidates
        .filter { it.link.hasDefaultRoute || it.isActive }
        .maxWithOrNull(compareBy<Candidate> { if (it.link.validated) 1 else 0 }
            .thenBy { if (it.link.hasDefaultRoute) 1 else 0 }
            .thenBy { if (it.isActive) 1 else 0 }
            .thenBy { if (it.link.transport == Transport.OTHER) 0 else 1 })
        ?.link ?: candidates.maxWithOrNull(compareBy<Candidate> { if (it.link.validated) 1 else 0 }
            .thenBy { if (it.link.hasDefaultRoute) 1 else 0 })?.link

    /** Conservative caps for current link state and recent local quality history. */
    fun policy(
        link: Link?,
        configuredParallel: Int,
        configuredDownloadBytes: Int,
        history: Profile? = null,
        now: Long = System.currentTimeMillis(),
    ): Policy {
        val parallel = configuredParallel.coerceIn(1, 16)
        val download = configuredDownloadBytes.coerceIn(0, 4_000_000)
        if (link == null) return Policy(parallel.coerceAtMost(3), download.coerceAtMost(512_000))
        if (!link.validated) return Policy(1, 0)
        if (link.metered || link.roaming) return Policy(parallel.coerceAtMost(2), 0)
        if (link.downstreamKbps in 1..1_500) return Policy(parallel.coerceAtMost(2), download.coerceAtMost(256_000))
        if (link.downstreamKbps == 0) return Policy(parallel.coerceAtMost(3), download.coerceAtMost(512_000))
        val freshHistory = history?.takeIf {
            it.totalProbes >= 3 && it.updatedAtMs > 0L &&
                now >= it.updatedAtMs && now - it.updatedAtMs <= 7L * 86_400_000L
        }
        if (freshHistory?.packetLoss?.let { it >= 0.20 } == true) {
            return Policy(parallel.coerceAtMost(2), download.coerceAtMost(256_000))
        }
        if (freshHistory?.rttMedianMs?.let { it >= 900L } == true) {
            return Policy(parallel.coerceAtMost(3), download.coerceAtMost(512_000))
        }
        if (freshHistory?.throughputMedianKbps?.let { it <= 1_000L } == true) {
            return Policy(parallel.coerceAtMost(2), download.coerceAtMost(256_000))
        }
        return Policy(parallel.coerceAtMost(6), download)
    }

    /** Confidence is zero for stale/empty data and rises with fresh successful samples. */
    fun confidence(profile: Profile?, now: Long, ttlMs: Long = 7L * 86_400_000L): Double {
        if (profile == null || profile.totalProbes <= 0 || profile.updatedAtMs <= 0L ||
            now < profile.updatedAtMs || now - profile.updatedAtMs > ttlMs
        ) return 0.0
        val sampleFactor = (profile.totalProbes / 9.0).coerceIn(0.0, 1.0)
        val freshness = (1.0 - (now - profile.updatedAtMs).toDouble() / ttlMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        return (sampleFactor * freshness).coerceIn(0.0, 1.0)
    }

    fun observe(probes: List<Probe>, now: Long): Observation {
        val ok = probes.filter { it.ok }
        val rtt = ok.mapNotNull { it.latencyMs?.takeIf { v -> v > 0 } }
        val rates = ok.mapNotNull { it.throughputKbps?.takeIf { v -> v > 0 } }
        val jitter = rtt.zipWithNext { a, b -> abs(b - a) }
        return Observation(
            SmartScore.median(rtt), SmartScore.median(jitter),
            if (probes.isEmpty()) null else (probes.size - ok.size).toDouble() / probes.size,
            SmartScore.median(rates), ok.size, probes.size, now.coerceAtLeast(0L),
        )
    }

    /** Bounded EWMA; absent measurements never erase known values. */
    fun merge(previous: Profile?, observation: Observation, link: Link?, now: Long): Profile {
        val alpha = 0.35
        fun blend(old: Long?, fresh: Long?): Long? = when {
            fresh == null || fresh <= 0 -> old
            old == null || old <= 0 -> fresh
            else -> (old * (1.0 - alpha) + fresh * alpha).toLong().coerceAtLeast(1L)
        }
        fun blendRate(old: Double?, fresh: Double?): Double? = when {
            fresh == null || !fresh.isFinite() -> old
            old == null || !old.isFinite() -> fresh.coerceIn(0.0, 1.0)
            else -> (old * (1.0 - alpha) + fresh * alpha).coerceIn(0.0, 1.0)
        }
        return Profile(
            link?.transport ?: previous?.transport ?: Transport.OTHER,
            link?.validated ?: previous?.validated ?: false,
            link?.metered ?: previous?.metered ?: true,
            link?.roaming ?: previous?.roaming ?: false,
            link?.downstreamKbps?.coerceAtLeast(0) ?: previous?.downstreamHintKbps ?: 0,
            link?.upstreamKbps?.coerceAtLeast(0) ?: previous?.upstreamHintKbps ?: 0,
            blend(previous?.rttMedianMs, observation.rttMedianMs),
            blend(previous?.jitterMedianMs, observation.jitterMedianMs),
            blendRate(previous?.packetLoss, observation.packetLoss),
            blend(previous?.throughputMedianKbps, observation.throughputMedianKbps),
            observation.successfulProbes.coerceAtLeast(0), observation.totalProbes.coerceAtLeast(0),
            now.coerceAtLeast(observation.observedAtMs).coerceAtLeast(0L),
        )
    }

    fun toJson(p: Profile): String = JSONObject()
        .put("transport", p.transport.name).put("validated", p.validated).put("metered", p.metered)
        .put("roaming", p.roaming).put("downstream_hint_kbps", p.downstreamHintKbps)
        .put("upstream_hint_kbps", p.upstreamHintKbps).put("rtt_median_ms", p.rttMedianMs ?: JSONObject.NULL)
        .put("jitter_median_ms", p.jitterMedianMs ?: JSONObject.NULL).put("packet_loss", p.packetLoss ?: JSONObject.NULL)
        .put("throughput_median_kbps", p.throughputMedianKbps ?: JSONObject.NULL)
        .put("successful_probes", p.successfulProbes).put("total_probes", p.totalProbes)
        .put("updated_at_ms", p.updatedAtMs).toString()

    fun parse(raw: String?, now: Long = System.currentTimeMillis(), ttlMs: Long = 7L * 86_400_000L): Profile? = try {
        val o = JSONObject(raw ?: return null)
        val transport = runCatching { Transport.valueOf(o.optString("transport")) }.getOrNull() ?: return null
        val updated = o.optLong("updated_at_ms", 0L)
        if (updated <= 0L || updated > now + 60_000L || now - updated > ttlMs.coerceAtLeast(0L)) return null
        fun nullableLong(k: String): Long? = if (o.isNull(k)) null else o.optLong(k).takeIf { it > 0L }
        val loss = if (o.isNull("packet_loss")) null else o.optDouble("packet_loss")
            .takeIf { it.isFinite() && it in 0.0..1.0 }
        Profile(
            transport, o.optBoolean("validated", false), o.optBoolean("metered", true),
            o.optBoolean("roaming", false), o.optInt("downstream_hint_kbps", 0).coerceAtLeast(0),
            o.optInt("upstream_hint_kbps", 0).coerceAtLeast(0), nullableLong("rtt_median_ms"),
            nullableLong("jitter_median_ms"), loss, nullableLong("throughput_median_kbps"),
            o.optInt("successful_probes", 0).coerceAtLeast(0), o.optInt("total_probes", 0).coerceAtLeast(0), updated,
        )
    } catch (_: Exception) { null }
}

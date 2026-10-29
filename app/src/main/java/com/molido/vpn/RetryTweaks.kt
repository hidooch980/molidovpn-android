package com.molido.vpn

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Retry-only outbound rewrites for SHARD / V2Ray nodes.
 *
 * A node's FIRST attempts are always rendered exactly as the subscription says.
 * Only after [FINGERPRINT_FROM] consecutive failures of the same node on the same
 * network does its outbound change:
 *
 *  - uTLS fingerprint rotates chrome -> firefox -> safari -> randomized, and
 *  - from the [EARLY_DATA_FROM]th failure, ws early data (`?ed=2048`) is added.
 *
 * Nodes whose TLS shape is interlocked with a specific fingerprint are never
 * rotated: SHARD nodes carrying `cs`/`fm` or `fp=unsafe` (see [ShardNode]'s doc —
 * the fragment lengths only line up with that exact ClientHello). Early data is
 * only for V2Ray nodes that are not on SHARD-style Cloudflare workers.
 * Imported VLESS XHTTP/WS profiles are never rewritten, even after failures:
 * their fingerprint and early-data/path choices belong to the publisher.
 *
 * Memory only, keyed by the physical network and node. A successful candidate
 * keeps the EXACT settings it probed with through the live launch; a verified
 * profile can order later attempts on this network for six hours. Failures
 * invalidate learned profiles. No Host, SNI, certificate or protocol changes.
 */
object RetryTweaks {

    private const val FINGERPRINT_FROM = 2
    private const val EARLY_DATA_FROM = 3
    private val ROTATION = listOf("chrome", "firefox", "safari", "randomized")

    data class Profile(val fingerprint: String? = null, val path: String? = null, val scope: String? = null)
    private data class State(val failures: Int, val at: Long, val learned: Profile? = null)
    private val states = ConcurrentHashMap<String, State>()
    private const val MAX_ENTRIES = 256
    private const val SUCCESS_TTL_MS = 6 * 60 * 60 * 1000L
    private const val FAILURE_TTL_MS = 10 * 60 * 1000L

    private fun id(context: Context, node: ShardNode): String =
        SmartSplit.networkKey(context) + "#" + node.key

    fun recordFailure(context: Context, node: ShardNode) = failed(id(context, node), node)

    fun recordSuccess(context: Context, node: ShardNode) = succeeded(id(context, node), node)

    /** Capture before rendering. Health feedback must not change the live launch. */
    fun prepare(context: Context, node: ShardNode): ShardNode = prepare(id(context, node), node)

    private fun current(key: String, now: Long): State? {
        val value = states[key] ?: return null
        val ttl = if (value.learned != null) SUCCESS_TTL_MS else FAILURE_TTL_MS
        return value.takeIf { now - it.at in 0 until ttl }
    }

    @Synchronized
    internal fun prepare(key: String, node: ShardNode, now: Long = System.currentTimeMillis()): ShardNode {
        val state = current(key, now)
        val learned = state?.learned
        val profile = if (learned != null && !preserveVlessTransport(node)) Profile(
            learned.fingerprint.takeIf { fingerprintFree(node) },
            learned.path.takeIf { it != null && it == earlyDataPath(node, EARLY_DATA_FROM) },
            key,
        ) else Profile(
            fingerprintFor(node, state?.failures ?: 0), earlyDataPath(node, state?.failures ?: 0), key
        )
        return node.copy(retryProfile = profile)
    }

    @Synchronized
    internal fun failed(key: String, node: ShardNode, now: Long = System.currentTimeMillis()) {
        if (node.retryProfile?.scope?.let { it != key } == true) return
        val previous = current(key, now)
        // A failed learned variant must not be promoted again just because an
        // earlier network measurement worked. Continue the bounded retry cycle.
        states[key] = State(((previous?.failures ?: 0) + 1).coerceAtMost(1000), now)
        prune(now)
    }

    @Synchronized
    internal fun succeeded(key: String, node: ShardNode, now: Long = System.currentTimeMillis()) {
        // The physical attachment changed while this candidate was probing.
        if (node.retryProfile?.scope?.let { it != key } == true) return
        val measured = node.retryProfile
        val learned = measured?.takeIf { it.fingerprint != null || it.path != null }
        states[key] = State(0, now, learned)
        prune(now)
    }

    private fun prune(now: Long) {
        states.keys.filter { current(it, now) == null }.forEach { states.remove(it) }
        states.entries.sortedBy { it.value.at }.take((states.size - MAX_ENTRIES).coerceAtLeast(0))
            .forEach { states.remove(it.key) }
    }

    @Synchronized internal fun clearForTests() = states.clear()

    /** Whether rewriting this node's fingerprint cannot break an interlocked shape. */
    private fun fingerprintFree(node: ShardNode): Boolean {
        val v2 = node.v2ray
        if (v2 != null) return v2.security == "tls" || v2.security == "reality"
        if (node.security != "tls") return false
        return node.cipherSuites.isEmpty() && node.finalMask.isEmpty() &&
            !node.fingerprint.equals("unsafe", ignoreCase = true)
    }

    private fun isWorkerHost(host: String): Boolean {
        val h = host.lowercase()
        return h.endsWith(".workers.dev") || h.endsWith(".pages.dev")
    }

    /** Fingerprint to use instead of the node's own, or null for "unchanged". */
    fun fingerprintFor(context: Context, node: ShardNode): String? =
        (node.retryProfile ?: prepare(context, node).retryProfile)?.fingerprint

    internal fun fingerprintFor(node: ShardNode, failures: Int): String? {
        if (preserveVlessTransport(node) || failures < FINGERPRINT_FROM || !fingerprintFree(node)) return null
        return ROTATION[(failures - FINGERPRINT_FROM) % ROTATION.size]
    }

    private fun preserveVlessTransport(node: ShardNode): Boolean =
        node.v2ray?.let { it.protocol == "vless" && it.network in setOf("xhttp", "ws") } == true

    /** ws path with early data for this retry, or null for "unchanged". */
    fun earlyDataPath(context: Context, node: ShardNode): String? =
        (node.retryProfile ?: prepare(context, node).retryProfile)?.path

    internal fun earlyDataPath(node: ShardNode, failures: Int): String? {
        if (preserveVlessTransport(node)) return null
        val v2 = node.v2ray ?: return null
        if (v2.network != "ws" || failures < EARLY_DATA_FROM) return null
        if (isWorkerHost(v2.host) || isWorkerHost(v2.sni)) return null
        val path = v2.path.ifEmpty { "/" }
        if (path.contains("ed=")) return null
        return path + (if (path.contains('?')) "&" else "?") + "ed=2048"
    }
}

package com.molido.vpn

/**
 * Runtime promotion gate for transport adapters.
 *
 * Parser and engine-config tests may exercise an adapter while the production
 * subscription/selection paths keep it disabled until real server/device evidence
 * has been reviewed. Changing this list requires the transport promotion tests.
 */
internal object TransportRuntimePolicy {
    private val disabledPendingInterop = setOf("mkcp")

    private fun canonicalNetwork(network: String): String =
        network.trim().lowercase().let { if (it == "kcp") "mkcp" else it }

    fun isSelectable(network: String): Boolean =
        canonicalNetwork(network) !in disabledPendingInterop

    fun unavailableReason(network: String): String? =
        if (isSelectable(network)) null else "${canonicalNetwork(network)} is waiting for live-server and device validation"
}

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

    fun isSelectable(network: String): Boolean =
        network.trim().lowercase() !in disabledPendingInterop

    fun unavailableReason(network: String): String? =
        if (isSelectable(network)) null else "$network is waiting for live-server and device validation"
}

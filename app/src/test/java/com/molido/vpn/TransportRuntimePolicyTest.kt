package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRuntimePolicyTest {
    @Test
    fun mKcpRemainsDisabledUntilLiveInteropEvidenceIsReviewed() {
        assertFalse(TransportRuntimePolicy.isSelectable("mkcp"))
        assertFalse(TransportRuntimePolicy.isSelectable("kcp"))
        assertNotNull(TransportRuntimePolicy.unavailableReason("mkcp"))
    }

    @Test
    fun existingValidatedTransportFamiliesRemainSelectable() {
        for (network in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
            assertTrue(network, TransportRuntimePolicy.isSelectable(network))
            assertNull(TransportRuntimePolicy.unavailableReason(network))
        }
    }
}

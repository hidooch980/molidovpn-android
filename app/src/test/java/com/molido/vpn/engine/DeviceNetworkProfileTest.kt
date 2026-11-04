package com.molido.vpn.engine

import org.junit.Assert.*
import org.junit.Test

class DeviceNetworkProfileTest {
    private fun link(
        transport: DeviceNetworkProfile.Transport = DeviceNetworkProfile.Transport.WIFI,
        validated: Boolean = true, metered: Boolean = false, roaming: Boolean = false,
        down: Int = 20_000, up: Int = 5_000, route: Boolean = true,
    ) = DeviceNetworkProfile.Link(transport, validated, metered, roaming, down, up, route)

    @Test fun selectsActiveValidatedDefaultPhysicalNetwork() {
        val slow = DeviceNetworkProfile.Candidate(link(down = 800), false)
        val active = DeviceNetworkProfile.Candidate(link(transport = DeviceNetworkProfile.Transport.CELLULAR), true)
        assertEquals(active.link, DeviceNetworkProfile.choose(listOf(slow, active)))
    }

    @Test fun prefersDefaultRouteWhenNoCandidateIsActive() {
        val noRoute = DeviceNetworkProfile.Candidate(link(route = false), false)
        val route = DeviceNetworkProfile.Candidate(link(transport = DeviceNetworkProfile.Transport.ETHERNET), false)
        assertEquals(route.link, DeviceNetworkProfile.choose(listOf(noRoute, route)))
    }

    @Test fun meteredAndRoamingDisableDownloadProbe() {
        assertEquals(0, DeviceNetworkProfile.policy(link(metered = true), 8, 1_500_000).downloadBytes)
        assertEquals(0, DeviceNetworkProfile.policy(link(roaming = true), 8, 1_500_000).downloadBytes)
        assertEquals(2, DeviceNetworkProfile.policy(link(metered = true), 8, 1_500_000).parallelProbes)
    }

    @Test fun unvalidatedAndSlowLinksAreConservative() {
        assertEquals(DeviceNetworkProfile.Policy(1, 0), DeviceNetworkProfile.policy(link(validated = false), 8, 1_500_000))
        assertEquals(DeviceNetworkProfile.Policy(2, 256_000), DeviceNetworkProfile.policy(link(down = 1_000), 8, 1_500_000))
    }

    @Test fun unknownLinkDoesNotAssumeUnlimitedCapacity() {
        assertEquals(DeviceNetworkProfile.Policy(3, 512_000), DeviceNetworkProfile.policy(null, 8, 1_500_000))
    }

    @Test fun qualityObservationComputesMedianJitterAndLoss() {
        val o = DeviceNetworkProfile.observe(
            listOf(Probe(true, 100, 2_000), Probe(false), Probe(true, 140, 3_000), Probe(true, 120)), 1234L,
        )
        assertEquals(120L, o.rttMedianMs)
        assertEquals(30L, o.jitterMedianMs)
        assertEquals(0.25, o.packetLoss!!, 0.0001)
        assertEquals(2_500L, o.throughputMedianKbps)
    }

    @Test fun missingSamplesDoNotErasePreviousMeasurements() {
        val previous = DeviceNetworkProfile.Profile(
            DeviceNetworkProfile.Transport.CELLULAR, true, true, false, 10_000, 2_000,
            90, 8, 0.02, 4_000, 3, 3, 1000,
        )
        val merged = DeviceNetworkProfile.merge(previous, DeviceNetworkProfile.observe(emptyList(), 2000L), null, 2000L)
        assertEquals(90L, merged.rttMedianMs)
        assertEquals(8L, merged.jitterMedianMs)
        assertEquals(0.02, merged.packetLoss!!, 0.0001)
        assertEquals(4_000L, merged.throughputMedianKbps)
    }

    @Test fun profileRoundTripsAndRejectsCorruptData() {
        val profile = DeviceNetworkProfile.merge(
            null, DeviceNetworkProfile.observe(listOf(Probe(true, 75, 5_000), Probe(true, 85, 6_000)), 1234L),
            link(transport = DeviceNetworkProfile.Transport.CELLULAR, metered = true), 1234L,
        )
        assertEquals(profile, DeviceNetworkProfile.parse(DeviceNetworkProfile.toJson(profile), now = 1234L))
        assertNull(DeviceNetworkProfile.parse("{bad"))
        assertNull(DeviceNetworkProfile.parse("""{"transport":"WIFI","updated_at_ms":0}"""))
        assertNull(DeviceNetworkProfile.parse(DeviceNetworkProfile.toJson(profile), now = 1234L + 8L * 86_400_000L))
    }
}

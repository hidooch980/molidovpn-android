package com.molido.vpn

import android.net.IpPrefix
import android.net.LinkAddress
import android.net.RouteInfo
import android.net.VpnService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise Android's real Builder validation/config, without establishing VPN
 * permission or using any external network. Native packet/exit support is a
 * separate device integration check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DualStackTunTest {
    private val plans = listOf(
        Tun2SocksManager.PrivateAddress("10.0.0.1", "10.0.0.0", 8, "10.0.0.2"),
        Tun2SocksManager.PrivateAddress("172.16.0.1", "172.16.0.0", 12, "172.16.0.2"),
        Tun2SocksManager.PrivateAddress("192.168.0.1", "192.168.0.0", 16, "192.168.0.2"),
        Tun2SocksManager.PrivateAddress("169.254.1.1", "169.254.1.0", 24, "169.254.1.2"),
    )

    @Test fun everyAddressPlanCapturesBothFamiliesAndKeepsItsDnsSubnet() {
        val controller = Robolectric.buildService(VpnService::class.java).create()
        try {
            val service = controller.get()
            for (plan in plans) {
                val builder = Tun2SocksManager.configureInterface(service.Builder(), plan)
                val config: Any = ReflectionHelpers.getField(builder, "mConfig")
                val addresses: List<LinkAddress> = ReflectionHelpers.getField(config, "addresses")
                val routes: List<RouteInfo> = ReflectionHelpers.getField(config, "routes")
                assertTrue(addresses.contains(LinkAddress(plan.ipAddress + "/" + plan.prefixLength)))
                assertTrue(addresses.contains(LinkAddress(Tun2SocksManager.VPN_INTERFACE_IPV6 + "/128")))
                val destinations = routes.map { it.destination }.toSet()
                assertTrue(destinations.contains(IpPrefix("0.0.0.0/0")))
                assertTrue(destinations.contains(IpPrefix("::/0")))
                assertTrue(destinations.contains(IpPrefix(plan.subnet + "/" + plan.prefixLength)))
                assertEquals(2, addresses.size)
            }
        } finally {
            controller.destroy()
        }
    }

    @Test fun ipv4DnsStaysInsideDualStackTunnel() {
        val controller = Robolectric.buildService(VpnService::class.java).create()
        try {
            val plan = plans.first()
            val builder = Tun2SocksManager.configureInterface(controller.get().Builder(), plan)
                .addDnsServer(plan.router)
            val config: Any = ReflectionHelpers.getField(builder, "mConfig")
            val dns: List<String> = ReflectionHelpers.getField(config, "dnsServers")
            assertEquals(listOf(plan.router), dns)
            // Interface and lwIP router must remain different, for both families.
            assertNotEquals(plan.ipAddress, plan.router)
            assertNotEquals(Tun2SocksManager.VPN_INTERFACE_IPV6, Tun2SocksManager.VPN_ROUTER_IPV6)
        } finally {
            controller.destroy()
        }
    }
}

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
                assertTrue(addresses.any { it.address == java.net.InetAddress.getByName(plan.ipAddress) && it.prefixLength == plan.prefixLength })
                assertTrue(addresses.any { it.address == java.net.InetAddress.getByName(Tun2SocksManager.VPN_INTERFACE_IPV6) && it.prefixLength == 128 })
                val destinations = routes.map { it.destination }.toSet()
                assertTrue(destinations.contains(IpPrefix(java.net.InetAddress.getByName("0.0.0.0"), 0)))
                assertTrue(destinations.contains(IpPrefix(java.net.InetAddress.getByName("::"), 0)))
                assertTrue(destinations.contains(IpPrefix(java.net.InetAddress.getByName(plan.subnet), plan.prefixLength)))
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
                .addDnsServer(java.net.InetAddress.getByName(plan.router))
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

    private fun varint(value: Int): ByteArray {
        var n = value
        val out = java.io.ByteArrayOutputStream()
        do {
            val b = n and 127
            n = n ushr 7
            out.write(b or if (n != 0) 128 else 0)
        } while (n != 0)
        return out.toByteArray()
    }

    private fun bytesField(tag: Int, value: ByteArray) =
        varint((tag shl 3) or 2) + varint(value.size) + value

    private fun country(code: String, vararg ranges: Pair<String, Int>): ByteArray {
        var entry = bytesField(1, code.toByteArray())
        for ((ip, prefix) in ranges) {
            val cidr = bytesField(1, java.net.InetAddress.getByName(ip).address) +
                varint(2 shl 3) + varint(prefix)
            entry += bytesField(2, cidr)
        }
        return bytesField(1, entry)
    }

    @Test fun iranBypassKeepsExactIpv6AllocationsAndExistingIpv4Policy() {
        val encoded = country("DE", "2001:db8:1::" to 48) +
            country("IR", "5.160.0.0" to 16, "5.160.1.0" to 24, "2a00:1fa0::" to 32)
        val routes = IranDirect.parse(encoded)
        assertEquals(2, routes.size)
        assertTrue(routes.contains("5.160.0.0" to 16))
        val ipv6 = routes.single { ':' in it.first }
        assertEquals(32, ipv6.second)
        assertArrayEquals(java.net.InetAddress.getByName("2a00:1fa0::").address,
            java.net.InetAddress.getByName(ipv6.first).address)
    }

    @Test fun countryDataCannotBypassIpv6DefaultOrLocalNetworks() {
        val encoded = country("IR", "::" to 0, "fc00::" to 7, "fe80::" to 10,
            "2001:db8::" to 2, "2001:db8::" to 129, "2a00:1fa0::" to 48)
        val routes = IranDirect.parse(encoded)
        assertEquals(1, routes.size)
        assertEquals(48, routes.single().second)
    }


    @Test fun ipv6CountryListIsNotTruncatedByTheOldIpv4RouteCap() {
        val ranges = (0 until 707).map {
            ("2a00:1fa0:" + it.toString(16) + "::") to 48
        }.toTypedArray()
        val routes = IranDirect.parse(country("IR", *ranges))
        assertEquals(707, routes.size)
        assertTrue(routes.all { ':' in it.first && it.second == 48 })
    }
}

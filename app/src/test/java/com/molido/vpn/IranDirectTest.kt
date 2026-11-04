package com.molido.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class IranDirectTest {
    private fun varint(value: Int): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        while (n and -128 != 0) {
            out.write((n and 0x7f) or 0x80)
            n = n ushr 7
        }
        out.write(n)
        return out.toByteArray()
    }

    private fun field(number: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(((number shl 3) or 2).toByte()) + varint(bytes.size) + bytes

    private fun cidr(address: ByteArray, prefix: Int): ByteArray =
        field(2, field(1, address) + byteArrayOf(0x10) + varint(prefix))

    private fun country(code: String, vararg cidrs: ByteArray): ByteArray =
        field(1, code.toByteArray(Charsets.UTF_8)) + cidrs.fold(ByteArray(0)) { acc, item ->
            acc + field(2, item)
        }

    @Test fun parsesIranianIpv4AndExactIpv6Allocations() {
        val ir = country(
            "IR",
            cidr(byteArrayOf(5, 52, 0, 0), 16),
            cidr(byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0
            ), 32),
        )
        val parsed = IranDirect.parse(field(1, ir))
        assertEquals(2, parsed.size)
        assertEquals("5.52.0.0" to 16, parsed.first { ':' !in it.first })
        val ipv6 = parsed.single { ':' in it.first }
        assertEquals(32, ipv6.second)
        assertTrue(ipv6.first.startsWith("2001:db8:"))
    }

    @Test fun ignoresNonIranCountriesAndOverlySpecificIpv4Prefixes() {
        val us = country("US", cidr(byteArrayOf(8, 8, 8, 0), 24))
        val ir = country("IR", cidr(byteArrayOf(5, 52, 1, 0), 24))
        assertTrue(IranDirect.parse(field(1, us) + field(1, ir)).isEmpty())
    }

    @Test fun ignoresMalformedAddressLengths() {
        val ir = country("IR", cidr(byteArrayOf(5, 52, 0), 16))
        assertTrue(IranDirect.parse(field(1, ir)).isEmpty())
    }
}

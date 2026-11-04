package com.molido.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsOnlyPacketValidationTest {
    private fun dnsHeader(id: Int, flags: Int): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(),
        (flags ushr 8).toByte(), flags.toByte(),
        0, 1, 0, 0, 0, 0, 0, 0,
    )

    @Test fun acceptsOnlyResponseForOutstandingTransaction() {
        val query = dnsHeader(0x4a21, 0x0100)
        val response = dnsHeader(0x4a21, 0x8180)
        assertTrue(DnsOnlyPacketValidation.matchesResponse(query, response, response.size))
    }

    @Test fun rejectsWrongTransactionId() {
        assertFalse(DnsOnlyPacketValidation.matchesResponse(
            dnsHeader(0x4a21, 0x0100), dnsHeader(0x4a22, 0x8180), 12,
        ))
    }

    @Test fun rejectsQueryPacketsAndOpcodeMismatch() {
        val query = dnsHeader(0x4a21, 0x0100)
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, dnsHeader(0x4a21, 0x0100), 12))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, dnsHeader(0x4a21, 0x8980), 12))
    }

    @Test fun rejectsTruncatedOrInvalidLengths() {
        val query = dnsHeader(0x4a21, 0x0100)
        val response = dnsHeader(0x4a21, 0x8180)
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, response, 11))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, response, 13))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query.copyOf(11), response, 12))
    }
}

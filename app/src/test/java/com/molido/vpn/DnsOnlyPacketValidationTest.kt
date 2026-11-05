package com.molido.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsOnlyPacketValidationTest {
    private fun dnsMessage(
        id: Int,
        flags: Int,
        name: String = "example.com",
        type: Int = 1,
        klass: Int = 1,
    ): ByteArray {
        val encodedName = name.split(".").flatMap { label ->
            listOf(label.length) + label.map { it.code }
        } + 0
        val question = (encodedName + listOf(type ushr 8, type and 0xff, klass ushr 8, klass and 0xff))
            .map { it.toByte() }.toByteArray()
        return byteArrayOf(
            (id ushr 8).toByte(), id.toByte(),
            (flags ushr 8).toByte(), flags.toByte(),
            0, 1, 0, 0, 0, 0, 0, 0,
        ) + question
    }

    @Test fun acceptsResponseEchoingOutstandingQuestion() {
        val query = dnsMessage(0x4a21, 0x0100)
        val response = dnsMessage(0x4a21, 0x8180)
        assertTrue(DnsOnlyPacketValidation.matchesResponse(query, response, response.size))
    }

    @Test fun acceptsCaseInsensitiveDnsQuestionNames() {
        val query = dnsMessage(0x4a21, 0x0100, name = "Example.COM")
        val response = dnsMessage(0x4a21, 0x8180, name = "example.com")
        assertTrue(DnsOnlyPacketValidation.matchesResponse(query, response, response.size))
    }

    @Test fun rejectsWrongTransactionId() {
        assertFalse(DnsOnlyPacketValidation.matchesResponse(
            dnsMessage(0x4a21, 0x0100), dnsMessage(0x4a22, 0x8180), 27,
        ))
    }

    @Test fun rejectsMismatchedQuestionNameTypeAndClass() {
        val query = dnsMessage(0x4a21, 0x0100)
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, dnsMessage(0x4a21, 0x8180, name = "attacker.example"), 35))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, dnsMessage(0x4a21, 0x8180, type = 28), 27))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, dnsMessage(0x4a21, 0x8180, klass = 3), 27))
    }

    @Test fun rejectsQueryPacketsAndOpcodeMismatch() {
        val query = dnsMessage(0x4a21, 0x0100)
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, dnsMessage(0x4a21, 0x0100), query.size))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, dnsMessage(0x4a21, 0x8980), query.size))
    }

    @Test fun rejectsTruncatedOrInvalidLengths() {
        val query = dnsMessage(0x4a21, 0x0100)
        val response = dnsMessage(0x4a21, 0x8180)
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, response, 11))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, response, response.size + 1))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query.copyOf(11), response, response.size))
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, response, 13))
    }

    @Test fun rejectsMalformedCompressionPointer() {
        val query = dnsMessage(0x4a21, 0x0100)
        val response = byteArrayOf(
            0x4a, 0x21, 0x81.toByte(), 0x80.toByte(),
            0, 1, 0, 0, 0, 0, 0, 0,
            0xc0.toByte(), 0x0c,
            0, 1, 0, 1,
        )
        assertFalse(DnsOnlyPacketValidation.matchesResponse(query, response, response.size))
    }
}

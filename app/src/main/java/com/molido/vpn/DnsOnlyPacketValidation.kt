package com.molido.vpn

/** Fail-closed validation for DNS replies received by the DNS-only UDP forwarder. */
internal object DnsOnlyPacketValidation {
    /** Requires a DNS header, QR=1, and the exact outstanding transaction ID/opcode. */
    fun matchesResponse(query: ByteArray, response: ByteArray, responseLength: Int): Boolean {
        if (query.size < DNS_HEADER_SIZE || responseLength < DNS_HEADER_SIZE || responseLength > response.size) return false
        val queryId = u16(query, 0)
        val responseId = u16(response, 0)
        if (queryId != responseId) return false
        val queryFlags = u16(query, 2)
        val responseFlags = u16(response, 2)
        if ((queryFlags and QR_MASK) != 0 || (responseFlags and QR_MASK) == 0) return false
        return (queryFlags and OPCODE_MASK) == (responseFlags and OPCODE_MASK)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private const val DNS_HEADER_SIZE = 12
    private const val QR_MASK = 0x8000
    private const val OPCODE_MASK = 0x7800
}

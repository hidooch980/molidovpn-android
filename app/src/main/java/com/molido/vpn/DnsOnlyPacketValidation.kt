package com.molido.vpn

/** Fail-closed validation for DNS replies received by the DNS-only UDP forwarder. */
internal object DnsOnlyPacketValidation {
    /**
     * Requires a matching transaction ID, QR/opcode, and every echoed DNS question.
     * Matching only the 16-bit ID lets an off-path packet with a guessed ID win the
     * race and inject an unrelated answer into the system resolver.
     */
    fun matchesResponse(query: ByteArray, response: ByteArray, responseLength: Int): Boolean {
        if (query.size < DNS_HEADER_SIZE || responseLength < DNS_HEADER_SIZE || responseLength > response.size) return false
        if (u16(query, 0) != u16(response, 0)) return false
        val queryFlags = u16(query, 2)
        val responseFlags = u16(response, 2)
        if ((queryFlags and QR_MASK) != 0 || (responseFlags and QR_MASK) == 0) return false
        if ((queryFlags and OPCODE_MASK) != (responseFlags and OPCODE_MASK)) return false

        val queryCount = u16(query, QUESTION_COUNT_OFFSET)
        val responseCount = u16(response, QUESTION_COUNT_OFFSET)
        if (queryCount == 0 || queryCount != responseCount || queryCount > MAX_QUESTIONS) return false

        return try {
            var queryOffset = DNS_HEADER_SIZE
            var responseOffset = DNS_HEADER_SIZE
            repeat(queryCount) {
                val q = readQuestion(query, queryOffset, query.size, allowCompression = true) ?: return false
                val a = readQuestion(response, responseOffset, responseLength, allowCompression = true) ?: return false
                if (q.name != a.name || q.type != a.type || q.klass != a.klass) return false
                queryOffset = q.nextOffset
                responseOffset = a.nextOffset
            }
            true
        } catch (_: IndexOutOfBoundsException) {
            false
        }
    }

    private data class Question(val name: List<Int>, val type: Int, val klass: Int, val nextOffset: Int)

    /**
     * Decodes a DNS name and its QTYPE/QCLASS. Compression pointers are bounded and
     * cycle-checked; the returned nextOffset always points past the encoded name in
     * the original question section, not at the pointer target.
     */
    private fun readQuestion(
        bytes: ByteArray,
        start: Int,
        limit: Int,
        allowCompression: Boolean,
    ): Question? {
        if (start < DNS_HEADER_SIZE || start >= limit) return null
        val labels = ArrayList<Int>()
        val visited = HashSet<Int>()
        var cursor = start
        var nextOffset = -1
        var expandedLength = 1
        var jumps = 0

        while (true) {
            if (cursor >= limit) return null
            val length = bytes[cursor].toInt() and 0xff
            when (length and 0xc0) {
                0x00 -> {
                    cursor++
                    if (length == 0) {
                        if (nextOffset < 0) nextOffset = cursor
                        break
                    }
                    if (length > MAX_LABEL_LENGTH || cursor + length > limit) return null
                    expandedLength += length + 1
                    if (expandedLength > MAX_NAME_LENGTH) return null
                    labels.add(length)
                    repeat(length) { index ->
                        val value = bytes[cursor + index].toInt() and 0xff
                        labels.add(if (value in 65..90) value + 32 else value)
                    }
                    cursor += length
                    if (nextOffset < 0) nextOffset = cursor
                }
                0xc0 -> {
                    if (!allowCompression || cursor + 1 >= limit || ++jumps > MAX_POINTER_JUMPS) return null
                    val pointer = ((length and 0x3f) shl 8) or (bytes[cursor + 1].toInt() and 0xff)
                    if (pointer < DNS_HEADER_SIZE || pointer >= limit || !visited.add(pointer)) return null
                    if (nextOffset < 0) nextOffset = cursor + 2
                    cursor = pointer
                }
                else -> return null // reserved label encodings
            }
        }

        if (nextOffset < 0 || nextOffset + 4 > limit) return null
        return Question(
            name = labels,
            type = u16(bytes, nextOffset),
            klass = u16(bytes, nextOffset + 2),
            nextOffset = nextOffset + 4,
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private const val DNS_HEADER_SIZE = 12
    private const val QUESTION_COUNT_OFFSET = 4
    private const val QR_MASK = 0x8000
    private const val OPCODE_MASK = 0x7800
    private const val MAX_QUESTIONS = 16
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_POINTER_JUMPS = 16
}

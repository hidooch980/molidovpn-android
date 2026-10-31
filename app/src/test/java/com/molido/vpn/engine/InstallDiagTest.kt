package com.molido.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallDiagTest {
    @Test fun idsAreWellFormedAndRandom() {
        val ids = (1..2_000).map { InstallIdFormat.generate() }.toSet()
        assertEquals(2_000, ids.size)
        ids.forEach { assertTrue(it, InstallIdFormat.valid(it)) }
        assertFalse(InstallIdFormat.valid("MLD-ABCI-0000")) // I is not Crockford
        assertFalse(InstallIdFormat.valid("MLD-abcd-0000"))
        assertFalse(InstallIdFormat.valid(null))
    }

    @Test fun typedIdsAreNormalized() {
        assertEquals("MLD-7K2M-9QX1", InstallIdFormat.normalize(" mld 7k2m-9qxl "))
        assertEquals("MLD-0000-1111", InstallIdFormat.normalize("MLDOOOOIIII"))
        assertNull(InstallIdFormat.normalize("MLD-123"))
    }

    @Test fun scrubRemovesConfigsKeysAndAddresses() {
        val s = DiagBuffer.scrub(
            "dial vless://11111111-2222-3333-4444-555555555555@203.0.113.7:443?pbk=AbCdEf&sid=12#x failed; " +
                "id 11111111-2222-3333-4444-555555555555 at 198.51.100.23 token=secret " + "Q".repeat(40)
        )
        assertFalse(s, s.contains("203.0.113.7"))
        assertFalse(s, s.contains("198.51.100.23"))
        assertFalse(s, s.contains("555555555555"))
        assertFalse(s, s.contains("secret"))
        assertFalse(s, s.contains("QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ"))
        assertTrue(s, s.contains("vless://…"))
        assertTrue(s, s.contains("198.51.x.x"))
    }

    @Test fun ringBufferCapsAndTracksPending() {
        var lines = emptyList<String>()
        for (i in 1..(DiagBuffer.MAX + 20)) lines = DiagBuffer.append(lines, DiagBuffer.event(i * 1_000L, "error", "v2ray", "wifi", "e$i"))
        assertEquals(DiagBuffer.MAX, lines.size)
        assertTrue(lines.last().contains("e${DiagBuffer.MAX + 20}"))
        assertEquals(5, DiagBuffer.pending(lines, (DiagBuffer.MAX + 15).toLong()).size)
    }
}

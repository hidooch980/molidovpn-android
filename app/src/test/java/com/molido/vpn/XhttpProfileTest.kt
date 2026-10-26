package com.molido.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XhttpProfileTest {
    @Test fun acceptsOnlySupportedExplicitModes() {
        for (mode in listOf("auto", "packet-up", "stream-up", "stream-one")) {
            assertEquals(mode, XhttpProfile.parse(mode, "")!!.mode)
        }
        assertEquals("auto", XhttpProfile.parse("", "")!!.mode)
        assertEquals("packet-up", XhttpProfile.parse("PACKET-UP", "")!!.mode)
        assertNull(XhttpProfile.parse("stream-down", ""))
        assertNull(XhttpProfile.parse("typo", "{}"))
    }

    @Test fun malformedExtraIsRejectedInsteadOfRemoved() {
        for (extra in listOf("[]", "null", "42", "\"text\"", "{broken", "{} trailing", "{}{}")) {
            assertNull(extra, XhttpProfile.parse("auto", extra))
        }
        assertNull(XhttpProfile.parse("auto", " ".repeat(65_537)))
        assertEquals("", XhttpProfile.parse("auto", "  ")!!.extra)
    }

    @Test fun keepsCompleteExtraIncludingDownstreamAndUnknownFutureFields() {
        val raw = """{"downloadSettings":{"address":"192.0.2.2","port":443,"network":"xhttp"},
            "xmux":{"maxConnections":2},"xPaddingBytes":"100-1000","futureField":{"value":7}}"""
        val parsed = XhttpProfile.parse("stream-up", raw)!!
        assertJsonEquivalent(JSONObject(raw), JSONObject(parsed.extra))
    }

    @Test fun rejectsHostHeaderAndNonStringHeaders() {
        for (headers in listOf("{\"Host\":\"cdn.example\"}", "{\"hOsT\":\"cdn.example\"}", "[]", "{\"X-Test\":5}")) {
            assertNull(XhttpProfile.parse("auto", "{\"headers\":$headers}"))
        }
        assertNotNull(XhttpProfile.parse("auto", """{"headers":{"X-Test":"value"}}"""))
        assertNotNull(XhttpProfile.parse("auto", """{"headers":null}"""))
    }

    @Test fun downstreamCannotBeCombinedWithStreamOne() {
        assertNull(XhttpProfile.parse("stream-one", """{"downloadSettings":{}}"""))
        assertNull(XhttpProfile.parse("stream-up", """{"downloadSettings":"bad"}"""))
        assertNotNull(XhttpProfile.parse("stream-one", """{"downloadSettings":null}"""))
        assertNotNull(XhttpProfile.parse("stream-up", """{"downloadSettings":{}}"""))
    }
}

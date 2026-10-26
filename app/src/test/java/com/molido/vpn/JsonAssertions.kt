package com.molido.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Structural comparison using the JSON API shared by Android and JVM tests. */
internal fun assertJsonEquivalent(expected: Any?, actual: Any?) {
    when (expected) {
        is JSONObject -> {
            assertTrue("Expected a JSON object", actual is JSONObject)
            actual as JSONObject
            val keys = expected.keys().asSequence().toSet()
            assertEquals(keys, actual.keys().asSequence().toSet())
            keys.forEach { assertJsonEquivalent(expected.get(it), actual.get(it)) }
        }
        is JSONArray -> {
            assertTrue("Expected a JSON array", actual is JSONArray)
            actual as JSONArray
            assertEquals(expected.length(), actual.length())
            for (index in 0 until expected.length()) assertJsonEquivalent(expected.get(index), actual.get(index))
        }
        else -> assertEquals(expected, actual)
    }
}

package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {

    @Test
    fun throughputUsesActualBytesAndElapsedMilliseconds() {
        assertEquals(80.0, SpeedTest.calculateMbps(10_000_000, 1_000)!!, 0.0001)
        assertEquals(40.0, SpeedTest.calculateMbps(5_000_000, 1_000)!!, 0.0001)
    }

    @Test
    fun invalidThroughputSamplesAreRejected() {
        assertNull(SpeedTest.calculateMbps(0, 1_000))
        assertNull(SpeedTest.calculateMbps(-1, 1_000))
        assertNull(SpeedTest.calculateMbps(1_000, 0))
        assertNull(SpeedTest.calculateMbps(1_000, -1))
    }

    @Test
    fun onlySuccessfulHttpResponsesAreAccepted() {
        assertTrue(SpeedTest.isSuccessfulHttpStatus(200))
        assertTrue(SpeedTest.isSuccessfulHttpStatus(204))
        assertTrue(SpeedTest.isSuccessfulHttpStatus(206))
        assertFalse(SpeedTest.isSuccessfulHttpStatus(199))
        assertFalse(SpeedTest.isSuccessfulHttpStatus(300))
        assertFalse(SpeedTest.isSuccessfulHttpStatus(404))
        assertFalse(SpeedTest.isSuccessfulHttpStatus(500))
    }
}

package com.molido.vpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionTuningTest {
    @Test public void autoMtuRespectsPhysicalLinkAndTransport() {
        assertEquals(1380, ConnectionTuning.tunMtu(true, true, 1380));
        assertEquals(1460, ConnectionTuning.tunMtu(true, false, 1460));
        assertEquals(1400, ConnectionTuning.tunMtu(true, true, 1500));
        assertEquals(1500, ConnectionTuning.tunMtu(true, false, 9000));
    }
    @Test public void unknownOrUnsafeReportsRetainIpv6SafeDefaults() {
        assertEquals(1400, ConnectionTuning.tunMtu(true, true, 0));
        assertEquals(1500, ConnectionTuning.tunMtu(true, false, 900));
        assertEquals(1280, ConnectionTuning.tunMtu(true, true, 1280));
        assertEquals(1500, ConnectionTuning.tunMtu(true, false, 100000));
    }
    @Test public void disablingAutoPreservesManualBehavior() {
        assertEquals(1500, ConnectionTuning.tunMtu(false, true, 1280));
    }
    @Test public void failedFragmentCacheExpiresInsteadOfBeingPermanent() {
        long saved = 1000;
        assertTrue(ConnectionTuning.freshMeasurement(saved, saved + 599999, true));
        assertFalse(ConnectionTuning.freshMeasurement(saved, saved + 600000, true));
        assertTrue(ConnectionTuning.freshMeasurement(saved, saved + 600000, false));
        assertFalse(ConnectionTuning.freshMeasurement(saved, saved + 86400000, false));
    }
    @Test public void clockRollbackAndLegacyCacheAreNotTrusted() {
        assertFalse(ConnectionTuning.freshMeasurement(0, 1000, false));
        assertFalse(ConnectionTuning.freshMeasurement(2000, 1000, false));
    }
}

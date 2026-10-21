package com.molido.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TrafficStallTest {
    @Test public void resumedBeforeWorkerDoesNotProbeOrRotate() {
        assertFalse(TrafficStall.confirm(100, () -> 101, () -> { throw new AssertionError("unneeded probe"); }));
    }
    @Test public void resumedDuringFirstProbeDoesNotStartSecondProbe() {
        AtomicLong received=new AtomicLong(100);
        AtomicInteger calls=new AtomicInteger();
        assertFalse(TrafficStall.confirm(100, received::get, () -> {
            assertEquals(1,calls.incrementAndGet()); received.incrementAndGet(); return false;
        }));
        assertEquals(1,calls.get());
    }
    @Test public void resumedDuringSecondProbeCancelsRotation() {
        AtomicLong received=new AtomicLong(100);
        AtomicInteger calls=new AtomicInteger();
        assertFalse(TrafficStall.confirm(100, received::get, () -> {
            if(calls.incrementAndGet()==2) received.incrementAndGet(); return false;
        }));
        assertEquals(2,calls.get());
    }
    @Test public void twoFailuresWithNoTrafficConfirmStall() {
        AtomicInteger calls=new AtomicInteger();
        assertTrue(TrafficStall.confirm(100, () -> 100, () -> { calls.incrementAndGet(); return false; }));
        assertEquals(2,calls.get());
    }
    @Test public void healthyFirstProbeAndCounterResetAreNotStalls() {
        assertFalse(TrafficStall.confirm(100, () -> 100, () -> true));
        assertFalse(TrafficStall.confirm(100, () -> 0, () -> { throw new AssertionError("counter reset"); }));
    }
}

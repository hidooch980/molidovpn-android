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
    @Test public void fragmentBlackholeConsumesOneTotalBudget() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(
            0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket> sockets =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        Thread listener = new Thread(() -> {
            ready.countDown();
            while (!server.isClosed()) {
                try {
                    sockets.add(server.accept());
                    accepted.incrementAndGet();
                    // Deliberately never answer SOCKS greeting: no internet is used.
                } catch (java.io.IOException stopped) { break; }
            }
        }, "fragment-blackhole-test");
        listener.setDaemon(true);
        listener.start();
        try {
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(SmartSplit.INSTANCE.probeThroughSocks(server.getLocalPort(), 250));
            // A per-host timeout would open three stalled connections. A total
            // budget expires on the first one and never starts another host.
            assertEquals(1, accepted.get());
        } finally {
            server.close();
            for (java.net.Socket socket : sockets) socket.close();
            listener.join(1000);
        }
    }

    @Test public void exhaustedFragmentBudgetDoesNotStartNetworkWork() {
        assertFalse(SmartSplit.INSTANCE.probeThroughSocks(1, 0));
    }

}

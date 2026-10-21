package com.molido.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TunnelLeaseTest {
    private static final class Handle implements AutoCloseable {
        int closes;
        Runnable beforeClose = () -> {};
        @Override public void close() { beforeClose.run(); closes++; }
    }
    @Test public void replacementIsPublishedBeforeOldInterfaceCloses() {
        TunnelLease<Handle> lease = new TunnelLease<>();
        Handle old = new Handle(), next = new Handle();
        lease.install(old);
        old.beforeClose = () -> { assertSame(next, lease.current()); assertEquals(0, next.closes); };
        lease.install(next);
        assertEquals(1, old.closes);
        assertSame(next, lease.current());
    }
    @Test public void failedReplacementKeepsThePreviousBlockingInterface() {
        TunnelLease<Handle> lease = new TunnelLease<>();
        Handle old = new Handle();
        lease.install(old);
        assertThrows(IllegalArgumentException.class, () -> lease.install(null));
        assertSame(old, lease.current());
        assertEquals(0, old.closes);
    }
    @Test public void lateWorkerCannotCloseTheNewSession() throws Exception {
        TunnelLease<Handle> lease = new TunnelLease<>();
        Handle old = new Handle(), next = new Handle();
        CountDownLatch replacement = new CountDownLatch(1);
        lease.install(old);
        Thread previousWorker = new Thread(() -> {
            try { if (!replacement.await(2, TimeUnit.SECONDS)) return; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            lease.release(old);
        });
        previousWorker.start();
        lease.install(next);
        replacement.countDown();
        previousWorker.join(3000);
        assertFalse(previousWorker.isAlive());
        assertSame(next, lease.current());
        assertEquals(0, next.closes);
        assertEquals(1, old.closes);
    }
    @Test public void explicitDisconnectClosesExactlyTheCurrentDescriptor() {
        TunnelLease<Handle> lease = new TunnelLease<>();
        Handle live = new Handle();
        lease.install(live); lease.install(live);
        lease.clear(); lease.clear(); lease.release(live);
        assertEquals(1, live.closes);
        assertNull(lease.current());
    }
    @Test public void closeFailureDoesNotDiscardTheEstablishedSuccessor() {
        TunnelLease<AutoCloseable> lease = new TunnelLease<>();
        lease.install(() -> { throw new java.io.IOException("old already closed"); });
        Handle next = new Handle();
        lease.install(next);
        assertSame(next, lease.current());
        assertEquals(0, next.closes);
    }
}

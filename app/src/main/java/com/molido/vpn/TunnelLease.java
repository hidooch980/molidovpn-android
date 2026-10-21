package com.molido.vpn;

/** Holds the TUN across retries. Install only an already-established replacement;
 * a late worker may release its own descriptor, never a successor's. */
public final class TunnelLease<T extends AutoCloseable> {
    private T current;
    public synchronized T current() { return current; }
    public synchronized void install(T next) {
        if (next == null) throw new IllegalArgumentException("No replacement TUN");
        if (next == current) return;
        T previous = current;
        current = next;
        close(previous);
    }
    public synchronized void release(T expected) {
        if (expected == null || current != expected) return;
        current = null;
        close(expected);
    }
    public synchronized void clear() {
        T previous = current;
        current = null;
        close(previous);
    }
    private static void close(AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) {
            // A stale close cannot discard an already-established successor.
        }
    }
}

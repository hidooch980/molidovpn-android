package com.molido.vpn;

/** Conservative Android TUN sizing and measured Fragment cache lifetime; no PMTU claim. */
public final class ConnectionTuning {
    private ConnectionTuning() {}
    public static final long SUCCESS_TTL_MS = 24L * 60 * 60 * 1000;
    public static final long FAILURE_TTL_MS = 10L * 60 * 1000;

    public static int tunMtu(boolean automatic, boolean cellular, int linkMtu) {
        if (!automatic) return 1500;
        int preferred = cellular ? 1400 : 1500;
        // Unknown or sub-IPv6-minimum reports are not safe TUN size suggestions.
        return linkMtu >= 1280 && linkMtu <= 65535
            ? Math.min(preferred, linkMtu) : preferred;
    }

    public static boolean freshMeasurement(long savedAt, long now, boolean failed) {
        if (savedAt <= 0 || now < savedAt) return false;
        return now - savedAt < (failed ? FAILURE_TTL_MS : SUCCESS_TTL_MS);
    }
}

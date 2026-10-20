package com.molido.vpn;
import java.util.List;
import java.util.Locale;

public final class ConnectionDiagnosis {
    private ConnectionDiagnosis() {}
    public enum Finding {
        OFFLINE, ACTIVE, COUNTRY, EMPTY_POOL, DNS, TIMEOUT, UNKNOWN
    }
    public static Finding inspect(boolean network, boolean active,
            String country, int nodes, List<String> recent) {
        if (!network) return Finding.OFFLINE;
        if (active) return Finding.ACTIVE;
        if (country != null && !country.isEmpty()) return Finding.COUNTRY;
        if (nodes == 0) return Finding.EMPTY_POOL;
        String logs = String.join("\n", recent).toLowerCase(Locale.ROOT);
        if (logs.contains("unknownhost") ||
            logs.contains("unable to resolve") ||
            logs.contains("no such host")) return Finding.DNS;
        if (logs.contains("timed out") ||
            logs.contains("timeout")) return Finding.TIMEOUT;
        return Finding.UNKNOWN;
    }
}

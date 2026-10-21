package com.molido.vpn;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Probe failures never outweigh bytes that resumed arriving during the check. */
public final class TrafficStall {
    private TrafficStall() {}
    public static boolean confirm(long baselineRx, LongSupplier received, BooleanSupplier probe) {
        if (received.getAsLong() != baselineRx || probe.getAsBoolean()) return false;
        if (received.getAsLong() != baselineRx || probe.getAsBoolean()) return false;
        return received.getAsLong() == baselineRx;
    }
}

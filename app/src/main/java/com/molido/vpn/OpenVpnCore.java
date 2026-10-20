package com.molido.vpn;
/** Native profile inspection only; does not start or own a tunnel. */
public final class OpenVpnCore {
    public static final int UNAVAILABLE = -1;
    public static final int VALID = 0;
    public static final int INVALID = 1;
    public static final int AUTH_REQUIRED = 2;
    public static final int EXTERNAL_PKI = 4;
    public static final int KEY_PASSWORD_REQUIRED = 8;
    public static final int INTERNAL_ERROR = 16;
    private static final boolean AVAILABLE;
    static {
        boolean loaded = false;
        try { System.loadLibrary("molido_openvpn"); loaded = true; }
        catch (UnsatisfiedLinkError | SecurityException ignored) { }
        AVAILABLE = loaded;
    }
    private OpenVpnCore() {}
    public static boolean isAvailable() { return AVAILABLE; }
    public static String revision() { return AVAILABLE ? nativeRevision() : ""; }
    public static int evaluate(byte[] content) {
        if (content == null) return INVALID;
        try { VpnProfilePreflight.openVpn(content); }
        catch (VpnProfilePreflight.Invalid e) { return INVALID; }
        return AVAILABLE ? nativeEvaluate(content) : UNAVAILABLE;
    }
    private static native String nativeRevision();
    private static native int nativeEvaluate(byte[] content);
}

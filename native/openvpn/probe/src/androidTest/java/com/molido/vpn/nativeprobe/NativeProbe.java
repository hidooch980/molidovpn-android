package com.molido.vpn.nativeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.molido.vpn.OpenVpnCore;
import com.molido.vpn.VpnProfilePreflight;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Executes the production Java/JNI entry point inside Android, without a network permission. */
public final class NativeProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Native inspection assertion failed");
    }
    private byte[] fixture(String name) throws Exception {
        try (InputStream input = getTargetContext().getAssets().open(name)) {
            return VpnProfilePreflight.readBounded(input, VpnProfilePreflight.MAX_BYTES);
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require(OpenVpnCore.isAvailable());
            String expected = new String(fixture("revision.txt"), StandardCharsets.US_ASCII).trim();
            require(OpenVpnCore.revision().equals(expected));
            byte[] valid = fixture("auth.ovpn");
            // Preflight acceptance alone is insufficient: native OpenVPN must also parse it.
            VpnProfilePreflight.openVpn(valid);
            for (int i = 0; i < 20; i++) {
                int flags = OpenVpnCore.evaluate(valid);
                require((flags & OpenVpnCore.AUTH_REQUIRED) != 0);
                require((flags & (OpenVpnCore.INVALID | OpenVpnCore.INTERNAL_ERROR)) == 0);
            }
            // Java permits up to 512 KiB; the pinned native parser has a smaller limit.
            byte[] nativeOversize = new byte[400 * 1024];
            java.util.Arrays.fill(nativeOversize, (byte) '#');
            System.arraycopy(valid, 0, nativeOversize, 0, valid.length);
            VpnProfilePreflight.openVpn(nativeOversize);
            require(OpenVpnCore.evaluate(nativeOversize) == OpenVpnCore.INVALID);
            require(OpenVpnCore.evaluate(null) == OpenVpnCore.INVALID);
            result.putString("stream", "\nMOLIDO_NATIVE_PROBE_OK: JNI loaded; revision, auth profile, repeated parsing and native size limit checked. No tunnel test.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Do not return parser text, certificates, profile payloads or credentials.
            result.putString("stream", "\nMOLIDO_NATIVE_PROBE_FAILED\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}

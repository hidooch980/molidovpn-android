package com.molido.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
public class OpenVpnCoreTest {
    @Test public void missingNativeLibraryDoesNotCrashOrPretendToValidate() {
        assertFalse(OpenVpnCore.isAvailable());
        assertEquals("", OpenVpnCore.revision());
        assertEquals(OpenVpnCore.UNAVAILABLE,
            OpenVpnCore.evaluate("client\ndev tun\nremote vpn.example.org 443 tcp\n".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void invalidProfilesNeverReachTheNativeParser() {
        assertEquals(OpenVpnCore.INVALID, OpenVpnCore.evaluate(null));
        assertEquals(OpenVpnCore.INVALID, OpenVpnCore.evaluate("client\nup secret-script\n".getBytes(StandardCharsets.UTF_8)));
    }
}

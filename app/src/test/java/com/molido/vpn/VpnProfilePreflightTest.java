package com.molido.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class VpnProfilePreflightTest {
    private static final String BASE = "client\ndev tun\nremote vpn.example.org 1194 udp\n";
    private VpnProfilePreflight.OpenVpn parse(String text) {
        return VpnProfilePreflight.openVpn(text.getBytes(StandardCharsets.UTF_8));
    }
    private void rejected(String text, String reason) {
        try { parse(text); fail("Import should be rejected"); }
        catch (VpnProfilePreflight.Invalid e) {
            assertEquals(reason, e.reason);
            assertEquals(reason, e.getMessage());
        }
    }
    @Test public void preservesPayloadAndSupportsMultipleRemotes() {
        String text = BASE + "remote vpn2.example.org 443 tcp-client\nproto udp\n<ca>\nCERT\n</ca>\n<key>\nPRIVATE-KEY\n</key>\n";
        VpnProfilePreflight.OpenVpn result = parse(text);
        assertEquals(text, result.content);
        assertEquals(2, result.remoteCount);
        assertFalse(result.toString().contains("PRIVATE-KEY"));
    }
    @Test public void supportsBomCrlfAndQuotedRemote() {
        assertEquals(1, parse("\uFEFFclient\r\ndev tun\r\nremote \"vpn.example.org\" 443 tcp # hello\r\n").remoteCount);
    }
    @Test public void rejectsScriptsAndPlugins() {
        rejected(BASE + "up /private/script\n", "unsupported");
        rejected(BASE + "plugin /private/plugin.so\n", "unsupported");
        rejected(BASE + "config other.ovpn\n", "unsupported");
        rejected(BASE + "management 127.0.0.1 1234\n", "unsupported");
    }
    @Test public void rejectsExternalSecrets() {
        rejected(BASE + "key /private/secret.key\n", "external");
        rejected(BASE + "ca ca.pem\n", "external");
        rejected(BASE + "auth-user-pass credentials.txt\n", "external");
        assertEquals(1, parse(BASE + "auth-user-pass\n").remoteCount);
    }
    @Test public void rejectsMalformedInlineBlocks() {
        rejected(BASE + "<key>\nsecret\n", "inline");
        rejected(BASE + "<key>\n</key>\n", "inline");
        rejected(BASE + "<key>\n<ca>\nsecret\n</ca>\n</key>\n", "inline");
        rejected(BASE + "<connection>\nremote host 443\n</connection>\n", "unsupported");
    }
    @Test public void rejectsUnknownRatherThanDroppingOptions() {
        rejected(BASE + "unknown-future-option value\n", "unsupported");
    }
    @Test public void rejectsNonClientAndTap() {
        rejected("remote vpn.example.org 1194\n", "client");
        rejected("client\ndev tap\nremote host 443\n", "device");
        rejected("client\ndev tun\n", "client");
    }
    @Test public void checksPortsAndTransport() {
        rejected("client\nremote host 0\n", "remote");
        rejected("client\nremote host 65536\n", "remote");
        rejected(BASE + "proto tcp-server\n", "transport");
        rejected("client\nremote host 443 nonsense\n", "transport");
    }
    @Test public void rejectsBrokenQuotes() { rejected(BASE + "remote \"secret-host 443\n", "syntax"); }
    @Test public void rejectsInvalidUtf8AndNull() {
        try { VpnProfilePreflight.openVpn(new byte[] {(byte) 0xC3, 0x28}); fail(); }
        catch (VpnProfilePreflight.Invalid e) { assertEquals("encoding", e.reason); }
        rejected(BASE + "\0", "encoding");
    }
    @Test public void rejectsOversizedProfile() {
        try { VpnProfilePreflight.openVpn(new byte[VpnProfilePreflight.MAX_BYTES + 1]); fail(); }
        catch (VpnProfilePreflight.Invalid e) { assertEquals("size", e.reason); }
    }
    @Test public void boundedReaderAcceptsLimitAndRejectsExtraByte() throws Exception {
        assertEquals(32, VpnProfilePreflight.readBounded(new ByteArrayInputStream(new byte[32]), 32).length);
        try { VpnProfilePreflight.readBounded(new ByteArrayInputStream(new byte[33]), 32); fail(); }
        catch (VpnProfilePreflight.Invalid e) { assertEquals("size", e.reason); }
    }
    @Test public void gatewayRetainsExplicitPortAndAuthGroup() {
        assertEquals("https://vpn.example.org:8443/group", VpnProfilePreflight.openConnect("vpn.example.org:8443/group"));
        assertEquals("https://[2001:db8::1]:443/group", VpnProfilePreflight.openConnect("https://[2001:db8::1]:443/group"));
    }
    @Test public void gatewayRejectsCredentialsInsecureSchemeAndInvalidPorts() {
        for (String value : new String[] { "http://vpn.example.org", "https://user:secret@vpn.example.org",
            "https://vpn.example.org:0", "https://vpn.example.org:65536", "https://vpn.example.org:",
            "https://vpn.example.org?cookie=secret", "https://vpn.example.org/#secret",
            "https://vpn.example.org/%0aX", "https://", "" }) {
            try { VpnProfilePreflight.openConnect(value); fail("Invalid gateway accepted"); }
            catch (VpnProfilePreflight.Invalid e) { assertEquals("gateway", e.getMessage()); }
        }
    }
}

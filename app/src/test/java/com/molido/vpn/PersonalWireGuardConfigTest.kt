package com.molido.vpn

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PersonalWireGuardConfigTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun entry(): ImportedVpnProfiles.Entry {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        return ImportedVpnProfiles.Entry("12345678-1234-1234-1234-123456789012", "Proton", ImportedVpnProfiles.WIREGUARD,
            "[Interface]\nPrivateKey=$key\nAddress=10.2.0.2/32\nDNS=10.2.0.1\n[Peer]\nPublicKey=$key\nAllowedIPs=0.0.0.0/0,::/0\nEndpoint=198.51.100.8:51820\n")
    }
    @Test fun providerIdentityNeverInheritsWarpTeamDiscoveryOrObfuscation() {
        context.getSharedPreferences("settings", 0).edit().clear()
            .putString("team", "unrelated").putString("obfuscation_profile", "firewall").commit()
        val json = JSONObject(PersonalWireGuardConfig.coreJson(context, entry()))
        assertEquals("wireguard", json.getString("protocol"))
        assertTrue(json.getBoolean("wireguard_import_only"))
        assertFalse(json.getBoolean("forced_peers_scan_fallback"))
        assertFalse(json.getBoolean("retry_obfuscation_profiles"))
        assertFalse(json.has("team")); assertFalse(json.has("obfuscation_parameters"))
        assertEquals("off", json.getString("obfuscation_profile"))
        assertEquals("10.2.0.1", json.getString("dns_servers"))
        assertEquals("198.51.100.8:51820", json.getString("forced_peers"))
        val identity = java.io.File(json.getString("wireguard_config_path"))
        assertEquals(context.cacheDir.canonicalFile, identity.parentFile.canonicalFile)
        assertTrue(identity.readText().contains("ipv6 = \"\""))
        assertFalse(json.toString().contains(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })))
        identity.delete()
    }
    @Test fun unsupportedProxyModeFailsBeforeWritingProviderIdentity() {
        context.getSharedPreferences("settings", 0).edit().clear()
            .putString(CoreConfig.TUNNEL_MODE_PREF, CoreConfig.TUNNEL_MODE_PROXY).commit()
        try { PersonalWireGuardConfig.coreJson(context, entry()); fail("Proxy mode accepted") }
        catch (expected: IllegalStateException) { assertEquals("vpn_mode", expected.message) }
    }
}

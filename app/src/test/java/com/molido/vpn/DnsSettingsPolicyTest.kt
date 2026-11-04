package com.molido.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsSettingsPolicyTest {
    @Test fun secureModeNeverBypassesForGamingDnsWithoutConsent() {
        assertFalse(
            DnsSettings.shouldBypassTunnelForGamingDns(
                gamingPreset = true,
                explicitConsent = false,
                sdkInt = 35,
            ),
        )
    }

    @Test fun explicitConsentAllowsGamingDnsBypassOnAndroid13OrNewer() {
        assertTrue(
            DnsSettings.shouldBypassTunnelForGamingDns(
                gamingPreset = true,
                explicitConsent = true,
                sdkInt = 33,
            ),
        )
        assertTrue(
            DnsSettings.shouldBypassTunnelForGamingDns(
                gamingPreset = true,
                explicitConsent = true,
                sdkInt = 35,
            ),
        )
    }

    @Test fun noGamingPresetNeverCreatesDnsBypass() {
        assertFalse(
            DnsSettings.shouldBypassTunnelForGamingDns(
                gamingPreset = false,
                explicitConsent = true,
                sdkInt = 35,
            ),
        )
    }

    @Test fun olderAndroidFallsBackToTunnelDnsEvenAfterConsent() {
        assertFalse(
            DnsSettings.shouldBypassTunnelForGamingDns(
                gamingPreset = true,
                explicitConsent = true,
                sdkInt = 32,
            ),
        )
    }
}

package com.molido.vpn

import android.content.Context

/**
 * User DNS choice: Automatic or one of the optional Iranian gaming resolvers.
 *
 * Gaming DNS is direct by design on Android 13+ and therefore bypasses the VPN
 * for those resolver IPs. That behavior is separately gated by an explicit
 * per-device consent bit; selecting/migrating a preset alone is not consent.
 * Any selection path that omits the explicit flag clears consent, including
 * automation/import flows. Secure tunnel DNS remains the default.
 */
object DnsSettings {

    const val PREF = "dns_preset"
    const val DIRECT_GAMING_ALLOWED_PREF = "direct_gaming_dns_allowed"

    enum class Preset(
        val key: String,
        val enLabel: String,
        val servers: List<String>,
        val gaming: Boolean = false,
    ) {
        AUTO("auto", "Automatic", emptyList()),
        RADAR("radar", "Radar Game", listOf("10.202.10.10", "10.202.10.11"), gaming = true),
        ELECTRO("electro", "Electro", listOf("78.157.42.100", "78.157.42.101"), gaming = true),
        SHECAN("shecan", "Shecan", listOf("178.22.122.100", "185.51.200.2"), gaming = true),
        ONLINE403("403", "403.online", listOf("10.202.10.202", "10.202.10.102"), gaming = true);

        val label: String get() = Strings.t(enLabel)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun preset(context: Context): Preset {
        val key = prefs(context).getString(PREF, Preset.AUTO.key)
        return Preset.entries.firstOrNull { it.key == key } ?: Preset.AUTO
    }

    fun byKey(key: String?): Preset? = Preset.entries.firstOrNull { it.key == key }

    /** True only after the user accepts the direct-DNS disclosure for a gaming preset. */
    fun directGamingDnsAllowed(context: Context): Boolean =
        prefs(context).getBoolean(DIRECT_GAMING_ALLOWED_PREF, false)

    fun setPreset(
        context: Context,
        preset: Preset,
        explicitlyAllowDirectGamingDns: Boolean = false,
    ) {
        prefs(context).edit()
            .putString(PREF, preset.key)
            .putBoolean(
                DIRECT_GAMING_ALLOWED_PREF,
                preset.gaming && explicitlyAllowDirectGamingDns,
            )
            .apply()
    }

    /** Pure routing gate, kept separate so the fail-closed policy is unit-testable. */
    fun shouldBypassTunnelForGamingDns(
        gamingPreset: Boolean,
        explicitConsent: Boolean,
        sdkInt: Int,
        android13Api: Int = 33,
    ): Boolean = gamingPreset && explicitConsent && sdkInt >= android13Api
}

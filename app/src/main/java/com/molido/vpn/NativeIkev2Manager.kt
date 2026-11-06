package com.molido.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.net.VpnProfileState
import android.os.Build

/** Android OS-managed IKEv2/IPsec. Do not layer this tunnel over MolidoVpnService. */
class NativeIkev2Manager(context: Context) {
    private val app = context.applicationContext
    private val manager: VpnManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) app.getSystemService(VpnManager::class.java) else null
    data class Credentials(val username: String, val password: String, val identity: String)
    private var sessionKey: String? = null
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && app.packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS) && manager != null
    fun provision(host: String, credentials: Credentials): Intent? {
        check(isSupported()) { "Android 11+ with IPsec support is required" }
        require(validHost(host)) { "Invalid IKEv2 server hostname" }
        require(credentials.username.isNotBlank() && credentials.password.isNotEmpty() && credentials.identity.isNotBlank()) { "IKEv2 credentials and identity are required" }
        val profile = Ikev2VpnProfile.Builder(host, credentials.identity).setAuthUsernamePassword(credentials.username, credentials.password, null).setBypassable(false).setMetered(false).build()
        return manager!!.provisionVpnProfile(profile)
    }
    fun start(): String? {
        check(isSupported()) { "IKEv2/IPsec is not supported" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager!!.startProvisionedVpnProfileSession().also { sessionKey = it }
        } else {
            @Suppress("DEPRECATION")
            manager!!.startProvisionedVpnProfile()
            sessionKey = null
            null
        }
    }
    /** null means API 30–32: Android does not expose an app-scoped session state there. */
    fun isOwnSessionConnected(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val expected = sessionKey ?: return false
        val state = manager!!.provisionedVpnProfileState ?: return false
        return state.sessionId == expected && state.state == VpnProfileState.STATE_CONNECTED
    }

    fun stop() {
        if (isSupported()) {
            manager!!.stopProvisionedVpnProfile()
            sessionKey = null
        }
    }
    fun remove() { if (isSupported()) { manager!!.stopProvisionedVpnProfile(); manager!!.deleteProvisionedVpnProfile() } }
    internal fun validHost(host: String): Boolean = isValidIkev2Host(host)

}

internal fun isValidIkev2Host(host: String): Boolean {
    if (host.isBlank() || host.length > 253 || host.any { it.isWhitespace() } ||
        host.startsWith(".") || host.endsWith(".") || host.contains("..")) return false

    if (host.contains(":")) {
        return runCatching {
            val address = java.net.InetAddress.getByName(host)
            address is java.net.Inet6Address
        }.getOrDefault(false)
    }

    if (host.all { it.isDigit() || it == '.' }) {
        val octets = host.split(".")
        return octets.size == 4 && octets.all { part ->
            val value = part.toIntOrNull()
            part.isNotEmpty() && part.length <= 3 &&
                part.all { it.isDigit() } && value != null && value in 0..255
        }
    }

    val labels = host.split(".")
    return labels.size >= 2 && labels.all { label ->
        label.length in 1..63 &&
            label.matches(Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$"))
    }
}

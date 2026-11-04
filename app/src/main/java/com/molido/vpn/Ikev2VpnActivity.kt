package com.molido.vpn

import android.app.Activity
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * IKEv2/IPsec via Android's built-in IPsec stack (API 30+).
 *
 * Credentials are passed to the platform profile manager and are never written
 * to Molido preferences, logs, backups or app-owned files. Android owns the VPN
 * interface and will revoke MolidoVpnService's VpnService tunnel when this
 * system-managed profile takes over.
 */
class Ikev2VpnActivity : Activity() {
    private lateinit var server: EditText
    private lateinit var ikeIdentity: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private var pendingProfile: Ikev2VpnProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 20.dp(), 24.dp(), 24.dp())
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = Strings.t("IKEv2 / IPsec")
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = Strings.t("Uses Android's built-in IKEv2/IPsec engine (Android 11+). Bring your own IKEv2 server and account. This is a separate system VPN and cannot run alongside another VPN.")
            textSize = 14f
            setPadding(0, 12.dp(), 0, 16.dp())
        })

        server = field(root, "Server hostname or IP")
        ikeIdentity = field(root, "IKE identity")
        username = field(root, "EAP username")
        password = field(root, "EAP password").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        status = TextView(this).apply {
            text = Strings.t("Credentials are not saved by MolidoVPN. Android stores the provisioned system VPN profile.")
            textSize = 13f
            setPadding(0, 12.dp(), 0, 12.dp())
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = Strings.t("Connect with IKEv2")
            setOnClickListener { provisionAndConnect() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = Strings.t("Disconnect IKEv2")
            setOnClickListener { stopProfile() }
        }, matchWidth())
    }

    private fun field(parent: LinearLayout, label: String): EditText {
        parent.addView(TextView(this).apply {
            text = Strings.t(label)
            textSize = 13f
            setPadding(0, 8.dp(), 0, 4.dp())
        })
        return EditText(this).also { input ->
            input.singleLine = true
            input.hint = Strings.t(label)
            parent.addView(input, matchWidth())
        }
    }

    private fun provisionAndConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            message("IKEv2 requires Android 11 or newer.")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)) {
            message("This device does not provide the Android IPsec tunnel feature.")
            return
        }

        val host = server.text.toString().trim().trimEnd('.')
        val eapUser = username.text.toString()
        val eapPass = password.text.toString()
        val identity = ikeIdentity.text.toString().ifBlank { eapUser }.trim()
        if (!validServer(host) || identity.isBlank() || eapUser.isBlank() || eapPass.isBlank()) {
            message("Enter a valid server, IKE identity, EAP username and password.")
            return
        }

        try {
            val profile = Ikev2VpnProfile.Builder(host, identity)
                .setAuthUsernamePassword(eapUser, eapPass, null)
                .setBypassable(false)
                .setMetered(true)
                .build()
            pendingProfile = profile
            val manager = getSystemService(VpnManager::class.java)
            val consent = manager.provisionVpnProfile(profile)
            if (consent != null) {
                startActivityForResult(consent, REQUEST_VPN_CONSENT)
            } else {
                startProvisioned(manager)
            }
        } catch (e: SecurityException) {
            message("Android denied IKEv2 profile provisioning. Check VPN permission and device policy.")
        } catch (e: IllegalArgumentException) {
            message("The IKEv2 profile parameters were rejected. Check the server and identity.")
        } catch (e: Exception) {
            message("Could not provision the IKEv2 profile on this device.")
        } finally {
            // Do not retain credential-bearing EditText contents longer than needed.
            password.text?.clear()
        }
    }

    @Deprecated("The platform consent flow uses Activity result APIs on API 30+.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_CONSENT) return
        if (resultCode != RESULT_OK) {
            pendingProfile = null
            message("IKEv2 VPN permission was not granted.")
            return
        }
        startProvisioned(getSystemService(VpnManager::class.java))
    }

    private fun startProvisioned(manager: VpnManager) {
        try {
            val profile = pendingProfile ?: run {
                message("No IKEv2 profile is ready.")
                return
            }
            // Provision again after consent: the profile is in memory only until
            // Android accepts it; never persist it in app preferences.
            manager.provisionVpnProfile(profile)
            if (Build.VERSION.SDK_INT >= 33) {
                manager.startProvisionedVpnProfileSession()
            } else {
                @Suppress("DEPRECATION")
                manager.startProvisionedVpnProfile()
            }
            status.text = Strings.t("IKEv2 connection requested. Confirm the VPN status in Android's system indicator.")
            pendingProfile = null
        } catch (e: SecurityException) {
            message("Android refused to start the IKEv2 profile.")
        } catch (e: Exception) {
            message("IKEv2 startup failed. Check server reachability and authentication.")
        }
    }

    private fun stopProfile() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            getSystemService(VpnManager::class.java).stopProvisionedVpnProfile()
            status.text = Strings.t("IKEv2 disconnect requested.")
        } catch (e: Exception) {
            message("Could not stop the IKEv2 profile.")
        }
    }

    private fun validServer(value: String): Boolean {
        if (value.isBlank() || value.length > 253 || value.any { it.isWhitespace() }) return false
        if (android.util.Patterns.IP_ADDRESS.matcher(value).matches()) return true
        val ascii = try { java.net.IDN.toASCII(value) } catch (_: IllegalArgumentException) { return false }
        return ascii.length <= 253 && android.util.Patterns.DOMAIN_NAME.matcher(ascii).matches()
    }

    private fun message(value: String) {
        status.text = Strings.t(value)
        Toast.makeText(this, Strings.t(value), Toast.LENGTH_LONG).show()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = 8.dp() }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_VPN_CONSENT = 7301
    }
}

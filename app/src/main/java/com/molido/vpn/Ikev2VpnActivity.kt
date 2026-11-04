package com.molido.vpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
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
import android.os.Handler
import android.os.Looper
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var serverRootCa: X509Certificate? = null
    private lateinit var directoryStatus: TextView
    private lateinit var freeServerList: LinearLayout
    private lateinit var refreshDirectoryButton: Button
    private var refreshingDirectory = false
    private val directoryHandler = Handler(Looper.getMainLooper())
    private val directoryAutoRefresh = object : Runnable {
        override fun run() {
            refreshFreeDirectory(showMessage = false)
            directoryHandler.postDelayed(this, DIRECTORY_REFRESH_INTERVAL_MS)
        }
    }

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
        ikeIdentity = field(root, "Server identity (Remote ID)")
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
            text = Strings.t("Import server CA certificate (PEM)")
            setOnClickListener {
                android.app.AlertDialog.Builder(this@Ikev2VpnActivity)
                    .setTitle(Strings.t("Trust server CA certificate?"))
                    .setMessage(Strings.t("A CA certificate can authorize VPN server identities. Import only the certificate downloaded from the provider's official site; a malicious CA could impersonate the VPN server."))
                    .setNegativeButton(Strings.t("Cancel"), null)
                    .setPositiveButton(Strings.t("Choose certificate")) { _, _ ->
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            },
                            REQUEST_CA_CERTIFICATE,
                        )
                    }
                    .show()
            }
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = Strings.t("Free community servers are third-party services, not operated by Molido. Availability and account lifetime can change. The provider may process registration usernames and passwords under its own privacy policy. Do not use these servers for sensitive traffic.")
            textSize = 13f
            setPadding(0, 12.dp(), 0, 8.dp())
        })
        directoryStatus = TextView(this).apply {
            text = Strings.t("Loading free IKEv2 server directory…")
            textSize = 13f
            setPadding(0, 8.dp(), 0, 8.dp())
        }
        root.addView(directoryStatus)
        refreshDirectoryButton = Button(this).apply {
            text = Strings.t("Refresh free IKEv2 servers")
            setOnClickListener { refreshFreeDirectory(showMessage = true) }
        }
        root.addView(refreshDirectoryButton, matchWidth())
        freeServerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(freeServerList, matchWidth())
        renderCachedDirectory()
        scheduleBackgroundDirectoryRefresh()

        root.addView(Button(this).apply {
            text = Strings.t("Connect with IKEv2")
            setOnClickListener { provisionAndConnect() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = Strings.t("Disconnect IKEv2")
            setOnClickListener { stopProfile() }
        }, matchWidth())
    }

    override fun onResume() {
        super.onResume()
        renderCachedDirectory()
        refreshFreeDirectory(showMessage = false)
        directoryHandler.removeCallbacks(directoryAutoRefresh)
        directoryHandler.postDelayed(directoryAutoRefresh, DIRECTORY_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        directoryHandler.removeCallbacks(directoryAutoRefresh)
        super.onPause()
    }

    private fun refreshFreeDirectory(showMessage: Boolean) {
        if (refreshingDirectory) return
        refreshingDirectory = true
        refreshDirectoryButton.isEnabled = false
        directoryStatus.text = Strings.t("Refreshing free IKEv2 server directory…")
        Thread {
            try {
                val servers = Ikev2FreeServerDirectory.refresh()
                runOnUiThread {
                    saveDirectoryCache(servers)
                    renderDirectory(servers, System.currentTimeMillis())
                    directoryStatus.text = Strings.tf(
                        "Directory updated %s · %s servers · refreshes every 5 minutes while this screen is open",
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                        servers.size,
                    )
                    refreshingDirectory = false
                    refreshDirectoryButton.isEnabled = true
                    if (showMessage) Toast.makeText(this, Strings.t("Free IKEv2 server list updated."), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    val cached = loadDirectoryCache()
                    if (cached.first.isNotEmpty()) {
                        renderDirectory(cached.first, cached.second)
                        directoryStatus.text = Strings.t("Could not refresh now; showing the last saved server list.")
                    } else {
                        directoryStatus.text = Strings.t("Could not load the free IKEv2 directory. Check internet and retry.")
                    }
                    refreshingDirectory = false
                    refreshDirectoryButton.isEnabled = true
                    if (showMessage) Toast.makeText(this, Strings.t("Server list refresh failed."), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveDirectoryCache(servers: List<Ikev2FreeServer>) {
        Ikev2FreeServerCache.save(this, servers)
    }

    private fun loadDirectoryCache(): Pair<List<Ikev2FreeServer>, Long> =
        Ikev2FreeServerCache.load(this)

    private fun scheduleBackgroundDirectoryRefresh() {
        val scheduler = getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(
            DIRECTORY_REFRESH_JOB_ID,
            ComponentName(this, Ikev2DirectoryRefreshJob::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(DIRECTORY_BACKGROUND_REFRESH_INTERVAL_MS)
            .build()
        scheduler.schedule(job)
    }

    private fun renderCachedDirectory() {
        val (servers, updatedAt) = loadDirectoryCache()
        if (servers.isNotEmpty()) renderDirectory(servers, updatedAt)
    }

    private fun renderDirectory(servers: List<Ikev2FreeServer>, updatedAt: Long) {
        freeServerList.removeAllViews()
        if (servers.isEmpty()) {
            freeServerList.addView(TextView(this).apply {
                text = Strings.t("No free IKEv2 servers are currently listed by this provider.")
                textSize = 13f
            })
            return
        }
        servers.forEach { item ->
            val state = if (item.listedAvailable) Strings.t("Provider page reports available") else Strings.t("Check current status on provider site")
            freeServerList.addView(Button(this).apply {
                text = item.country + " · " + item.host + "\n" + state
                isAllCaps = false
                setOnClickListener {
                    server.setText(item.host)
                    ikeIdentity.setText(item.identity)
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.accountUrl)))
                    } catch (_: Exception) {
                        message("Could not open the provider account page.")
                    }
                }
            }, matchWidth())
        }
        if (updatedAt > 0L) {
            directoryStatus.text = Strings.tf(
                "Last directory update %s · %s servers",
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updatedAt)),
                servers.size,
            )
        }
    }

    private fun field(parent: LinearLayout, label: String): EditText {
        parent.addView(TextView(this).apply {
            text = Strings.t(label)
            textSize = 13f
            setPadding(0, 8.dp(), 0, 4.dp())
        })
        return EditText(this).also { input ->
            input.setSingleLine(true)
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
        val identity = ikeIdentity.text.toString().ifBlank { host }.trim()
        if (host.endsWith(".vpnjantit.com", ignoreCase = true) && serverRootCa == null) {
            message("Import the official VPN Jantit CA certificate before connecting to a free community server.")
            return
        }
        if (!Ikev2ProfileValidation.isValidServer(host) ||
            !Ikev2ProfileValidation.hasEapCredentials(identity, eapUser, eapPass)) {
            message("Enter a valid server, IKE identity, EAP username and password.")
            return
        }

        try {
            val profileBuilder = Ikev2VpnProfile.Builder(host, identity)
                .setAuthUsernamePassword(eapUser, eapPass, serverRootCa)
                .setBypassable(false)
                .setMetered(true)
            if (Build.VERSION.SDK_INT >= 33) {
                profileBuilder.setRequiresInternetValidation(true)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                profileBuilder.setAutomaticIpVersionSelectionEnabled(true)
            }
            val profile = profileBuilder.build()
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
        if (requestCode == REQUEST_CA_CERTIFICATE) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_CA_BYTES) { "CA certificate file is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("Empty CA certificate")
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
                require(cert.basicConstraints >= 0) { "The selected certificate is not a CA" }
                serverRootCa = cert
                status.text = Strings.t("Server CA certificate imported for this session.")
            } catch (_: Exception) {
                serverRootCa = null
                message("Could not read a valid CA certificate. Select a PEM or DER CA certificate.")
            }
            return
        }
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
            // provisionVpnProfile() already installed this profile and returned
            // the consent intent. After RESULT_OK, start that provisioned profile.
            // Do not provision it a second time or risk re-triggering consent.
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
        private const val DIRECTORY_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        private const val DIRECTORY_PREFS = "ikev2_free_directory"
        private const val DIRECTORY_JSON = "servers_json"
        private const val DIRECTORY_UPDATED_AT = "updated_at"
        private const val DIRECTORY_REFRESH_JOB_ID = 80421
        private const val DIRECTORY_BACKGROUND_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        private const val REQUEST_CA_CERTIFICATE = 7302
        private const val MAX_CA_BYTES = 256 * 1024
    }
}

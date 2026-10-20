package com.molido.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Prepare imports without advertising an unavailable tunnel engine. */
class VpnProfilesActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var palette: AppAppearance.Palette
    private val buttons = mutableListOf<Button>()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(palette.ink)
        typeface = Typefaces.regular(this@VpnProfilesActivity)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun action(value: String, run: () -> Unit) = Button(this).apply {
        text = Strings.t(value)
        setOnClickListener { run() }
        buttons.add(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        palette = AppAppearance.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (AppLanguage.current() == "fa") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(palette.canvas)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(20), dp(24) + insets.systemWindowInsetTop,
                dp(20), dp(24) + insets.systemWindowInsetBottom)
            insets
        }
        root.addView(label("OpenVPN / AnyConnect").apply { textSize = 24f })
        root.addView(label(Strings.t("VPN_PROFILES_PREPARATION")))
        root.addView(action("Import OpenVPN file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, IMPORT_OPENVPN)
        })
        root.addView(action("Add AnyConnect address") { addGateway() })
        status = label("")
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(action("Close") { finish() })
        setContentView(root)
        load()
    }

    private fun background(task: () -> Unit) {
        buttons.forEach { it.isEnabled = false }
        status.text = Strings.t("VPN_PROFILES_LOADING")
        worker.execute {
            val result = runCatching {
                task()
                ImportedVpnProfiles.entries(applicationContext)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                buttons.forEach { it.isEnabled = true }
                result.fold(::render) { failure ->
                    // Never display provider exceptions, URIs, configuration contents or secrets.
                    val reason = (failure as? VpnProfilePreflight.Invalid)?.reason
                        ?: (failure as? ImportedVpnProfiles.SaveError)?.reason
                    val key = when (reason) {
                        "size" -> "VPN_PROFILES_SIZE"
                        "external" -> "VPN_PROFILES_EXTERNAL"
                        "unsupported" -> "VPN_PROFILES_UNSUPPORTED"
                        "gateway" -> "VPN_PROFILES_GATEWAY"
                        "limit" -> "VPN_PROFILES_LIMIT"
                        "duplicate" -> "VPN_PROFILES_DUPLICATE"
                        null -> "VPN_PROFILES_STORAGE_ERROR"
                        else -> "VPN_PROFILES_INVALID"
                    }
                    status.text = Strings.t(key)
                }
            }
        }
    }
    private fun load() = background {}

    private fun render(entries: List<ImportedVpnProfiles.Entry>) {
        list.removeAllViews()
        status.text = Strings.t(if (entries.isEmpty()) "VPN_PROFILES_EMPTY" else "VPN_PROFILES_SAVED")
        entries.forEach { entry ->
            val type = if (entry.kind == ImportedVpnProfiles.OPENVPN) "OpenVPN" else "AnyConnect"
            list.addView(label("${entry.name} · $type\n${Strings.t("VPN_PROFILES_NOT_TESTED")}").apply {
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    AlertDialog.Builder(this@VpnProfilesActivity)
                        .setTitle(Strings.t("Delete profile?"))
                        .setMessage(entry.name)
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .setPositiveButton(Strings.t("Delete")) { _, _ ->
                            background { ImportedVpnProfiles.delete(applicationContext, entry.id) }
                        }.show()
                }
            })
        }
    }

    private fun addGateway() {
        val name = EditText(this).apply {
            hint = Strings.t("Profile name")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val address = EditText(this).apply {
            hint = "https://vpn.example.com"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(name)
            addView(address)
        }
        val dialog = AlertDialog.Builder(this).setTitle(Strings.t("Add AnyConnect address"))
            .setView(layout).setNegativeButton(Strings.t("Cancel"), null)
            .setPositiveButton(Strings.t("Save"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.isNullOrBlank()) { name.error = Strings.t("Profile name"); return@setOnClickListener }
                val gateway = runCatching { VpnProfilePreflight.openConnect(address.text.toString()) }.getOrNull()
                if (gateway == null) { address.error = Strings.t("VPN_PROFILES_GATEWAY"); return@setOnClickListener }
                val title = name.text.toString()
                dialog.dismiss()
                background { ImportedVpnProfiles.add(applicationContext, title, ImportedVpnProfiles.OPENCONNECT, gateway) }
            }
        }
        dialog.show()
    }

    @Deprecated("Document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_OPENVPN || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        background {
            val bytes = contentResolver.openInputStream(uri)?.use {
                VpnProfilePreflight.readBounded(it, VpnProfilePreflight.MAX_BYTES)
            } ?: error("File unavailable")
            val checked = VpnProfilePreflight.openVpn(bytes)
            ImportedVpnProfiles.add(applicationContext, "OpenVPN ${System.currentTimeMillis()}",
                ImportedVpnProfiles.OPENVPN, checked.content)
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
    private companion object { const val IMPORT_OPENVPN = 7101 }
}

package com.molido.vpn

import android.app.AlertDialog
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * User-configured Dynamic DNS updates.
 *
 * Credentials are encrypted with an Android Keystore AES/GCM key. Provider
 * endpoints are fixed HTTPS origins; user input is only ever sent as encoded
 * parameters or JSON values, never used to construct a hostname or URL origin.
 */
internal object DdnsManager {
    private const val PREFS = "molido_ddns"
    private const val CONFIG = "encrypted_config"
    private const val KEY_ALIAS = "molido.ddns.aes.v1"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000

    enum class Provider(val label: String) {
        DUCKDNS("DuckDNS"),
        NOIP("No-IP"),
        CLOUDFLARE("Cloudflare")
    }

    data class Config(
        val provider: Provider,
        val hostname: String,
        val token: String,
        val zoneId: String = "",
        val recordId: String = ""
    )

    fun summary(context: Context): String =
        load(context)?.let { "${it.provider.label} · ${it.hostname}" } ?: Strings.t("Not configured")

    fun show(context: Context) {
        val saved = runCatching { load(context) }.getOrNull()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
        }
        val provider = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, Provider.entries.map { it.label })
            setSelection(saved?.provider?.ordinal ?: 0)
        }
        fun field(hintText: String, value: String = "", secret: Boolean = false): EditText =
            EditText(context).apply {
                hint = hintText
                setSingleLine(true)
                setText(value)
                if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        val hostname = field("Hostname (example.duckdns.org)", saved?.hostname.orEmpty())
        val token = field("API token / username:password", saved?.token.orEmpty(), true)
        val zone = field("Cloudflare Zone ID (Cloudflare only)", saved?.zoneId.orEmpty())
        val record = field("Cloudflare DNS Record ID (Cloudflare only)", saved?.recordId.orEmpty())
        root.addView(provider)
        root.addView(hostname)
        root.addView(token)
        root.addView(zone)
        root.addView(record)
        val help = TextView(context).apply {
            text = "Supports DuckDNS, No-IP and Cloudflare. Credentials stay encrypted on this device. Updates use HTTPS."
            textSize = 12f
            setPadding(0, 12, 0, 4)
        }
        root.addView(help)
        AlertDialog.Builder(context)
            .setTitle("Dynamic DNS (DDNS)")
            .setView(root)
            .setNeutralButton("Update now") { _, _ ->
                val cfg = runCatching {
                    Config(
                        Provider.entries[provider.selectedItemPosition],
                        hostname.text.toString().trim(),
                        token.text.toString().trim(),
                        zone.text.toString().trim(),
                        record.text.toString().trim()
                    ).also(::validate)
                }.getOrElse {
                    AlertDialog.Builder(context).setTitle("DDNS configuration error")
                        .setMessage(it.message ?: "Invalid configuration").setPositiveButton("OK", null).show()
                    return@setNeutralButton
                }
                save(context, cfg)
                Thread {
                    val result = runCatching { update(cfg) }
                    android.os.Handler(context.mainLooper).post {
                        val message = result.fold(
                            onSuccess = { "DDNS updated successfully: ${cfg.hostname}" },
                            onFailure = { "DDNS update failed: ${it.message ?: "network error"}" }
                        )
                        AlertDialog.Builder(context).setTitle("DDNS status").setMessage(message)
                            .setPositiveButton("OK", null).show()
                    }
                }.apply { isDaemon = true; name = "molido-ddns-update"; start() }
            }
            .setNegativeButton("Close", null)
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    Config(
                        Provider.entries[provider.selectedItemPosition],
                        hostname.text.toString().trim(),
                        token.text.toString().trim(),
                        zone.text.toString().trim(),
                        record.text.toString().trim()
                    ).also(::validate)
                }.onSuccess { save(context, it) }
                    .onFailure {
                        AlertDialog.Builder(context).setTitle("DDNS configuration error")
                            .setMessage(it.message ?: "Invalid configuration").setPositiveButton("OK", null).show()
                    }
            }
            .show()
    }

    fun updateSaved(context: Context): Result<Unit> = runCatching {
        val config = load(context) ?: error("DDNS is not configured")
        update(config)
    }

    private fun validate(config: Config) {
        require(config.hostname.length in 4..253) { "Enter a valid DDNS hostname." }
        require(config.hostname.matches(Regex("(?i)^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")) &&
            !config.hostname.contains("..") && config.hostname.contains('.')) { "Hostname is invalid." }
        require(config.token.length in 8..512) { "Enter a valid provider token or password." }
        if (config.provider == Provider.CLOUDFLARE) {
            require(config.zoneId.matches(Regex("^[a-fA-F0-9]{32}$"))) { "Cloudflare Zone ID must be 32 hexadecimal characters." }
            require(config.recordId.matches(Regex("^[a-fA-F0-9]{32}$"))) { "Cloudflare DNS Record ID must be 32 hexadecimal characters." }
        }
    }

    private fun update(config: Config) {
        validate(config)
        when (config.provider) {
            Provider.DUCKDNS -> {
                val query = "domains=${enc(config.hostname.substringBefore('.'))}&token=${enc(config.token)}&ip="
                val response = request("https://www.duckdns.org/update?$query", "GET")
                check(response.trim().equals("OK", true)) { "DuckDNS rejected the update: ${response.take(160)}" }
            }
            Provider.NOIP -> {
                val connection = URL("https://dynupdate.no-ip.com/nic/update?hostname=${enc(config.hostname)}").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", "MolidoVPN-DDNS/1.0 support@molido.invalid")
                    val credentials = config.token.split(":", limit = 2)\n                    require(credentials.size == 2 && credentials.all { it.isNotBlank() }) { "For No-IP, enter username:password in the credential field." }\n                    val auth = Base64.encodeToString(config.token.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                    connection.setRequestProperty("Authorization", "Basic $auth")
                    val body = readResponse(connection)
                    check(connection.responseCode in 200..299 && (body.startsWith("good") || body.startsWith("nochg"))) {
                        "No-IP rejected the update: ${body.take(160)}"
                    }
                } finally { connection.disconnect() }
            }
            Provider.CLOUDFLARE -> {
                val ip = request("https://api.ipify.org", "GET").trim()
                require(ip.matches(Regex("^[0-9a-fA-F:.]{3,45}$"))) { "Could not determine a valid public IP address." }
                val payload = JSONObject().put("type", if (ip.contains(':')) "AAAA" else "A")
                    .put("name", config.hostname).put("content", ip).put("ttl", 120).put("proxied", false)
                val connection = URL("https://api.cloudflare.com/client/v4/zones/${config.zoneId}/dns_records/${config.recordId}")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "PUT"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer ${config.token}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
                    val body = readResponse(connection)
                    val json = JSONObject(body)
                    check(connection.responseCode in 200..299 && json.optBoolean("success")) {
                        "Cloudflare rejected the update: ${json.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "API error"}"
                    }
                } finally { connection.disconnect() }
            }
        }
    }

    private fun request(address: String, method: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "MolidoVPN-DDNS/1.0")
            val body = readResponse(connection)
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}: ${body.take(160)}" }
            return body
        } finally { connection.disconnect() }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(4096) }.orEmpty()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun save(context: Context, config: Config) {
        val json = JSONObject().put("provider", config.provider.name).put("hostname", config.hostname)
            .put("token", config.token).put("zoneId", config.zoneId).put("recordId", config.recordId).toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CONFIG, encrypt(json)).apply()
    }

    private fun load(context: Context): Config? {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONFIG, null) ?: return null
        val json = JSONObject(decrypt(encrypted))
        return Config(
            Provider.valueOf(json.getString("provider")),
            json.getString("hostname"),
            json.getString("token"),
            json.optString("zoneId"),
            json.optString("recordId")
        ).also(::validate)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Stored DDNS configuration is corrupt." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }
}

package com.molido.vpn

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Private provider export, independent of the app's provisioned WARP identity. */
object PersonalWireGuardConfig {
    fun coreJson(context: Context, entry: ImportedVpnProfiles.Entry): String {
        require(entry.kind == ImportedVpnProfiles.WIREGUARD)
        require(entry.id.matches(Regex("[0-9a-f-]{36}")))
        check(!CoreConfig.proxyOnly(context)) { "vpn_mode" }
        val profile = PersonalWireGuard.parse(entry.payload.toByteArray(Charsets.UTF_8))
        val file = File(context.cacheDir, "personal-wireguard-${entry.id}.toml")
        val identity = buildString {
            append("device_id = \"personal-wireguard\"\naccess_token = \"\"\n")
            append("ipv4 = \"").append(profile.ipv4).append("\"\n")
            append("ipv6 = \"").append(profile.ipv6).append("\"\n")
            append("wg_private_key = \"").append(profile.privateKey).append("\"\n")
            append("wg_peer_public_key = \"").append(profile.publicKey).append("\"\n")
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
            output.write(identity.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
        return JSONObject(CoreConfig.json(context, "wireguard")).apply {
            put("config_path", file.absolutePath)
            put("wireguard_config_path", file.absolutePath)
            put("wireguard_import_only", true)
            put("wireguard_data_check", true)
            remove("forced_peer")
            put("forced_peers", profile.endpoint)
            put("forced_peers_scan_fallback", false)
            put("obfuscation_profile", "off")
            remove("obfuscation_parameters")
            put("retry_obfuscation_profiles", false)
            put("dns_servers", profile.dns.joinToString(","))
            listOf("team", "access_client_id", "access_client_secret", "access_token", "access_email")
                .forEach { remove(it) }
            put("gateway", false)
        }.toString()
    }
}

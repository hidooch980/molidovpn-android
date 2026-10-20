package com.molido.vpn

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local preparation only; profiles are not Smart Connect candidates. */
object ImportedVpnProfiles {
    const val OPENVPN = "openvpn"
    const val OPENCONNECT = "openconnect"
    private const val MAX_PROFILES = 20
    private val idPattern = Regex("[0-9a-f-]{36}")
    // No generated toString/copy: the payload may contain private keys.
    class Entry(val id: String, val name: String, val kind: String, val payload: String)
    class SaveError(val reason: String) : IllegalStateException(reason)

    private fun directory(context: Context): File =
        File(context.noBackupFilesDir, "imported_vpn_profiles").apply {
            check(isDirectory || mkdirs()) { "Profile storage unavailable" }
        }

    @Synchronized
    fun entries(context: Context): List<Entry> = directory(context).listFiles().orEmpty()
        .filter { it.extension == "vault" && idPattern.matches(it.nameWithoutExtension) }
        .sortedBy { it.name }
        .map { file ->
            val encrypted = AtomicFile(file).openRead().use { input ->
                VpnProfilePreflight.readBounded(input, VpnProfilePreflight.MAX_BYTES * 2)
                    .toString(Charsets.UTF_8)
            }
            val json = JSONObject(SecureStore.decryptProfile(encrypted))
            check(json.getInt("version") == 1)
            val kind = json.getString("kind")
            check(kind == OPENVPN || kind == OPENCONNECT)
            Entry(file.nameWithoutExtension, json.getString("name"), kind, json.getString("payload"))
        }

    @Synchronized
    fun add(context: Context, name: String, kind: String, payload: String) {
        val content = when (kind) {
            OPENVPN -> VpnProfilePreflight.openVpn(payload.toByteArray(Charsets.UTF_8)).content
            OPENCONNECT -> VpnProfilePreflight.openConnect(payload)
            else -> error("Unsupported profile type")
        }
        val existing = entries(context)
        if (existing.size >= MAX_PROFILES) throw SaveError("limit")
        if (existing.any { it.kind == kind && it.payload == content }) throw SaveError("duplicate")
        val cleanName = name.trim().filter { !it.isISOControl() }.take(80)
        require(cleanName.isNotBlank()) { "Profile name required" }
        val json = JSONObject().put("version", 1).put("name", cleanName)
            .put("kind", kind).put("payload", content).toString()
        val encrypted = SecureStore.encryptProfile(json)
        if (encrypted.toByteArray(Charsets.UTF_8).size > VpnProfilePreflight.MAX_BYTES * 2)
            throw SaveError("size")
        val file = AtomicFile(File(directory(context), UUID.randomUUID().toString() + ".vault"))
        val output = file.startWrite()
        try {
            output.write(encrypted.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        require(idPattern.matches(id))
        AtomicFile(File(directory(context), "$id.vault")).delete()
    }
}

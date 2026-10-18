package com.molido.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where the in-app updater looks, and how it checks what it got. Pure (unit tested); the network
 * and PackageManager parts stay in [AppUpdater].
 *
 * Order: our worker → GitHub API → every configured mirror ([ResilienceConfig.updateMirrors], e.g. a
 * second worker on a custom domain, an R2 public bucket, an ArvanCloud bucket). A mirror is a plain
 * folder holding `latest.json` (GitHub "latest release" shape) next to the APK files.
 *
 * Trust: a mirror is never trusted. The APK must (1) match the SHA-256 the metadata lists, when it
 * lists one (GitHub's `digest`, or `sha256` that our workflow writes), and (2) ALWAYS be signed with
 * the same certificate as the installed app — checked in [AppUpdater] before the installer opens.
 */
object UpdateSources {

    const val WORKER_RELEASE_URL = "https://molido-sub.hidooch980.workers.dev/app/latest.json"
    const val GITHUB_RELEASE_URL = "https://api.github.com/repos/hidooch980/molidovpn/releases/latest"

    data class Release(val version: String, val assetName: String, val downloadUrl: String, val sha256: String?)

    fun releaseJsonUrls(mirrors: List<String>): List<String> =
        (listOf(WORKER_RELEASE_URL, GITHUB_RELEASE_URL) + mirrors.map { "${it.trimEnd('/')}/latest.json" }).distinct()

    /** The release's own URL first, then the same file name on every mirror. */
    fun downloadUrls(release: Release, mirrors: List<String>): List<String> =
        (listOf(release.downloadUrl) + mirrors.map { "${it.trimEnd('/')}/${release.assetName}" }).distinct()

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** `"digest": "sha256:<hex>"` (GitHub) or `"sha256": "<hex>"` (our mirrors); null when absent/invalid. */
    fun assetSha256(asset: JSONObject): String? {
        val raw = asset.optString("sha256", "").ifEmpty {
            asset.optString("digest", "").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:").orEmpty()
        }.trim().lowercase()
        return raw.takeIf { HEX64.matches(it) }
    }

    fun abiToken(abi: String): String = when (abi) {
        "arm64-v8a" -> "android-arm64"
        "armeabi-v7a" -> "android-armv7"
        else -> "android-universal"
    }

    /** Picks the APK for the first supported ABI that has one. Null = no compatible asset. */
    fun parseRelease(text: String, abis: List<String>): Release? {
        val json = JSONObject(text)
        val version = json.getString("tag_name").removePrefix("v")
        val assets: JSONArray = json.getJSONArray("assets")
        for (abi in abis + "universal") {
            val token = abiToken(abi)
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk") && name.contains(token, ignoreCase = true)) {
                    return Release(version, name, asset.getString("browser_download_url"), assetSha256(asset))
                }
            }
        }
        return null
    }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    /** Same signer set, compared as SHA-256 of each certificate. Empty on either side = no match. */
    fun sameSigners(a: List<ByteArray>, b: List<ByteArray>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        fun set(list: List<ByteArray>) = list.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        }.toSet()
        return set(a) == set(b)
    }
}

package com.molido.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.net.URI

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

    const val MAX_APK_BYTES: Long = 300L * 1024L * 1024L
    fun withinApkSizeLimit(declaredBytes: Long, receivedBytes: Long): Boolean =
        (declaredBytes < 0L || declaredBytes <= MAX_APK_BYTES) &&
            receivedBytes in 0L..MAX_APK_BYTES

    const val WORKER_RELEASE_URL = "https://molido-sub.hidooch980.workers.dev/app/latest.json"
    const val GITHUB_RELEASE_URL = "https://api.github.com/repos/hidooch980/molidovpn/releases/latest"

    data class Release(
        val version: String,
        val assetName: String,
        val downloadUrl: String,
        val sha256: String?,
        /** Owner marked the release "[critical]": prompt at once. */
        val critical: Boolean = false,
        /** Human-readable release notes, shown before installation and once after upgrade. */
        val notes: List<String> = emptyList(),
    )

    /** Reject plaintext, credential-bearing, malformed, and non-web URLs before network access. */
    fun isSecureHttpsUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
    } catch (_: Exception) {
        false
    }

    fun releaseJsonUrls(mirrors: List<String>): List<String> =
        (listOf(LiveSync.RAW + "latest.json", LiveSync.CDN + "latest.json", StaticFirst.RAW + "latest.json", StaticFirst.CDN + "latest.json", GITHUB_RELEASE_URL, WORKER_RELEASE_URL) +
            mirrors.map { "${it.trimEnd('/')}/latest.json" }).filter(::isSecureHttpsUrl).distinct()

    /**
     * The first source that answers wins, except that a static copy (live/sub branch, refreshed every few
     * minutes by a cron job) saying "nothing newer" is double-checked once against GitHub's own API, so a
     * just-published release is not hidden behind a stale copy. The Worker is never asked for that.
     */
    fun latest(urls: List<String>, installed: String, isNewer: (String, String) -> Boolean, fetch: (String) -> Release?): Release? {
        var lastError: Exception? = null
        for (url in urls) {
            val release = try { fetch(url) } catch (e: Exception) { lastError = e; continue }
            val static = url.startsWith(LiveSync.RAW) || url.startsWith(LiveSync.CDN) ||
                url.startsWith(StaticFirst.RAW) || url.startsWith(StaticFirst.CDN)
            if (static && (release == null || !isNewer(release.version, installed))) {
                val fresh = runCatching { fetch(GITHUB_RELEASE_URL) }.getOrNull()
                if (fresh != null && isNewer(fresh.version, installed)) return fresh
            }
            return release
        }
        throw lastError ?: IllegalStateException("no release source")
    }

    /** Extract concise, user-facing bullet points from GitHub release Markdown. */
    fun releaseNotes(markdown: String): List<String> = markdown.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") }
        .map { it.drop(2).trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
        .map { if (it.length > 240) it.take(237).trimEnd() + "…" else it }
        .distinct()
        .take(8)
        .toList()

    /** The release's own URL first, then the same file name on every mirror. */
    fun downloadUrls(release: Release, mirrors: List<String>): List<String> =
        (listOf(release.downloadUrl) + mirrors.map { "${it.trimEnd('/')}/${release.assetName}" })
            .filter(::isSecureHttpsUrl).distinct()

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
        val body = json.optString("body", "")
        val critical = json.optBoolean("critical", false) || body.contains("[critical]", ignoreCase = true)
        val notes = releaseNotes(body)
        for (abi in abis + "universal") {
            val token = abiToken(abi)
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk") && name.contains(token, ignoreCase = true)) {
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (!isSecureHttpsUrl(downloadUrl)) continue
                    return Release(version, name, downloadUrl, assetSha256(asset), critical, notes)
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

    /**
     * Accept only the installed current signer or a cryptographically verified Android signing
     * lineage that explicitly carries it forward. Multi-signer APKs must match the complete signer
     * set; a shared certificate is never enough in that case.
     */
    fun isValidSignerTransition(
        installedCurrent: List<ByteArray>,
        installedHasMultipleSigners: Boolean,
        candidateCurrent: List<ByteArray>,
        candidateHistory: List<ByteArray>,
        candidateHasMultipleSigners: Boolean,
    ): Boolean {
        if (installedCurrent.isEmpty() || candidateCurrent.isEmpty()) return false
        if (installedHasMultipleSigners || candidateHasMultipleSigners) {
            return installedHasMultipleSigners && candidateHasMultipleSigners &&
                sameSigners(installedCurrent, candidateCurrent)
        }
        if (installedCurrent.size != 1 || candidateCurrent.size != 1) return false
        return candidateHistory.any { it.contentEquals(installedCurrent.single()) }
    }

    /** Same signer set, compared as SHA-256 of each certificate. Empty on either side = no match. */
    fun sameSigners(a: List<ByteArray>, b: List<ByteArray>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        fun set(list: List<ByteArray>) = list.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        }.toSet()
        return set(a) == set(b)
    }
}

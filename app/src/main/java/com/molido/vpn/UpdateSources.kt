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
 * Order: static GitHub mirrors → GitHub Releases API → configured mirrors
 * ([ResilienceConfig.updateMirrors], e.g. R2 or ArvanCloud). A mirror is a plain
 * folder holding `latest.json` (GitHub "latest release" shape) next to the APK files.
 *
 * Trust: a mirror is never trusted. The APK must (1) include a valid SHA-256 in release metadata
 * (`digest` from GitHub or `sha256` from a configured mirror) and match it byte-for-byte, and (2) ALWAYS be
 * signed with the same certificate lineage as the installed app — checked in [AppUpdater] before
 * the installer opens. Missing or malformed hashes make an APK ineligible for installation.
 */
object UpdateSources {

    const val MAX_APK_BYTES: Long = 300L * 1024L * 1024L
    const val MAX_METADATA_BYTES: Long = 2L * 1024L * 1024L
    fun withinMetadataSizeLimit(declaredBytes: Long, receivedBytes: Long): Boolean =
        (declaredBytes < 0L || declaredBytes <= MAX_METADATA_BYTES) &&
            receivedBytes in 0L..MAX_METADATA_BYTES

    fun withinApkSizeLimit(declaredBytes: Long, receivedBytes: Long): Boolean =
        (declaredBytes < 0L || declaredBytes <= MAX_APK_BYTES) &&
            receivedBytes in 0L..MAX_APK_BYTES

    const val GITHUB_RELEASE_URL = "https://api.github.com/repos/hidooch980/molidovpn-android/releases/latest"

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
        (listOf(LiveSync.RAW + "latest.json", LiveSync.CDN + "latest.json", StaticFirst.RAW + "latest.json", StaticFirst.CDN + "latest.json", GITHUB_RELEASE_URL) +
            mirrors.map { "${it.trimEnd('/')}/latest.json" }).filter(::isSecureHttpsUrl).distinct()

    /**
     * Try every configured source until one has a release newer than the installed app.
     * Static copies are double-checked against GitHub, but a stale or empty copy must not
     * prevent GitHub and user-configured mirrors from being tried.
     */
    fun latest(urls: List<String>, installed: String, isNewer: (String, String) -> Boolean, fetch: (String) -> Release?): Release? {
        var lastError: Exception? = null
        var successfulFetch = false
        var githubChecked = false
        var best: Release? = null

        fun consider(candidate: Release?) {
            val currentBest = best
            if (candidate != null && isNewer(candidate.version, installed) &&
                (currentBest == null || isNewer(candidate.version, currentBest.version))) {
                best = candidate
            }
        }

        for (url in urls) {
            if (url == GITHUB_RELEASE_URL && githubChecked) continue
            val release = try {
                fetch(url).also { successfulFetch = true }
            } catch (e: Exception) {
                lastError = e
                continue
            }
            val static = url.startsWith(LiveSync.RAW) || url.startsWith(LiveSync.CDN) ||
                url.startsWith(StaticFirst.RAW) || url.startsWith(StaticFirst.CDN)
            consider(release)
            if (static && !githubChecked) {
                // Static copies may lag even when they already contain a version newer than installed.
                githubChecked = true
                val fresh = try {
                    fetch(GITHUB_RELEASE_URL).also { successfulFetch = true }
                } catch (e: Exception) {
                    lastError = e
                    null
                }
                consider(fresh)
            }
        }
        if (best != null) return best
        if (!successfulFetch && lastError != null) throw lastError
        return null
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

    private data class SemanticVersion(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val prerelease: List<String>,
    )

    private val SEMVER = Regex(
        """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"""
    )

    /** Strict SemVer comparison; stable releases outrank prereleases and build metadata is ignored. */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = parseSemanticVersion(remote) ?: return false
        val l = parseSemanticVersion(local) ?: return false
        if (r.major != l.major) return r.major > l.major
        if (r.minor != l.minor) return r.minor > l.minor
        if (r.patch != l.patch) return r.patch > l.patch
        if (r.prerelease.isEmpty()) return l.prerelease.isNotEmpty()
        if (l.prerelease.isEmpty()) return false
        for (i in 0 until minOf(r.prerelease.size, l.prerelease.size)) {
            val a = r.prerelease[i]
            val b = l.prerelease[i]
            val aNumeric = a.all(Char::isDigit)
            val bNumeric = b.all(Char::isDigit)
            val comparison = when {
                aNumeric && bNumeric -> when {
                    a.length != b.length -> a.length.compareTo(b.length)
                    else -> a.compareTo(b)
                }
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
            if (comparison != 0) return comparison > 0
        }
        return r.prerelease.size > l.prerelease.size
    }

    private fun parseSemanticVersion(value: String): SemanticVersion? {
        val match = SEMVER.matchEntire(value.removePrefix("v")) ?: return null
        val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
        if (prerelease.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
        return SemanticVersion(
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].toLongOrNull() ?: return null,
            prerelease = prerelease,
        )
    }

    fun isValidReleaseVersion(value: String): Boolean = parseSemanticVersion(value) != null

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
    fun parseRelease(text: String, abis: List<String>, television: Boolean = false): Release? {
        val json = JSONObject(text)
        val version = json.getString("tag_name").removePrefix("v")
        if (!isValidReleaseVersion(version)) return null
        val assets: JSONArray = json.getJSONArray("assets")
        val body = json.optString("body", "")
        val critical = json.optBoolean("critical", false) || body.contains("[critical]", ignoreCase = true)
        val notes = releaseNotes(body)
        for (abi in abis + "universal") {
            val baseToken = abiToken(abi)
            val token = if (television) "android-tv-${baseToken.removePrefix("android-")}" else baseToken
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk") && name.contains(token, ignoreCase = true)) {
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (!isSecureHttpsUrl(downloadUrl)) continue
                    // No APK is installable unless release metadata supplies a valid SHA-256.
                    // GitHub and configured release mirrors publish this field.
                    val sha256 = assetSha256(asset) ?: continue
                    return Release(version, name, downloadUrl, sha256, critical, notes)
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

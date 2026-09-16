package com.molido.vpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.os.Build
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * "Check for updates": finds a newer release (our worker first, GitHub second), then downloads the
 * right APK for this CPU inside the app and opens the system installer. The owner chose in-app install
 * over the browser hand-off; the one-time "install unknown apps" permission is requested when needed.
 * The browser is only a fallback when the in-app download fails.
 */
class AppUpdater(private val activity: Activity) {
    private val worker = Executors.newSingleThreadExecutor()
    private var progressDialog: AlertDialog? = null
    private var busy = false

    /** [silent]: the launch-time check — no spinner, and only speaks up when a newer version exists. */
    fun checkForUpdate(silent: Boolean = false) {
        if (busy) return
        busy = true
        if (!silent) showProgress(Strings.t("Checking for updates"))
        worker.execute {
            val result = runCatching(::latestRelease)
            activity.runOnUiThread {
                dismissProgress()
                busy = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val release = result.getOrNull()
                if (silent) {
                    if (release != null && isNewer(release.version, appVersion())) announceUpdate(release)
                    return@runOnUiThread
                }
                result.onFailure { showMessage(Strings.t("Update check failed"), it.message ?: Strings.t("Try again later")) }
                    .onSuccess { found ->
                        when {
                            found == null -> showMessage(Strings.t("No update available"), Strings.t("No compatible release was found"))
                            !isNewer(found.version, appVersion()) ->
                                showMessage(Strings.t("You're up to date"), Strings.tf("MolidoVPN %s is installed", appVersion()))
                            else -> announceUpdate(found)
                        }
                    }
            }
        }
    }

    /** The new-version notice. */
    private fun announceUpdate(release: Release) {
        dialogBuilder()
            .setTitle(Strings.t("Update available"))
            .setMessage(
                Strings.tf("MolidoVPN %s has been released. You are on %s.", release.version, appVersion()) + "\n\n" +
                    Strings.t("Tapping Update downloads and installs it inside the app.")
            )
            .setNegativeButton(Strings.t("Later"), null)
            .setPositiveButton(Strings.t("Update")) { _, _ -> openDownload(release) }
            .show()
    }

    /** APK already downloaded but waiting for the "install unknown apps" permission. */
    private var pendingApk: File? = null
    private var downloadBar: ProgressBar? = null

    /**
     * In-app update: downloads the APK with a progress bar, checks it really is a newer build of this
     * app (a stale cached download is rejected instead of silently reinstalling the old version), then
     * hands it to the system installer. The browser is only a fallback when the download fails.
     */
    private fun openDownload(release: Release) {
        val url = release.downloadUrl + (if ('?' in release.downloadUrl) '&' else '?') + "v=${release.version}"
        showDownloadProgress()
        worker.execute {
            val result = runCatching { downloadApk(url, release) }
            activity.runOnUiThread {
                dismissProgress()
                downloadBar = null
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { install(it) }.onFailure { error ->
                    dialogBuilder()
                        .setTitle(Strings.t("Download failed"))
                        .setMessage((error.message ?: "") + "\n\n" + Strings.t("Download it from the website instead?"))
                        .setNegativeButton(Strings.t("Later"), null)
                        .setPositiveButton(Strings.t("Open website")) { _, _ ->
                            if (!openLink(url)) openLink(RELEASES_PAGE_URL)
                        }
                        .show()
                }
            }
        }
    }

    private fun downloadApk(url: String, release: Release): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "MolidoVPN-${release.version}.apk")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "MolidoVPN-Android")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            var received = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) {
                            val percent = (received * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                activity.runOnUiThread { updateDownloadProgress(percent) }
                            }
                        }
                    }
                }
            }
            check(total <= 0 || received == total) { "incomplete download ($received / $total)" }
        } finally {
            connection.disconnect()
        }
        // Never install something that is not a newer build of this very app.
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(target.path, 0)
        check(archive != null && archive.packageName == activity.packageName) { Strings.t("The downloaded file is damaged") }
        val current = pm.getPackageInfo(activity.packageName, 0)
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive!!.longVersionCode else archive!!.versionCode.toLong()
        @Suppress("DEPRECATION")
        val oldCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        check(newCode > oldCode) { Strings.t("The downloaded file is not newer than this version") }
        return target
    }

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            dialogBuilder()
                .setTitle(Strings.t("One-time permission"))
                .setMessage(Strings.t("Allow MolidoVPN to install updates, then come back — installation continues automatically."))
                .setNegativeButton(Strings.t("Later"), null)
                .setPositiveButton(Strings.t("Allow")) { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        )
                    } catch (_: ActivityNotFoundException) {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                }
                .show()
            return
        }
        pendingApk = null
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Called from the activity's onResume: finishes an install that was waiting for the permission. */
    fun resumePendingInstall() {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        if (apk.exists()) install(apk) else pendingApk = null
    }

    private fun showDownloadProgress() {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        downloadBar = bar
        layout.addView(bar)
        progressDialog = dialogBuilder()
            .setTitle(Strings.t("Downloading update"))
            .setView(layout)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun updateDownloadProgress(percent: Int) {
        val bar = downloadBar ?: return
        bar.isIndeterminate = false
        bar.progress = percent
        progressDialog?.setTitle(Strings.t("Downloading update") + " " + percent + "%")
    }

    private fun openLink(url: String): Boolean = try {
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Our Cloudflare worker first (reachable where GitHub is filtered), then GitHub itself. */
    private fun latestRelease(): Release? {
        var lastError: Exception? = null
        for (url in listOf(MIRROR_RELEASE_URL, RELEASE_URL)) {
            try {
                return fetchRelease(url)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("no release source")
    }

    private fun fetchRelease(url: String): Release? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MolidoVPN-Android")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub returned ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { reader -> reader.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            val apk = Build.SUPPORTED_ABIS.asSequence()
                .mapNotNull { abi -> assetForAbi(assets, abi) }
                .firstOrNull()
                ?: return null
            return Release(version, apk.first, apk.second)
        } finally {
            connection.disconnect()
        }
    }

    private fun assetForAbi(assets: org.json.JSONArray, abi: String): Pair<String, String>? {
        // MolidoVPN release assets are named MobinVPN-android-arm64.apk / -armv7.apk / -universal.apk.
        val token = when (abi) {
            "arm64-v8a" -> "android-arm64"
            "armeabi-v7a" -> "android-armv7"
            else -> "android-universal"
        }
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            if (name.endsWith(".apk") && name.contains(token, ignoreCase = true)) {
                return name to asset.getString("browser_download_url")
            }
        }
        return null
    }

    /**
     * Indeterminate spinner for the version check.
     *
     * There is no determinate case left now that the APK download belongs to the
     * browser, so the old `indeterminate` parameter and the progress-percentage
     * plumbing are gone with it.
     */
    private fun showProgress(title: String) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        layout.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        })
        progressDialog = dialogBuilder()
            .setTitle(title)
            .setView(layout)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun showMessage(title: String, message: String) {
        dialogBuilder().setTitle(title).setMessage(message).setPositiveButton(Strings.t("OK"), null).show()
    }

    // The overlay is picked per palette. Passing a fixed one here overrides the
    // activity theme's own `materialAlertDialogTheme`, so on Porcelain the
    // updater was the one black dialog in an otherwise white app.
    private fun dialogBuilder() = MaterialAlertDialogBuilder(
        activity,
        if (AppAppearance.isNight(activity)) R.style.ThemeOverlay_Molido_AlertDialog
        else R.style.ThemeOverlay_Molido_Light_AlertDialog,
    )

    private fun appVersion(): String = activity.packageManager
        .getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private data class Release(val version: String, val assetName: String, val downloadUrl: String)

    private companion object {
        const val RELEASE_HOST = "api.github.com"
        const val MIRROR_RELEASE_URL = "https://molido-sub.hidooch980.workers.dev/app/latest.json"
        const val RELEASE_URL ="https://$RELEASE_HOST/repos/hidooch980/molidovpn/releases/latest"
        const val RELEASES_PAGE_URL = "https://github.com/hidooch980/molidovpn/releases/latest"

        fun isNewer(remote: String, local: String): Boolean {
            val remoteParts = remote.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            val localParts = local.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
                val comparison = remoteParts.getOrElse(index) { 0 }.compareTo(localParts.getOrElse(index) { 0 })
                if (comparison != 0) return comparison > 0
            }
            return false
        }
    }
}

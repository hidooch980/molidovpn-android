package com.molido.vpn

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** "What's new" after an update: once, when versionName differs from the last one seen. Never throws. */
object WhatsNew {
    private const val KEY = "last_seen_version"
    private const val NOTES_VERSION = "release_notes_version"
    private const val NOTES_JSON = "release_notes_json"

    /** Cache trusted release text before the installer opens; app preferences survive APK upgrades. */
    fun saveReleaseNotes(context: Context, version: String, notes: List<String>) {
        if (version.isBlank() || notes.isEmpty()) return
        val json = JSONArray().apply { notes.take(8).forEach { put(it.take(240)) } }.toString()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(NOTES_VERSION, version)
            .putString(NOTES_JSON, json)
            .apply()
    }

    /** Returns the version if this start follows an update (not a fresh install), else null. Marks it seen. */
    fun pendingVersion(context: Context): String? = try {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = prefs.getString(KEY, "") ?: ""
        prefs.edit().putString(KEY, version).apply()
        if (last.isEmpty() || last == version || version.isEmpty()) null else version
    } catch (_: Exception) {
        null
    }

    /** Blocking; call off the main thread. Falls back to a generic line. */
    fun items(context: Context, version: String): List<String> {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getString(NOTES_VERSION, "") == version) {
            val cached = runCatching {
                val arr = JSONArray(prefs.getString(NOTES_JSON, "[]") ?: "[]")
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) return cached
        }
        val fetched = try {
            val body = StaticFirst.get(StaticFirst.urls("changelog.json", "/app/changelog.json?v=$version")) {
                runCatching { JSONObject(it) }.isSuccess
            }
            run {
                if (body == null) emptyList() else {
                    val arr = JSONObject(body).optJSONArray("items")
                    (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
                        val p = o.optString("platform", "all")
                        if (p == "all" || p == "android" || p.isEmpty()) o.optString("text").takeIf { it.isNotBlank() } else null
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        return fetched.ifEmpty { listOf("نسخهٔ $version نصب شد", "بهبود پایداری و سرعت") }
    }
}

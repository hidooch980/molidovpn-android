package com.molido.vpn

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** "What's new" after an update: once, when versionName differs from the last one seen. Never throws. */
object WhatsNew {
    private const val KEY = "last_seen_version"

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
    fun items(version: String): List<String> {
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

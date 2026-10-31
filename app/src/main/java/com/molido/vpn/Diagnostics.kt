package com.molido.vpn

import android.content.Context
import android.os.Build
import com.molido.vpn.engine.DiagBuffer
import com.molido.vpn.engine.InstallIdFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** This install's random ID ([InstallIdFormat]). Encrypted at rest; carried in settings backups. */
object InstallId {
    private const val KEY = "install_id"
    @Volatile private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val stored = runCatching { SecureStore.getSecret(app, KEY) }.getOrDefault("")
            val id = if (InstallIdFormat.valid(stored)) stored else InstallIdFormat.generate().also {
                runCatching { SecureStore.putSecret(app, KEY, it) }
            }
            cached = id
            return id
        }
    }

    /** From a settings backup: the restored phone keeps its old ID, so its history stays together. */
    fun restore(context: Context, id: String) {
        if (!InstallIdFormat.valid(id)) return
        runCatching { SecureStore.putSecret(context.applicationContext, KEY, id) }
        cached = id
    }
}

/**
 * Per-install diagnostics: a local ring buffer of connection events and errors, uploaded compactly to
 * the Worker's /diag only when a connect fails (with the reports opt-in) or when the user taps
 * "Send report". At most one upload per hour (the Worker enforces it too); unsent events wait offline.
 */
object Diagnostics {
    private const val FILE = "diag.jsonl"
    private const val PREFS = "diag"
    private const val ENDPOINT = "https://molido-sub.hidooch980.workers.dev/diag"
    private const val EVERY_MS = 60 * 60 * 1000L

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun record(context: Context, kind: String, method: String?, msg: String) {
        try {
            val f = file(context)
            val lines = if (f.exists()) f.readLines() else emptyList()
            val net = runCatching { ConnectionReports.networkType(context) }.getOrNull()
            f.writeText(DiagBuffer.append(lines, DiagBuffer.event(System.currentTimeMillis(), kind, method, net, msg)).joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    /** Recent events for the "Send report" preview. */
    fun recent(context: Context): List<String> = runCatching { file(context).readLines() }.getOrDefault(emptyList())

    /** [userAsked]: the Send report button (works without the reports opt-in). Returns false when throttled. */
    fun upload(context: Context, userAsked: Boolean = false, done: ((Boolean) -> Unit)? = null): Boolean {
        val app = context.applicationContext
        if (!userAsked && !ConnectionReports.enabled(app)) return false
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (now - p.getLong("sent_ms", 0L) in 0 until EVERY_MS) { done?.invoke(false); return false }
        Thread({
            val ok = try { send(app) } catch (_: Exception) { false }
            if (ok) p.edit().putLong("sent_ms", System.currentTimeMillis()).apply()
            done?.invoke(ok)
        }, "diag-upload").apply { isDaemon = true }.start()
        return true
    }

    private fun send(context: Context): Boolean {
        val p = prefs(context)
        val pending = synchronized(this) { DiagBuffer.pending(recent(context), p.getLong("sent_at", 0L)) }
        if (pending.isEmpty()) return true
        val events = JSONArray().apply { pending.takeLast(60).forEach { put(JSONObject(it)) } }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val body = JSONObject()
            .put("install_id", InstallId.get(context))
            .put("platform", "android")
            .put("ver", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(60))
            .put("os", "Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            .put("op", runCatching { ConnectionReports.operator(context) }.getOrNull())
            .put("mccmnc", tm?.networkOperator?.takeIf { it.length in 5..6 } ?: JSONObject.NULL)
            .put("events", events)
            .toString()
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 204) {
                val last = pending.mapNotNull { runCatching { JSONObject(it).optLong("at") }.getOrNull() }.maxOrNull() ?: 0L
                p.edit().putLong("sent_at", last).apply()
            }
            code == 204 || code == 429 // 429: the Worker already has a report from this hour
        } finally {
            c.disconnect()
        }
    }
}

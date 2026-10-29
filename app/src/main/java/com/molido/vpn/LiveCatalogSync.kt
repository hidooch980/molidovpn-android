package com.molido.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.IdentityHashMap

object LiveCatalogSync {
    const val PREF = "live_catalog_sync"
    internal const val REMOTE_INTERVAL_MS = 5 * 60 * 1000L
    private const val LAST_ATTEMPT = "live_catalog_last_attempt"
    private val main = Handler(Looper.getMainLooper())
    private val owners = IdentityHashMap<Any, (() -> Unit)?>()
    private var app: Context? = null

    private val tick = object : Runnable {
        override fun run() {
            val context = app ?: return
            if (owners.isEmpty()) return
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val online = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getBoolean(PREF, true)
                if (online && enabled && claimRefresh(context)) {
                    V2raySubscription.refreshIfDue(context, maxAgeMs = REMOTE_INTERVAL_MS)
                    ShardSubscription.refreshIfDue(context)
                    RemotePolicy.refreshIfDue(context)
                    ResilienceConfig.refreshIfDue(context)
                    AppRemote.refreshIfDue(context) {
                        main.post { owners.values.toList().forEach { it?.invoke() } }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (owners.isNotEmpty()) main.postDelayed(this, 5_000L)
            }
        }
    }

    /** Also gate failed attempts and activity/service restarts, not only successful fetches. */
    @Synchronized
    internal fun claimRefresh(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previous = prefs.getLong(LAST_ATTEMPT, -1L)
        if (previous >= 0L && now - previous in 0 until REMOTE_INTERVAL_MS) return false
        prefs.edit().putLong(LAST_ATTEMPT, now).apply()
        return true
    }

    fun acquire(owner: Any, context: Context, onUpdated: (() -> Unit)? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val first = owners.isEmpty()
        owners[owner] = onUpdated
        app = context.applicationContext
        if (first) main.post(tick)
    }

    fun release(owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        owners.remove(owner)
        if (owners.isEmpty()) {
            main.removeCallbacks(tick)
            app = null
        }
    }
}

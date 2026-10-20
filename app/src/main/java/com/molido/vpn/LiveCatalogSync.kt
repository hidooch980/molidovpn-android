package com.molido.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.IdentityHashMap

object LiveCatalogSync {
    const val PREF = "live_catalog_sync"
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
                if (online && enabled) {
                    V2raySubscription.refreshIfDue(context, force = true)
                    ShardSubscription.refreshIfDue(context, force = true)
                    RemotePolicy.refreshIfDue(context, force = true)
                    ResilienceConfig.refreshIfDue(context, force = true)
                    AppRemote.refreshIfDue(context, force = true) {
                        main.post { owners.values.toList().forEach { it?.invoke() } }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (owners.isNotEmpty()) main.postDelayed(this, 5_000L)
            }
        }
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

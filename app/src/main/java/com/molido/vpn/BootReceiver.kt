package com.molido.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-registers the periodic catalog/subscription job after boot and app replacement.
 * The receiver performs no network I/O; JobScheduler owns all bounded background work.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ShardRefreshJob.schedule(context.applicationContext)
        }
    }
}

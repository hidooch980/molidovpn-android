package com.molido.vpn.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.molido.vpn.AppRemote
import com.molido.vpn.BackgroundScanner
import com.molido.vpn.RemotePolicy
import com.molido.vpn.ResilienceConfig
import com.molido.vpn.ShardSubscription
import com.molido.vpn.SmartSplitSub
import com.molido.vpn.TunnelStatus
import com.molido.vpn.V2raySubscription
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small deterministic agent coordinator. Skills are local code with explicit
 * permissions and budgets; no remote prompt or downloaded code is ever executed.
 */
object AgentRuntime {
    private const val PREFS = "molido_agent_runtime"
    private const val ENABLED = "enabled"
    private val running = AtomicBoolean(false)

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }

    fun runCycle(context: Context, onAppConfigUpdated: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!enabled(app) || !running.compareAndSet(false, true)) return
        try {
            val environment = environment(app)
            val now = System.currentTimeMillis()
            AgentSkillRegistry.automaticSkills().forEach { skill ->
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val key = "last_dispatch_${skill.id}"
                val checkpoint = AgentCheckpoint(prefs.getLong(key, 0L))
                if (!AgentSkillRegistry.isDue(skill, checkpoint, now, environment)) return@forEach

                // Claim before dispatch so process/activity restarts cannot storm upstreams.
                prefs.edit()
                    .putLong(key, now)
                    .putLong("dispatch_count_${skill.id}", prefs.getLong("dispatch_count_${skill.id}", 0L) + 1L)
                    .apply()
                try {
                    when (skill.id) {
                        "server-discovery" -> {
                            V2raySubscription.refreshIfDue(app, maxAgeMs = AgentSkillRegistry.FIVE_MINUTES_MS)
                            ShardSubscription.refreshIfDue(app, maxAgeMs = AgentSkillRegistry.FIVE_MINUTES_MS)
                            SmartSplitSub.refreshIfDue(app, maxAgeMs = AgentSkillRegistry.FIVE_MINUTES_MS)
                        }
                        "policy-manager" -> {
                            RemotePolicy.refreshIfDue(app)
                            ResilienceConfig.refreshIfDue(app)
                        }
                        "app-update-manager" -> AppRemote.refreshIfDue(app, onUpdated = onAppConfigUpdated)
                    }
                    prefs.edit().remove("last_error_${skill.id}").putLong("last_requested_${skill.id}", now).apply()
                } catch (error: Exception) {
                    prefs.edit()
                        .putString("last_error_${skill.id}", error.javaClass.simpleName)
                        .putLong("last_error_at_${skill.id}", now)
                        .apply()
                }
            }
        } finally {
            running.set(false)
        }
    }

    fun dispatchCount(context: Context, skillId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("dispatch_count_$skillId", 0L)

    fun lastRequestedAt(context: Context, skillId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("last_requested_$skillId", 0L)

    fun lastError(context: Context, skillId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_error_$skillId", null)

    private fun environment(context: Context): AgentEnvironment {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val metered = runCatching { connectivity?.isActiveNetworkMetered ?: true }.getOrDefault(true)
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryNotLow = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.let { it > 15 } ?: false
        val tunnelIdle = !TunnelStatus.isActive() || TunnelStatus.isProxyMode
        return AgentEnvironment(online, metered, batteryNotLow, tunnelIdle)
    }
}

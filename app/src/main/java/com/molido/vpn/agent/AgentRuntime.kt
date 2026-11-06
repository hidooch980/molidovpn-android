package com.molido.vpn.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.molido.vpn.AppRemote
import com.molido.vpn.RemotePolicy
import com.molido.vpn.ResilienceConfig
import com.molido.vpn.ShardSubscription
import com.molido.vpn.SmartSplitSub
import com.molido.vpn.TunnelStatus
import com.molido.vpn.V2raySubscription
import java.util.concurrent.atomic.AtomicBoolean

/** Snapshot suitable for a diagnostics screen; timestamps are epoch milliseconds. */
data class AgentSkillStatus(
    val id: String,
    val mode: AgentSkillMode,
    val dispatchCount: Long,
    val lastDispatchAt: Long,
    val lastCallReturnedAt: Long,
    val lastErrorAt: Long,
    val lastError: String?,
    val blockedReason: String?,
)

/**
 * Deterministic on-device coordinator. Skills are local code with explicit
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

    /**
     * Runs only AUTOMATIC skills. Each dispatch is claimed before work starts;
     * provider exceptions are isolated so one skill cannot prevent the others.
     * A recorded completion means the provider call returned, not necessarily
     * that a remote catalog changed (providers own validation/cache semantics).
     */
    fun runCycle(context: Context, onAppConfigUpdated: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!enabled(app) || !running.compareAndSet(false, true)) return
        try {
            val environment = environment(app)
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            AgentSkillRegistry.automaticSkills().forEach { skill ->
                val key = "last_dispatch_${skill.id}"
                val checkpoint = AgentCheckpoint(prefs.getLong(key, 0L))
                val now = System.currentTimeMillis()
                if (!AgentSkillRegistry.isDue(skill, checkpoint, now, environment)) return@forEach

                // Synchronous commit makes the claim survive abrupt process death.
                val claimed = prefs.edit()
                    .putLong(key, now)
                    .putLong("dispatch_count_${skill.id}", prefs.getLong("dispatch_count_${skill.id}", 0L) + 1L)
                    .commit()
                if (!claimed) return@forEach

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
                    prefs.edit()
                        .remove("last_error_${skill.id}")
                        .putLong("last_call_returned_${skill.id}", System.currentTimeMillis())
                        .apply()
                } catch (error: Exception) {
                    // Store only the exception class, never remote payloads or user data.
                    prefs.edit()
                        .putString("last_error_${skill.id}", error.javaClass.simpleName)
                        .putLong("last_error_at_${skill.id}", System.currentTimeMillis())
                        .apply()
                }
            }
        } finally {
            running.set(false)
        }
    }

    /** Read-only health snapshot for every registered skill. */
    fun statuses(context: Context, now: Long = System.currentTimeMillis()): List<AgentSkillStatus> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val env = environment(app)
        return AgentSkillRegistry.all.map { skill ->
            val id = skill.id
            val lastDispatch = prefs.getLong("last_dispatch_$id", 0L)
            val reason = when {
                !enabled(app) -> "agent_disabled"
                else -> AgentSkillRegistry.blockedReason(
                    skill, AgentCheckpoint(lastDispatch), now, env,
                )
            }
            AgentSkillStatus(
                id = id,
                mode = skill.mode,
                dispatchCount = prefs.getLong("dispatch_count_$id", 0L),
                lastDispatchAt = lastDispatch,
                lastCallReturnedAt = prefs.getLong("last_call_returned_$id", 0L),
                lastErrorAt = prefs.getLong("last_error_at_$id", 0L),
                lastError = prefs.getString("last_error_$id", null),
                blockedReason = reason,
            )
        }
    }

    fun dispatchCount(context: Context, skillId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("dispatch_count_$skillId", 0L)

    fun lastRequestedAt(context: Context, skillId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("last_dispatch_$skillId", 0L)

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
        // -1 means unavailable; fail closed rather than schedule battery-sensitive work.
        val batteryNotLow = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.let { it in 16..100 } ?: false
        val tunnelIdle = !TunnelStatus.isActive() || TunnelStatus.isProxyMode
        return AgentEnvironment(online, metered, batteryNotLow, tunnelIdle)
    }
}

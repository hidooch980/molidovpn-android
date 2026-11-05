package com.molido.vpn

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * Refreshes a configured DDNS record on a battery-conscious six-hour cadence.
 * No credentials are written to logs; failures leave the provider's last record
 * untouched and are retried by the next scheduled run.
 */
class DdnsRefreshJob : android.app.job.JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread({
            try {
                DdnsManager.updateSaved(applicationContext)
                    .onFailure { ConnectionLog.record("DDNS refresh failed: ${it.javaClass.simpleName}") }
            } finally {
                jobFinished(params, false)
            }
        }, "ddns-refresh").apply { isDaemon = true; start() }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 0x4D4444
        private const val PERIOD_MS = 6 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, DdnsRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .build()
            runCatching { scheduler.schedule(job) }
                .onFailure { ConnectionLog.record("DDNS job scheduling failed: ${it.javaClass.simpleName}") }
        }
    }
}

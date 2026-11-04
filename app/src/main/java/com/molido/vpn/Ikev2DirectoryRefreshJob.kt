package com.molido.vpn

import android.app.job.JobParameters
import android.app.job.JobService

/** Periodically refreshes only public endpoint metadata; never handles VPN credentials. */
class Ikev2DirectoryRefreshJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            try {
                val servers = Ikev2FreeServerDirectory.refresh()
                Ikev2FreeServerCache.save(applicationContext, servers)
            } catch (_: Exception) {
                // Keep the last known-good cache when the provider is unreachable.
            } finally {
                jobFinished(params, false)
            }
        }.apply {
            name = "Molido-IKEv2-directory-refresh"
            isDaemon = true
            start()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        return true
    }
}

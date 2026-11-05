package com.molido.vpn.ads

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mobile-only privacy-first ads bootstrap.
 *
 * It intentionally does nothing until a real AdMob App ID is supplied at build
 * time. Ads are never initialized before UMP has refreshed consent and confirmed
 * canRequestAds(). No VPN state, traffic, DNS, destination, or profile data is
 * read or passed to either SDK.
 */
object MobileAdsBootstrap {
    private const val APP_ID_META_DATA = "com.google.android.gms.ads.APPLICATION_ID"
    private val appIdPattern = Regex("^ca-app-pub-[0-9]{16}~[0-9]{10}$")
    private val initializationStarted = AtomicBoolean(false)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun requestConsent(activity: Activity) {
        val appId = configuredAppId(activity) ?: return
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val parameters = ConsentRequestParameters.Builder().build()

        // Google requires a fresh consent-information update on every app launch.
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    initializeIfAllowed(activity, consentInformation, appId)
                }
            },
            {
                // A transient update failure must not erase a still-valid prior
                // consent decision. UMP's canRequestAds() is the authority.
                initializeIfAllowed(activity, consentInformation, appId)
            },
        )
    }

    /** Call from a visible, user-initiated Privacy choices setting. */
    @JvmStatic
    fun showPrivacyOptions(activity: Activity, onComplete: (Boolean) -> Unit = {}) {
        if (configuredAppId(activity) == null) {
            onComplete(false)
            return
        }
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            onComplete(error == null)
        }
    }

    private fun initializeIfAllowed(
        activity: Activity,
        consentInformation: ConsentInformation,
        appId: String,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!consentInformation.canRequestAds()) return
        if (!initializationStarted.compareAndSet(false, true)) return

        val appContext = activity.applicationContext
        backgroundScope.launch {
            try {
                // Next-Gen SDK initialization must run off the main thread.
                MobileAds.initialize(
                    appContext,
                    InitializationConfig.Builder(appId).build(),
                ) {
                    // SDK initialized. Ad units are deliberately not requested
                    // here; the UI must first enforce the VPN-state placement gate.
                }
            } catch (_: Exception) {
                // Fail closed. VPN operation and app startup must never depend on ads.
                initializationStarted.set(false)
            }
        }
    }

    private fun configuredAppId(activity: Activity): String? {
        val metadata = try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.packageManager.getApplicationInfo(
                    activity.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                ).metaData
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getApplicationInfo(
                    activity.packageName,
                    PackageManager.GET_META_DATA,
                ).metaData
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val value = metadata?.getString(APP_ID_META_DATA)?.trim().orEmpty()
        return value.takeIf { appIdPattern.matches(it) }
    }
}

package com.molido.vpn.ads

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError

/**
 * A single anchored banner slot for the mobile home screen.
 *
 * The host must pass allowed=true only for DISCONNECTED. A transition to any
 * other VPN state destroys the view and its ad resources immediately.
 */
object MobileBannerSlot {
    private var activeView: AdView? = null
    private var activeContainer: FrameLayout? = null
    private var activeUnitId: String? = null
    private var attemptedThisDisconnectedPeriod = false

    @JvmStatic
    fun sync(activity: Activity, container: FrameLayout, allowed: Boolean) {
        if (!allowed || activity.isFinishing || activity.isDestroyed) {
            release(container, resetAttempt = true)
            return
        }
        if (!MobileAdsBootstrap.isReadyToRequestAds()) return
        val unitId = MobileAdsBootstrap.bannerAdUnitId(activity) ?: return
        if (activeView != null && activeContainer === container && activeUnitId == unitId) {
            container.visibility = View.VISIBLE
            return
        }
        if (attemptedThisDisconnectedPeriod) return

        release(container, resetAttempt = false)
        attemptedThisDisconnectedPeriod = true
        val view = AdView(activity)
        activeView = view
        activeContainer = container
        activeUnitId = unitId
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        container.visibility = View.VISIBLE

        val request = BannerAdRequest.Builder(unitId, AdSize.BANNER).build()
        view.loadAd(request, object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) {
                if (activeView !== view || activeContainer !== container) {
                    view.destroy()
                    return
                }
                container.visibility = View.VISIBLE
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                if (activeView === view) release(container, resetAttempt = false)
            }
        })
    }

    @JvmStatic
    fun hide(container: FrameLayout?) {
        if (container != null) release(container, resetAttempt = true)
        else {
            activeView?.destroy()
            activeView = null
            activeContainer?.removeAllViews()
            activeContainer?.visibility = View.GONE
            activeContainer = null
            activeUnitId = null
            attemptedThisDisconnectedPeriod = true
        }
    }

    private fun release(container: FrameLayout, resetAttempt: Boolean) {
        val view = activeView
        if (activeContainer === container) {
            activeView = null
            activeContainer = null
            activeUnitId = null
            view?.destroy()
        }
        container.removeAllViews()
        container.visibility = View.GONE
        if (resetAttempt) attemptedThisDisconnectedPeriod = false
    }
}

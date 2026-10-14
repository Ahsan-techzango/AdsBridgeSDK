package io.github.adsbridge.sdk

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.LiveData
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig

/** Host-controlled SDK facade; do not reference app billing, resources or Firebase. */
object AdsLibrary {
    @Volatile private var initialized = false
    /** Call once from Application.onCreate. App ID must match manifest meta-data. */
    fun initialize(context: Context, isPremium: Boolean, appId: String, onInitialized: (() -> Unit)? = null) {
        Policy.updated(isPremium)
        AdLogger.emit("sdk", "initialize")
        if (initialized) { onInitialized?.let { Ui.run(it) }; return }
        synchronized(this) {
            if (initialized) { onInitialized?.let { Ui.run(it) }; return }
            initialized = true
            Thread {
                try {
                    MobileAds.initialize(context.applicationContext, InitializationConfig.Builder(appId).build()) { }
                    onInitialized?.let { Ui.run(it) }
                } catch (t: Exception) {
                    initialized = false
                    android.util.Log.e("AdsLibrary", "Initialization failed", t)
                }
            }.start()
        }
    }
    /** Enable diagnostic events; Logcat is disabled by default to avoid release noise. */
    fun configureLogging(enabled: Boolean, logcat: Boolean = enabled) {
        AdLogger.enabled = enabled
        AdLogger.logcatEnabled = enabled && logcat
    }
    /** Optional host event observer. Always called on Android main thread when logging is enabled. */
    fun setAdLogListener(listener: ((AdLogEvent) -> Unit)?) { AdLogger.listener = listener }

    /** Only load-and-show cache misses trigger progress; preloads do not. */
    fun setAdLoadingListener(listener: ((AdLoadingEvent) -> Unit)?) {
        AdLoadingEvents.listener = listener
    }
    fun setPremiumUser(value: Boolean) = Policy.updated(value)
    fun isPremiumUser() = Policy.premium.get()
    fun isNetworkAvailable(context: Context) = context.networkUsable()
    fun setAdsEnabled(value: Boolean) { Policy.enabled = value; AdLogger.emit("sdk", "ads_enabled", detail = value.toString()); if (!value) clearAll() }
    fun setCooldownMillis(value: Long) { require(value >= 0); Policy.cooldownMs = value }
    fun setAnalyticsListener(listener: ((event: String, placement: String) -> Unit)?) { Policy.analytics = listener }
    fun clearAll() = Ui.run { FullscreenAds.clear(); NativeAds.clear(); BannerAds.clear(); AppOpenAds.clear() }
    fun preloadInterstitial(context: Context, adUnitId: String, placement: String, onResult: ((Boolean) -> Unit)? = null) =
        FullscreenAds.preload(context, adUnitId, placement, onResult)
    fun loadAndShowInterstitial(activity: Activity, adUnitId: String, placement: String,
                                useAnyCachedFallback: Boolean = true, showDelayMs: Long = 0,
                                callback: ((loaded: Boolean, failed: Boolean, showed: Boolean, dismissed: Boolean) -> Unit)? = null) =
        FullscreenAds.loadAndShow(activity, adUnitId, placement, useAnyCachedFallback, showDelayMs, callback)
    fun showAnyCachedInterstitial(activity: Activity, showDelayMs: Long = 0,
                                  callback: ((showed: Boolean, dismissed: Boolean, failed: Boolean) -> Unit)? = null) =
        FullscreenAds.showAny(activity, showDelayMs, callback)
    fun preloadRewarded(context: Context, adUnitId: String, onResult: ((Boolean) -> Unit)? = null) =
        FullscreenAds.preloadRewarded(context, adUnitId, onResult)
    fun loadAndShowRewarded(activity: Activity, adUnitId: String, showDelayMs: Long = 0,
                            callback: ((loaded: Boolean, failed: Boolean, showed: Boolean, dismissed: Boolean, rewarded: Boolean) -> Unit)? = null) =
        FullscreenAds.rewarded(activity, adUnitId, showDelayMs, callback)
    fun nativeState(placement: String): LiveData<NativeState> = NativeAds.observe(placement)
    fun preloadNativeAd(context: Context, adUnitId: String, placement: String = adUnitId) =
        NativeAds.preload(context, adUnitId, placement)
    fun loadAndShowAppOpenAd(
        activity: Activity,
        adUnitId: String,
        callback: ((
            showed: Boolean,
            dismissed: Boolean,
            failed: Boolean
        ) -> Unit)? = null
    ) = AppOpenAds.loadAndShow(
        activity = activity,
        id = adUnitId,
        cb = callback
    )
    fun showNative(activity: Activity, container: FrameLayout, placement: String,
                   style: Int = 1, colors: NativeColors? = null, onResult: ((Boolean) -> Unit)? = null) =
        NativeAds.show(activity, container, placement, style, colors, onResult)
    fun loadAndShowNativeAd(activity: Activity, adUnitId: String, container: FrameLayout,
                            style: Int = 1, colors: NativeColors? = null, onResult: ((Boolean) -> Unit)? = null,
                            shimmer: ShimmerOptions = ShimmerOptions()) =
        NativeAds.loadAndShow(activity, adUnitId, container, style, colors, onResult, shimmer)
    fun loadBanner(activity: Activity, container: FrameLayout, adUnitId: String,
                   collapsible: Boolean = false, top: Boolean = false, onResult: ((Boolean) -> Unit)? = null,
                   shimmer: ShimmerOptions = ShimmerOptions(heightDp = 60)) =
        BannerAds.load(activity, container, adUnitId, collapsible, top, onResult, shimmer)
    fun loadAppOpenAd(context: Context, adUnitId: String, onResult: ((Boolean) -> Unit)? = null) =
        AppOpenAds.load(context, adUnitId, onResult)
    fun showAppOpenAd(activity: Activity, callback: ((showed: Boolean, dismissed: Boolean, failed: Boolean) -> Unit)? = null) =
        AppOpenAds.show(activity, callback)
    fun clearPlacement(placement: String) = Ui.run { FullscreenAds.clearPlacement(placement); NativeAds.clearPlacement(placement) }
    fun removeAd(container: FrameLayout) = Ui.run { NativeAds.remove(container); BannerAds.remove(container) }
}

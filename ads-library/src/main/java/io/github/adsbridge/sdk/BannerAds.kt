package io.github.adsbridge.sdk

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Uses the Next-Gen AdView loading/registration and lifecycle contract. */
internal object BannerAds {
    // A session is valid only while it remains the current request for its container.
    private class Session(val adView: AdView, val completion: Completion) {
        var active = true
        var loaded = false
        var reported = false
        var attachListener: View.OnAttachStateChangeListener? = null
        fun result(ok: Boolean, callback: ((Boolean) -> Unit)?) {
            completion.finish { callback?.invoke(ok) }
        }
    }

    private val sessions = WeakHashMap<FrameLayout, Session>()

    fun clear() = Ui.run { sessions.keys.toList().forEach { remove(it) } }

    fun remove(container: FrameLayout) = Ui.run {
        val old = sessions.remove(container)
        if (old != null) {
            old.active = false
            old.attachListener?.let { container.removeOnAttachStateChangeListener(it) }
            old.adView.destroy() // Cancels active Next-Gen banner loads and frees resources.
        }
        AdShimmer.hide(container)
        container.removeAllViews()
        container.visibility = View.GONE
    }

    fun load(
        activity: Activity,
        container: FrameLayout,
        id: String,
        collapsible: Boolean,
        top: Boolean,
        onResult: ((Boolean) -> Unit)?,
        shimmer: ShimmerOptions = ShimmerOptions(heightDp = 60)
    ) = Ui.run {
        AdLogger.emit("banner", "load_requested")
        remove(container)
        if (!Ui.valid(activity) || !Policy.permitted() || !activity.networkUsable() || id.isBlank()) {
            AdLogger.emit("banner", "load_skipped", detail = "invalid_activity_premium_offline_or_empty_id")
            onResult?.invoke(false)
            return@run
        }

        // Anchored adaptive width follows the actual container, not the whole display.
        val widthPx = (container.width.takeIf { it > 0 }
            ?: (activity.resources.displayMetrics.widthPixels - container.paddingLeft - container.paddingRight))
            .coerceAtLeast(1)
        val widthDp = (widthPx / activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
        val requestBuilder = BannerAdRequest.Builder(id, adSize)
        if (collapsible) {
            requestBuilder.setGoogleExtrasBundle(Bundle().apply {
                putString("collapsible", if (top) "top" else "bottom")
            })
        }

        val adView = AdView(activity)
        val session = Session(adView, Completion())
        sessions[container] = session
        val containerRef = WeakReference(container)
        fun isCurrent(): Boolean = session.active &&
            containerRef.get()?.let { sessions[it] === session } == true

        // Next-Gen AdView registers the banner itself on successful load.
        container.addView(adView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        adView.visibility = View.INVISIBLE
        container.visibility = View.VISIBLE
        AdShimmer.show(container, shimmer)
        AdLogger.emit("banner", "shimmer_started")

        // Do not retain an Activity's AdView if its container goes away.
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (isCurrent()) containerRef.get()?.let { remove(it) }
            }
        }
        session.attachListener = listener
        container.addOnAttachStateChangeListener(listener)

        adView.loadAd(requestBuilder.build(), object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) = Ui.run {
                if (!isCurrent()) return@run
                val target = containerRef.get() ?: return@run
                if (!Policy.permitted() || !Ui.valid(activity)) {
                    remove(target)
                    session.result(false, onResult)
                    return@run
                }
                session.loaded = true
                ad.adEventCallback = object : BannerAdEventCallback {
                    override fun onAdImpression() {
                        if (isCurrent()) AdLogger.emit("banner", "impression")
                    }
                    override fun onAdClicked() {
                        if (isCurrent()) AdLogger.emit("banner", "clicked")
                    }
                }
                // Hide only skeleton. Keep the actual AdView attached.
                AdShimmer.hide(target)
                adView.visibility = View.VISIBLE
                AdLogger.emit("banner", "loaded")
                session.result(true, onResult)
            }
            override fun onAdFailedToLoad(error: LoadAdError) = Ui.run {
                if (!isCurrent()) return@run
                AdLogger.emit("banner", "load_failed", detail = error.message ?: "unknown")
                containerRef.get()?.let { remove(it) }
                session.result(false, onResult)
            }
        })
    }
}

package io.github.adsbridge.sdk

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesPlacement
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd.NativeAdType
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** LiveData emits immutable states, not raw NativeAd to avoid accidental multiple registrations. */
sealed class NativeState {
    data object Idle : NativeState()
    data object Loading : NativeState()
    data object Ready : NativeState()
    data class Failed(val reason: String) : NativeState()
    data object Disabled : NativeState()
}

data class NativeColors(
    val backgroundColor: Int? = null,
    val adTextBackgroundColor: Int? = null,
    val ctaColor: Int? = null,
    val textColor: Int? = null,
    val ctaTextColor: Int? = null
)

internal object NativeAds {
    private val states = mutableMapOf<String, MutableLiveData<NativeState>>()
    private val cached = mutableMapOf<String, NativeAd>()
    private val loading = mutableSetOf<String>()
    private val listeners = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private val displayed = WeakHashMap<FrameLayout, NativeAd>()
    private val shimmerOwners = WeakHashMap<FrameLayout, String>()
    private var generation = 0L
    fun observe(key: String): LiveData<NativeState> = synchronized(states) {
        states.getOrPut(key) { MutableLiveData<NativeState>(NativeState.Idle) }
    }

    private fun state(key: String, value: NativeState) {
        synchronized(states) { states.getOrPut(key) { MutableLiveData<NativeState>(NativeState.Idle) } }.value =
            value
    }

    fun clear() {
        generation++
        cached.values.forEach { it.destroy() }; cached.clear(); loading.clear(); listeners.clear()
        displayed.toMap().forEach { (container, ad) -> container.removeAllViews(); ad.destroy() }
        displayed.clear()
        shimmerOwners.keys.toList().forEach { AdShimmer.hide(it) }; shimmerOwners.clear()
        synchronized(states) { states.forEach { (key, _) -> state(key, NativeState.Disabled) } }
    }

    fun clearPlacement(placement: String) {
        cached.remove(placement)?.destroy(); loading.remove(placement)
        listeners.remove(placement); state(placement, NativeState.Idle)
    }

    fun preload(
        context: Context,
        id: String,
        placement: String,
        result: ((Boolean) -> Unit)? = null
    ) = Ui.run {
        AdLogger.emit("native", "load_requested", placement)
        if (!Policy.permitted() || !context.networkUsable()) {
            AdLogger.emit("native", if (!Policy.permitted()) "skipped" else "offline", placement)
            state(
                placement,
                NativeState.Failed("Disabled or offline")
            ); result?.invoke(false); return@run
        }
        if (cached.containsKey(placement)) {
            AdLogger.emit("native", "cache_hit", placement); state(
                placement,
                NativeState.Ready
            ); result?.invoke(true); return@run
        }
        if (result != null) listeners.getOrPut(placement) { mutableListOf() }.add(result)
        if (!loading.add(placement)) return@run
        state(placement, NativeState.Loading)
        val gen = generation
        val request = NativeAdRequest.Builder(id, listOf(NativeAdType.NATIVE))
            .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
            .setAdChoicesPlacement(AdChoicesPlacement.TOP_RIGHT).build()
        NativeAdLoader.load(request, object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) = Ui.run {
                if (gen != generation || !Policy.permitted()) {
                    nativeAd.destroy(); return@run
                }
                AdLogger.emit("native", "loaded", placement)
                loading.remove(placement); cached.put(placement, nativeAd)?.destroy()
                state(placement, NativeState.Ready); listeners.remove(placement)
                ?.forEach { it(true) }
            }

            override fun onAdFailedToLoad(error: LoadAdError) = Ui.run {
                if (gen != generation) return@run
                AdLogger.emit("native", "load_failed", placement, error.message ?: "unknown")
                loading.remove(placement); state(
                placement,
                NativeState.Failed(error.message ?: "Unknown ad error")
            )
                listeners.remove(placement)?.forEach { it(false) }
            }
        })
    }

    fun show(
        activity: Activity,
        container: FrameLayout,
        placement: String,
        style: Int,
        colors: NativeColors?,
        result: ((Boolean) -> Unit)?
    ) = Ui.run {
        AdLogger.emit("native", "show_requested", placement)
        if (!Policy.permitted() || !Ui.valid(activity)) {
            AdLogger.emit(
                "native",
                "skipped",
                placement
            ); remove(container); result?.invoke(false); return@run
        }
        val resolved = if (cached.containsKey(placement)) placement else cached.keys.firstOrNull()
        val ad = resolved?.let { cached.remove(it) }
        if (resolved != null && resolved != placement) AdLogger.emit(
            "native",
            "global_cache_hit",
            placement,
            "from=$resolved"
        )
        if (ad == null) {
            AdLogger.emit(
                "native",
                "show_failed",
                placement,
                "not_cached"
            ); result?.invoke(false); return@run
        }
        val res = when (style) {
            2 -> R.layout.native_ad_advance
            3 -> R.layout.native_ad_large_advance
            else -> R.layout.native_ad_small
        }
        val inflated = LayoutInflater.from(activity).inflate(res, container, false)
        val view = inflated as? NativeAdView
        if (view == null) {
            cached[resolved ?: placement] = ad
            AdShimmer.hide(container)
            result?.invoke(false)
            return@run
        }
        colors?.backgroundColor?.let(view::setBackgroundColor)
        view.headlineView = view.findViewById(R.id.ad_headline)
        view.bodyView = view.findViewById(R.id.ad_body)
        view.callToActionView = view.findViewById(R.id.ad_call_to_action)
        view.iconView = view.findViewById(R.id.icon)
        (view.headlineView as? TextView)?.apply {
            text = ad.headline
            colors?.textColor?.let(::setTextColor)
        }
        val adBadge = view.findViewById<TextView>(R.id.adText)

        colors?.adTextBackgroundColor?.let { color ->
            adBadge?.background?.mutate()?.setTint(color)
        }
        (view.bodyView as? TextView)?.apply {
            text = ad.body ?: ""; visibility =
            if (ad.body.isNullOrEmpty() || style != 2) View.GONE else View.VISIBLE; colors?.textColor?.let(
            ::setTextColor
        )
        }
        (view.callToActionView as? Button)?.apply {
            text = ad.callToAction ?: ""

            visibility = if (ad.callToAction.isNullOrEmpty()) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }

            // CTA background color (rounded corners preserved)
            colors?.ctaColor?.let { color ->
                background?.mutate()?.let { drawable ->
                    drawable.setTint(color)
                    background = drawable
                }
            }

            // CTA text color
            colors?.ctaTextColor?.let { color ->
                setTextColor(color)
            }
        }
        val icon = view.iconView as? ImageView
        icon?.setImageDrawable(ad.icon?.drawable); icon?.visibility =
        if (ad.icon == null) View.GONE else View.VISIBLE
        val media = view.findViewById<MediaView?>(R.id.ad_media)
        view.registerNativeAd(ad, media)
        remove(container); container.addView(view); container.visibility = View.VISIBLE
        AdLogger.emit("native", "shown", placement)
        displayed[container] = ad; shimmerOwners.remove(container); state(
        placement,
        NativeState.Idle
    ); result?.invoke(true)
    }

    fun loadAndShow(
        activity: Activity, id: String, container: FrameLayout, style: Int, colors: NativeColors?,
        result: ((Boolean) -> Unit)?,
        shimmer: ShimmerOptions = ShimmerOptions()
    ) = Ui.run {
        AdLogger.emit("native", "show_requested", id)
        if (!Policy.permitted() || !Ui.valid(activity)) {
            AdLogger.emit(
                "native",
                "skipped",
                id
            ); remove(container); result?.invoke(false); return@run
        }
        // Reuse any unconsumed native ad rather than loading again.
        if (cached.isNotEmpty()) {
            show(activity, container, cached.keys.first(), style, colors, result)
            return@run
        }
        remove(container)
        AdLogger.emit("native", "shimmer_started", "direct")
        AdShimmer.showNative(container, shimmer, style)
        shimmerOwners[container] = id
        val ref = WeakReference(activity)
        val pending = loading.firstOrNull()
        val callback: (Boolean) -> Unit = { ready ->
            if (shimmerOwners[container] == id) {
                shimmerOwners.remove(container)
                val active = ref.get()?.takeIf(Ui::valid)
                if (ready && active != null) show(
                    active,
                    container,
                    pending ?: id,
                    style,
                    colors,
                    result
                )
                else {
                    remove(container); result?.invoke(false)
                }
            }
        }
        if (pending != null) {
            AdLogger.emit("native", "joined_pending_load", id, "from=$pending")
            listeners.getOrPut(pending) { mutableListOf() }.add(callback)
        } else preload(activity.applicationContext, id, id, callback)
    }

    fun remove(container: FrameLayout) {
        shimmerOwners.remove(container); AdShimmer.hide(container)
        displayed.remove(container)?.destroy(); container.removeAllViews(); container.visibility =
            View.GONE
    }
}

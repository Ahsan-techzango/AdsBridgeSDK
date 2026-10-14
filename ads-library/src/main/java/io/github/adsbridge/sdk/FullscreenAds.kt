package io.github.adsbridge.sdk

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import java.lang.ref.WeakReference

internal object FullscreenAds {
    // All cache mutations occur on UI thread; network callbacks are marshalled here.
    private val cache = mutableMapOf<String, InterstitialAd>()
    private val loading = mutableSetOf<String>()
    private val callbacks = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private val rewardedCache = linkedMapOf<String, RewardedAd>()
    private val rewardedLoading = mutableSetOf<String>()
    private val rewardedListeners = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private var requestVersion = 0L
    private var rewardedRequestVersion = 0L
    private var showing = false
    private var paused = false
    private var lastShow = 0L
    private var epoch = 0L
    private var interstitialWait: Long? = null
    private var rewardedWait: Long? = null
    fun clear() {
        AdLoadingEvents.stop(interstitialWait); AdLoadingEvents.stop(rewardedWait); interstitialWait =
            null; rewardedWait =
            null; epoch++; cache.clear(); loading.clear(); callbacks.clear(); rewardedCache.clear(); rewardedLoading.clear(); rewardedListeners.clear(); requestVersion++; rewardedRequestVersion++; showing =
            false
    }

    fun clearPlacement(placement: String) {
        cache.remove(placement); loading.remove(placement); callbacks.remove(placement)
    }

    fun pause(value: Boolean) {
        Ui.run { paused = value }
    }

    fun preload(context: Context, id: String, placement: String, result: ((Boolean) -> Unit)?) =
        Ui.run {
            AdLogger.emit("interstitial", "load_requested", placement)
            if (!Policy.permitted() || !context.networkUsable()) {
                AdLogger.emit(
                    "interstitial",
                    if (!Policy.permitted()) "skipped" else "offline",
                    placement
                ); result?.invoke(false); return@run
            }
            if (cache.containsKey(placement)) {
                AdLogger.emit(
                    "interstitial",
                    "cache_hit",
                    placement
                ); result?.invoke(true); return@run
            }
            if (result != null) callbacks.getOrPut(placement) { mutableListOf() }.add(result)
            if (!loading.add(placement)) return@run
            val gen = epoch
            InterstitialAd.load(
                AdRequest.Builder(id).build(),
                object : AdLoadCallback<InterstitialAd> {
                    override fun onAdLoaded(ad: InterstitialAd) = Ui.run {
                        if (gen != epoch || !Policy.permitted()) return@run
                        AdLogger.emit("interstitial", "loaded", placement)
                        loading.remove(placement); cache[placement] = ad
                        callbacks.remove(placement)?.forEach { it(true) }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) = Ui.run {
                        if (gen != epoch) return@run
                        AdLogger.emit(
                            "interstitial",
                            "load_failed",
                            placement,
                            error.message ?: "unknown"
                        )
                        loading.remove(placement); callbacks.remove(placement)
                        ?.forEach { it(false) }
                    }
                })
        }

    fun loadAndShow(
        activity: Activity, id: String, placement: String, fallback: Boolean, delay: Long,
        cb: ((Boolean, Boolean, Boolean, Boolean) -> Unit)?
    ) = Ui.run {
        AdLogger.emit("interstitial", "show_requested", placement)
        val terminal = Completion()
        if (!Policy.permitted() || !activity.networkUsable() || !Ui.valid(activity)) {
            AdLogger.emit(
                "interstitial",
                if (!Policy.permitted()) "skipped" else if (!activity.networkUsable()) "offline" else "show_failed",
                placement
            )
            terminal.finish { cb?.invoke(false, true, false, false) }; return@run
        }
        // A new screen owns the next show request; a previous delayed show is invalidated.
        AdLoadingEvents.stop(interstitialWait)
        interstitialWait = null
        val request = ++requestVersion
        val entry =
            cache[placement]?.let { placement to it } ?: if (fallback) cache.entries.firstOrNull()
                ?.let { it.key to it.value } else null
        if (entry != null) {
            cb?.invoke(true, false, false, false)
            schedule(activity, entry.first, entry.second, delay, cb, terminal, request)
        } else {
            val wait = AdLoadingEvents.start("interstitial", placement)
            interstitialWait = wait
            // A different placement may already be loading. Join it instead of
            // requesting another fullscreen ad; only the new Activity can show it.
            val pendingKey = if (fallback) loading.firstOrNull() else null
            val gen = epoch
            val ref = WeakReference(activity)
            val deadline = Runnable {
                AdLoadingEvents.stop(wait)
                if (interstitialWait == wait) interstitialWait = null
                terminal.finish { cb?.invoke(false, true, false, false) }
            }
            Ui.handler.postDelayed(deadline, 10000L)
            fun useLoaded(key: String, ok: Boolean) {
                Ui.handler.removeCallbacks(deadline)
                AdLoadingEvents.stop(wait)
                if (interstitialWait == wait) interstitialWait = null
                if (terminal.done() || gen != epoch || request != requestVersion || !Policy.permitted()) {
                    terminal.finish { cb?.invoke(false, true, false, false) }
                } else if (!ok) terminal.finish { cb?.invoke(false, true, false, false) }
                else {
                    val candidate = cache[key] ?: cache.values.firstOrNull()
                    if (candidate == null) terminal.finish { cb?.invoke(false, true, false, false) }
                    else {
                        cb?.invoke(true, false, false, false)
                        ref.get()
                            ?.let { schedule(it, key, candidate, delay, cb, terminal, request) }
                            ?: terminal.finish { cb?.invoke(false, true, false, false) }
                    }
                }
            }
            if (pendingKey != null) {
                AdLogger.emit("interstitial", "joined_pending_load", placement, "from=$pendingKey")
                callbacks.getOrPut(pendingKey) { mutableListOf() }
                    .add { ok -> useLoaded(pendingKey, ok) }
            } else {
                preload(activity.applicationContext, id, placement) { ok ->
                    useLoaded(
                        placement,
                        ok
                    )
                }
            }
        }
    }

    private fun schedule(
        activity: Activity, placement: String, ad: InterstitialAd, delay: Long,
        cb: ((Boolean, Boolean, Boolean, Boolean) -> Unit)?, terminal: Completion, request: Long
    ) {
        val ref = WeakReference(activity)
        val gen = epoch
        Ui.handler.postDelayed({
            val act = ref.get()
            if (terminal.done()) return@postDelayed
            if (act == null || !Ui.valid(act) || gen != epoch || request != requestVersion || !Policy.permitted() || paused || showing ||
                SystemClock.elapsedRealtime() - lastShow < Policy.cooldownMs
            ) {
                terminal.finish { cb?.invoke(false, true, false, false) }; return@postDelayed
            }
            AdLogger.emit("interstitial", "show_attempt", placement)
            showing = true
            // Consume cached ad before calling show; it must never be shown twice.
            cache.entries.removeAll { it.value === ad }
            ad.adEventCallback = object : InterstitialAdEventCallback {
                override fun onAdShowedFullScreenContent() = Ui.run {
                    AdLogger.emit(
                        "interstitial",
                        "shown",
                        placement
                    ); if (!terminal.done()) cb?.invoke(false, false, true, false)
                }

                override fun onAdDismissedFullScreenContent() = Ui.run {
                    AdLogger.emit("interstitial", "dismissed", placement)
                    showing = false; lastShow = SystemClock.elapsedRealtime()
                    terminal.finish { cb?.invoke(false, false, false, true) }
                }

                override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) =
                    Ui.run {
                        AdLogger.emit(
                            "interstitial",
                            "show_failed",
                            placement,
                            error.message ?: "unknown"
                        )
                        showing = false; terminal.finish { cb?.invoke(false, true, false, false) }
                    }
            }
            try {
                ad.show(act)
            } catch (_: Exception) {
                showing = false; terminal.finish { cb?.invoke(false, true, false, false) }
            }
        }, delay.coerceAtLeast(0L))
    }

    fun showAny(activity: Activity, delay: Long, cb: ((Boolean, Boolean, Boolean) -> Unit)?) =
        Ui.run {
            val item = cache.entries.firstOrNull()
            if (item == null) {
                cb?.invoke(false, false, true); return@run
            }
            schedule(
                activity,
                item.key,
                item.value,
                delay,
                { _, failed, showed, dismissed -> cb?.invoke(showed, dismissed, failed) },
                Completion(),
                ++requestVersion
            )
        }

    /** Preload independently of any Activity. Requests for the same unit are coalesced. */
    fun preloadRewarded(context: Context, id: String, result: ((Boolean) -> Unit)? = null) =
        Ui.run {
            if (!Policy.permitted() || !context.networkUsable()) {
                AdLogger.emit("rewarded", "skipped_or_offline", id)
                result?.invoke(false)
                return@run
            }
            if (rewardedCache.containsKey(id)) {
                result?.invoke(true); return@run
            }
            if (result != null) rewardedListeners.getOrPut(id) { mutableListOf() }.add(result)
            if (!rewardedLoading.add(id)) return@run
            val gen = epoch
            AdLogger.emit("rewarded", "load_requested", id)
            RewardedAd.load(AdRequest.Builder(id).build(), object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) = Ui.run {
                    if (gen != epoch || !Policy.permitted()) return@run
                    rewardedLoading.remove(id)
                    rewardedCache[id] = ad
                    AdLogger.emit("rewarded", "loaded", id)
                    rewardedListeners.remove(id)?.forEach { it(true) }
                }

                override fun onAdFailedToLoad(error: LoadAdError) = Ui.run {
                    if (gen != epoch) return@run
                    rewardedLoading.remove(id)
                    AdLogger.emit("rewarded", "load_failed", id, error.message ?: "unknown")
                    rewardedListeners.remove(id)?.forEach { it(false) }
                }
            })
        }

    fun rewarded(
        activity: Activity, id: String, delay: Long,
        cb: ((Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit)?
    ) = Ui.run {
        val done = Completion()
        if (!Policy.permitted() || !Ui.valid(activity)) {
            done.finish { cb?.invoke(false, true, false, false, false) }
            return@run
        }
        AdLoadingEvents.stop(rewardedWait)
        rewardedWait = null
        val version = ++rewardedRequestVersion
        val gen = epoch
        val ref = WeakReference(activity)
        fun show(ad: RewardedAd) {
            if (done.done()) return
            cb?.invoke(true, false, false, false, false)
            Ui.handler.postDelayed({
                val a = ref.get()
                if (done.done()) return@postDelayed
                if (version != rewardedRequestVersion || gen != epoch || !Policy.permitted() ||
                    showing || paused || a == null || !Ui.valid(a)
                ) {
                    done.finish { cb?.invoke(false, true, false, false, false) }
                    return@postDelayed
                }
                showing = true
                rewardedCache.entries.removeAll { it.value === ad }
                ad.adEventCallback = object : RewardedAdEventCallback {
                    override fun onAdShowedFullScreenContent() = Ui.run {
                        AdLogger.emit("rewarded", "shown", id)
                        if (!done.done()) cb?.invoke(false, false, true, false, false)
                    }

                    override fun onAdDismissedFullScreenContent() = Ui.run {
                        showing = false
                        AdLogger.emit("rewarded", "dismissed", id)
                        done.finish { cb?.invoke(false, false, false, true, false) }
                    }

                    override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) =
                        Ui.run {
                            showing = false
                            AdLogger.emit("rewarded", "show_failed", id, error.message ?: "unknown")
                            done.finish { cb?.invoke(false, true, false, false, false) }
                        }
                }
                try {
                    ad.show(a) {
                        Ui.run {
                            AdLogger.emit("rewarded", "reward_earned", id)
                            if (!done.done()) cb?.invoke(false, false, false, false, true)
                        }
                    }
                } catch (_: Exception) {
                    showing = false
                    done.finish { cb?.invoke(false, true, false, false, false) }
                }
            }, delay.coerceAtLeast(0))
        }

        val cachedAd = rewardedCache[id] ?: rewardedCache.values.firstOrNull()
        if (cachedAd != null) {
            AdLogger.emit("rewarded", "global_cache_hit", id); show(cachedAd); return@run
        }
        val wait = AdLoadingEvents.start("rewarded", id)
        rewardedWait = wait
        val timeout = Runnable {
            AdLoadingEvents.stop(wait)
            if (rewardedWait == wait) rewardedWait = null
            done.finish { cb?.invoke(false, true, false, false, false) }
        }
        Ui.handler.postDelayed(timeout, 10000L)
        val pending = rewardedLoading.firstOrNull()
        val resultHandler: (Boolean) -> Unit = { ok ->
            Ui.handler.removeCallbacks(timeout)
            AdLoadingEvents.stop(wait)
            if (rewardedWait == wait) rewardedWait = null
            if (!done.done()) {
                if (!ok || version != rewardedRequestVersion || gen != epoch || !Policy.permitted()) {
                    done.finish { cb?.invoke(false, true, false, false, false) }
                } else {
                    (rewardedCache[id] ?: rewardedCache.values.firstOrNull())?.let { show(it) }
                        ?: done.finish { cb?.invoke(false, true, false, false, false) }
                }
            }
        }
        if (pending != null) {
            AdLogger.emit("rewarded", "joined_pending_load", id, "from=$pending")
            rewardedListeners.getOrPut(pending) { mutableListOf() }.add(resultHandler)
        } else preloadRewarded(activity.applicationContext, id, resultHandler)
    }
}


package io.github.adsbridge.sdk

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import java.lang.ref.WeakReference

/**
 * An app-open progress event stays active from loadAndShow/show until dismissal or failure.
 * Standalone load() (preload) never starts a progress event.
 * All mutable state and SDK callbacks are processed on the main thread by Ui.run.
 */
internal object AppOpenAds {
    private const val MAX_AGE_MS = 4 * 60 * 60 * 1000L
    private const val LOAD_TIMEOUT_MS = 10_000L

    private var cached: AppOpenAd? = null
    private var loadedAt = 0L
    private var showing = false
    private var loading = false
    private var paused = false
    private var generation = 0L
    private var requestVersion = 0L

    private val loadListeners = mutableListOf<(Boolean) -> Unit>()
    private var currentRequest: PendingShow? = null
    private var activeShowToken: Long? = null

    private class PendingShow(
        val version: Long,
        val activity: WeakReference<Activity>,
        val callback: ((Boolean, Boolean, Boolean) -> Unit)?,
        val loadingToken: Long
    ) {
        var completed = false
        var timeout: Runnable? = null
        private var tokenTransferred = false

        fun transferTokenToShow(): Long {
            tokenTransferred = true
            return loadingToken
        }

        fun finishFailed() {
            if (completed) return
            completed = true
            timeout?.let(Ui.handler::removeCallbacks)
            timeout = null
            if (!tokenTransferred) AdLoadingEvents.stop(loadingToken)
            callback?.invoke(false, false, true)
        }
    }

    private fun available(): Boolean = cached != null &&
            SystemClock.elapsedRealtime() - loadedAt < MAX_AGE_MS

    private fun cancelPending() {
        val old = currentRequest ?: return
        currentRequest = null
        old.finishFailed()
    }

    fun clear() = Ui.run {
        generation++
        requestVersion++
        cancelPending()
        cached = null
        loadedAt = 0L
        loading = false
        val waiting = loadListeners.toList()
        loadListeners.clear()
        waiting.forEach { it(false) }
        // If an ad is already visible, its own dismissal callback owns the progress token.
        // Do not set showing=false or prematurely remove the underlying fullscreen dialog.
    }

    fun pause(value: Boolean) = Ui.run { paused = value }

    /** Preload only: does not emit progress events. Joins an existing in-flight load. */
    fun load(context: Context, id: String, onResult: ((Boolean) -> Unit)?) = Ui.run {
        AdLogger.emit("app_open", "load_requested")
        if (!Policy.permitted()) {
            AdLogger.emit("app_open", "skipped")
            onResult?.invoke(false)
            return@run
        }
        if (available()) {
            onResult?.invoke(true)
            return@run
        }
        if (loading) {
            if (onResult != null) loadListeners.add(onResult)
            return@run
        }
        if (!context.networkUsable()) {
            AdLogger.emit("app_open", "offline")
            onResult?.invoke(false)
            return@run
        }
        if (onResult != null) loadListeners.add(onResult)
        loading = true
        val gen = generation

        fun completeLoad(ok: Boolean) {
            val waiting = loadListeners.toList()
            loadListeners.clear()
            waiting.forEach { it(ok) }
        }

        try {
            AppOpenAd.load(AdRequest.Builder(id).build(), object : AdLoadCallback<AppOpenAd> {
                override fun onAdLoaded(ad: AppOpenAd) = Ui.run {
                    if (gen != generation) return@run
                    loading = false
                    if (!Policy.permitted()) {
                        completeLoad(false)
                        return@run
                    }
                    cached = ad
                    loadedAt = SystemClock.elapsedRealtime()
                    AdLogger.emit("app_open", "loaded")
                    completeLoad(true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) = Ui.run {
                    if (gen != generation) return@run
                    loading = false
                    AdLogger.emit("app_open", "load_failed", detail = error.message ?: "unknown")
                    completeLoad(false)
                }
            })
        } catch (error: Exception) {
            if (gen == generation) {
                loading = false
                AdLogger.emit("app_open", "load_failed", detail = error.message ?: "exception")
                completeLoad(false)
            }
        }
    }

    /** Direct show, including preloaded ads: keep fullscreen progress until ad closes. */
    fun show(activity: Activity, cb: ((Boolean, Boolean, Boolean) -> Unit)?) = Ui.run {
        showInternal(activity, cb, null)
    }

    /** Progress starts before show and only stops when the ad is dismissed or fails. */
    private fun showInternal(
        activity: Activity,
        cb: ((Boolean, Boolean, Boolean) -> Unit)?,
        existingToken: Long?
    ) {
        val token = existingToken ?: AdLoadingEvents.start("app_open", "show")
        val complete = Completion()
        val ad = cached
        AdLogger.emit("app_open", "show_requested")

        if (!Policy.permitted() || !Ui.valid(activity) || paused || showing || ad == null || !available()) {
            AdLoadingEvents.stop(token)
            complete.finish { cb?.invoke(false, false, true) }
            return
        }

        cached = null
        showing = true
        activeShowToken = token

        fun finish(showFailed: Boolean) {
            if (complete.done()) return
            showing = false
            if (activeShowToken == token) activeShowToken = null
            AdLoadingEvents.stop(token)
            complete.finish {
                if (showFailed) cb?.invoke(false, false, true)
                else cb?.invoke(false, true, false)
            }
        }

        var showReported = false
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() = Ui.run {
                AdLogger.emit("app_open", "shown")
                // Intentionally DO NOT stop the token here.
                if (!complete.done() && !showReported) {
                    showReported = true
                    cb?.invoke(true, false, false)
                }
            }

            override fun onAdDismissedFullScreenContent() = Ui.run {
                AdLogger.emit("app_open", "dismissed")
                finish(false)
            }

            override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) = Ui.run {
                AdLogger.emit("app_open", "show_failed", detail = error.message ?: "unknown")
                finish(true)
            }
        }
        try {
            ad.show(activity)
        } catch (error: Exception) {
            AdLogger.emit("app_open", "show_failed", detail = error.message ?: "exception")
            finish(true)
        }
    }

    /** Cached => immediately show with dialog; cache miss => load, then show with same dialog. */
    fun loadAndShow(
        activity: Activity,
        id: String,
        cb: ((Boolean, Boolean, Boolean) -> Unit)?
    ) = Ui.run {
        requestVersion++
        cancelPending()

        if (!Policy.permitted() || !Ui.valid(activity) || paused || showing) {
            cb?.invoke(false, false, true)
            return@run
        }
        if (available()) {
            showInternal(activity, cb, null)
            return@run
        }
        if (!loading && !activity.networkUsable()) {
            AdLogger.emit("app_open", "offline")
            cb?.invoke(false, false, true)
            return@run
        }

        val request = PendingShow(
            version = requestVersion,
            activity = WeakReference(activity),
            callback = cb,
            loadingToken = AdLoadingEvents.start("app_open", id)
        )
        currentRequest = request
        val gen = generation

        val timeout = Runnable {
            if (currentRequest === request) {
                currentRequest = null
                AdLogger.emit("app_open", "load_timeout")
                request.finishFailed()
            }
        }
        request.timeout = timeout
        Ui.handler.postDelayed(timeout, LOAD_TIMEOUT_MS)

        load(activity.applicationContext, id) { ok ->
            if (currentRequest !== request || request.completed) return@load
            currentRequest = null
            request.timeout?.let(Ui.handler::removeCallbacks)
            request.timeout = null

            val active = request.activity.get()
            if (!ok || gen != generation || request.version != requestVersion ||
                !Policy.permitted() || paused || active == null || !Ui.valid(active)
            ) {
                request.finishFailed()
            } else {
                request.completed = true
                showInternal(active, cb, request.transferTokenToShow())
            }
        }
    }
}

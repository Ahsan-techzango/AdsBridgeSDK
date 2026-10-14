package io.github.adsbridge.sdk

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object Ui {
    val handler = Handler(Looper.getMainLooper())
    fun run(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) safe(action)
        else handler.post { safe(action) }
    }
    private fun safe(action: () -> Unit) {
        try { action() } catch (t: Exception) { Log.e("AdsLibrary", "Host callback failed", t) }
    }
    fun valid(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed
}

/** Exactly one terminal outcome for each operation. Event notifications may occur before it. */
internal class Completion {
    private val finished = AtomicBoolean(false)
    fun finish(action: () -> Unit) { if (finished.compareAndSet(false, true)) Ui.run(action) }
    fun done() = finished.get()
}

internal object Policy {
    val premium = AtomicBoolean(false)
    val generation = AtomicLong(0)
    @Volatile var enabled = true
    @Volatile var cooldownMs = 0L
    @Volatile var analytics: ((String, String) -> Unit)? = null
    fun permitted() = !premium.get() && enabled
    fun updated(premiumUser: Boolean) {
        premium.set(premiumUser)
        AdLogger.emit("sdk", "premium_changed", detail = premiumUser.toString())
        generation.incrementAndGet()
        Ui.run {
            FullscreenAds.clear()
            NativeAds.clear()
            BannerAds.clear()
            AppOpenAds.clear()
        }
    }
    fun log(event: String, placement: String) {
        val adapter = analytics ?: return
        Ui.run { adapter(event, placement) }
    }
}

internal fun Context.networkUsable(): Boolean { return try {
    val mgr = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = mgr.getNetworkCapabilities(mgr.activeNetwork ?: return false) ?: return false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (_: SecurityException) { false }
}

package io.github.adsbridge.sdk

import android.util.Log

/** Diagnostic events; no ad IDs, user identifiers, or Activity references are retained. */
data class AdLogEvent(
    val format: String,
    val action: String,
    val placement: String = "",
    val detail: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

/** Centralized, opt-in Logcat diagnostics and optional host listener. */
internal object AdLogger {
    @Volatile var enabled: Boolean = false
    @Volatile var logcatEnabled: Boolean = false
    @Volatile var listener: ((AdLogEvent) -> Unit)? = null

    fun emit(format: String, action: String, placement: String = "", detail: String = "") {
        if (!enabled) return
        val event = AdLogEvent(format, action, placement, detail)
        Ui.run {
            if (logcatEnabled) {
                val message = "${event.format} | ${event.action} | ${event.placement}" +
                    if (event.detail.isNotBlank()) " | ${event.detail}" else ""
                when (action) {
                    "load_failed", "show_failed", "offline" -> Log.w("AdsLibrary", message)
                    else -> Log.d("AdsLibrary", message)
                }
            }
            try { listener?.invoke(event) }
            catch (e: Exception) { Log.e("AdsLibrary", "Ad log listener failed", e) }
        }
    }
}

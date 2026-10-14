package io.github.adsbridge.sdk

/** A show request is waiting for an ad to load. Preloads never emit these events. */
data class AdLoadingEvent(
    val format: String,
    val placement: String,
    val isLoading: Boolean
)

internal object AdLoadingEvents {
    @Volatile var listener: ((AdLoadingEvent) -> Unit)? = null
    private var sequence = 0L
    private val active = mutableMapOf<Long, Pair<String, String>>()

    /** Call on UI thread. */
    fun start(format: String, placement: String): Long {
        val token = ++sequence
        active[token] = format to placement
        emit(format, placement, true)
        return token
    }

    /** Idempotent. Late load callbacks cannot emit duplicate dismiss events. */
    fun stop(token: Long?) {
        if (token == null) return
        val request = active.remove(token) ?: return
        emit(request.first, request.second, false)
    }

    private fun emit(format: String, placement: String, loading: Boolean) {
        Ui.run {
            try {
                listener?.invoke(AdLoadingEvent(format, placement, loading))
            } catch (e: Exception) {
                android.util.Log.e("AdsLibrary", "Ad loading listener failed", e)
            }
        }
    }
}

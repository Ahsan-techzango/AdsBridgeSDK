package io.github.adsbridge.sdk

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.WeakHashMap

/** Native skeleton mirrors the selected built-in layout; banner uses a banner-specific skeleton. */
data class ShimmerOptions(
    val enabled: Boolean = true,
    val heightDp: Int = 110,
    val baseColor: Int = Color.rgb(230, 234, 240),
    val highlightColor: Int = Color.rgb(248, 250, 252),
    val durationMs: Long = 1100L
)

internal object AdShimmer {
    private val placeholders = WeakHashMap<FrameLayout, SkeletonView>()

    /** Native style 1: small; 2: left media; 3: full-width top media. */
    fun showNative(container: FrameLayout, options: ShimmerOptions, style: Int) {
        show(container, options, when (style) { 2 -> 2; 3 -> 3; else -> 1 })
    }

    /** Kept for binary/source compatibility with callers inside the SDK. */
    fun show(container: FrameLayout, options: ShimmerOptions) = show(container, options, 0)

    private fun show(container: FrameLayout, options: ShimmerOptions, kind: Int) {
        hide(container)
        if (!options.enabled) return
        val height = when (kind) {
            1 -> 112 // icon, text and CTA, like native_ad_small.xml
            2 -> 180 // left media and text, like native_ad_advance.xml
            3 -> 235 // full-width media and details, like native_ad_large_advance.xml
            else -> options.heightDp.coerceAtLeast(45) // actual banner slot
        }
        val view = SkeletonView(container, options, kind)
        placeholders[container] = view
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (height * container.resources.displayMetrics.density).toInt()
        ))
        container.visibility = View.VISIBLE
        view.start()
    }

    fun hide(container: FrameLayout) {
        placeholders.remove(container)?.let { it.stop(); container.removeView(it) }
    }

    private class SkeletonView(
        container: FrameLayout,
        private val options: ShimmerOptions,
        private val kind: Int
    ) : View(container.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private fun dp(value: Float) = value * density
        private var phase = -1f
        private val animator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration = options.durationMs.coerceAtLeast(250L)
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }
        fun start() { animator.start() }
        fun stop() { animator.cancel() }
        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

        private fun block(c: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float = 7f) {
            if (w <= 0f || h <= 0f) return
            c.drawRoundRect(x, y, x + w, y + h, dp(radius), dp(radius), paint)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // Static light card plus animated gradient on each skeleton element.
            paint.shader = null
            paint.color = Color.rgb(248, 249, 251)
            canvas.drawRoundRect(0f, 0f, w, h, dp(12f), dp(12f), paint)
            paint.color = Color.WHITE
            paint.shader = LinearGradient(
                (phase - .55f) * w, 0f, (phase + .55f) * w, 0f,
                intArrayOf(options.baseColor, options.highlightColor, options.baseColor),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
            )
            val pad = dp(10f)
            val gap = dp(8f)
            when (kind) {
                1 -> {
                    // Small: app icon on left, headline/body on right, bottom CTA.
                    val icon = dp(40f)
                    block(canvas, pad, pad, icon, icon)
                    block(canvas, pad + icon + gap, pad + dp(2f), (w - 2 * pad - icon - 2 * gap) * .72f, dp(12f))
                    block(canvas, pad + icon + gap, pad + dp(23f), (w - 2 * pad - icon - 2 * gap) * .9f, dp(10f))
                    block(canvas, pad, h - pad - dp(38f), w - 2 * pad, dp(38f))
                }
                2 -> {
                    // Advanced: image/media left (165dp), details and CTA right.
                    val mediaW = minOf(dp(165f), w * .47f)
                    block(canvas, pad, pad, mediaW, h - 2 * pad)
                    val x = pad + mediaW + gap
                    val right = w - x - pad
                    block(canvas, x, pad, dp(25f).coerceAtMost(right), dp(18f), 4f) // AD badge
                    block(canvas, x, pad + dp(30f), right * .94f, dp(13f))
                    block(canvas, x, pad + dp(52f), right * .78f, dp(10f))
                    block(canvas, x, pad + dp(70f), right * .9f, dp(10f))
                    block(canvas, x, h - pad - dp(38f), right, dp(38f))
                }
                3 -> {
                    // Large: full-width media on top, icon/text below and CTA.
                    val mediaHeight = dp(135f)
                    block(canvas, pad, pad, w - 2 * pad, mediaHeight)
                    val textY = pad + mediaHeight + dp(9f)
                    block(canvas, pad, textY, dp(40f), dp(40f))
                    val x = pad + dp(48f)
                    block(canvas, x, textY + dp(3f), (w - x - pad) * .74f, dp(12f))
                    block(canvas, x, textY + dp(24f), (w - x - pad) * .54f, dp(10f))
                    block(canvas, pad, h - pad - dp(30f), w - 2 * pad, dp(30f))
                }
                else -> {
                    // Banner: icon, two lines and CTA instead of a plain rectangle.
                    val icon = (h - 2 * pad).coerceAtMost(dp(44f))
                    block(canvas, pad, (h - icon) / 2f, icon, icon)
                    val x = pad + icon + gap
                    val ctaW = minOf(dp(86f), w * .27f)
                    val textW = (w - x - ctaW - 3 * gap).coerceAtLeast(0f)
                    block(canvas, x, h * .26f, textW, dp(11f))
                    block(canvas, x, h * .55f, textW * .7f, dp(9f))
                    block(canvas, w - pad - ctaW, (h - dp(34f)) / 2f, ctaW, dp(34f))
                }
            }
            paint.shader = null
        }
    }
}

package dev.zanderp.opencfmoto

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * A strip of road with the Z150X on it. [Mode.IDLE] parks the scooter at the start, [Mode.RIDING]
 * rides it across on a loop while linking, [Mode.CRUISING] holds it mid-strip with the road
 * scrolling while live, and [Mode.ARRIVE] (splash) rides in, pauses and rides off. Always drawn
 * left-to-right; animation stops when the system "remove animations" setting is on.
 */
class RideStripView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, RIDING, CRUISING, ARRIVE }

    private val dp = resources.displayMetrics.density
    private val art = ScooterArt().apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        accent = ContextCompat.getColor(context, R.color.brand_orange)
        muted = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.road)
        strokeWidth = 3 * dp
    }
    private val ease = DecelerateInterpolator(2f)
    private val ticker = TimeAnimator().apply { setTimeListener { _, _, _ -> invalidate() } }
    private var startedAt = 0L

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            startedAt = System.currentTimeMillis()
            syncTicker()
            invalidate()
        }

    private val moving get() = mode != Mode.IDLE
    private val animating get() = moving && ValueAnimator.areAnimatorsEnabled()

    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncTicker() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) syncTicker() else ticker.cancel()
    }

    private fun syncTicker() {
        if (animating && isAttachedToWindow) { if (!ticker.isStarted) ticker.start() } else ticker.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val ground = height - 6 * dp
        val t = if (animating) System.currentTimeMillis() - startedAt else 0L

        val dash = 26 * dp
        val shift = if (animating) -((t % 450L) / 450f) * dash * 2 else 0f
        var x = shift
        while (x < w) { canvas.drawLine(x, ground, x + dash, ground, road); x += dash * 2 }

        val h = (height - 10 * dp).coerceAtMost(64 * dp)
        val sw = art.widthFor(h)
        val bob = if (animating && mode == Mode.CRUISING) (Math.sin(t / 500.0 * 2 * Math.PI) * 1.5 * dp).toFloat() else 0f
        val left = when (mode) {
            Mode.IDLE -> 28 * dp
            Mode.CRUISING -> (w - sw) / 2f
            Mode.RIDING -> if (animating) -sw + ((t % 2400L) / 2400f) * (w + sw * 1.4f) else (w - sw) / 2f
            Mode.ARRIVE -> {
                val p = (t % 4500L) / 4500f
                val mid = (w - sw) / 2f
                when {
                    !animating -> mid
                    p < 0.35f -> -sw * 2 + (mid + sw * 2) * ease.getInterpolation(p / 0.35f)
                    p < 0.70f -> mid
                    else -> mid + (w - mid + sw) * ((p - 0.70f) / 0.30f)
                }
            }
        }
        val headlight = if (mode == Mode.ARRIVE && animating) (if ((t % 4500L) / 4500f > 0.33f) 1f else 0.3f) else 1f
        art.draw(
            canvas, left, ground - 1.5f * dp - bob, h,
            wheelDeg = if (animating) (t % 450L) / 450f * 360f else 0f,
            speedPhase = if (animating) (t % 500L) / 500f else null,
            headlight = headlight,
        )
    }
}

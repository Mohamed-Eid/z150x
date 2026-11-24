package dev.zanderp.opencfmoto

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/**
 * The round Connect button, drawn like a rev gauge behind its label children: a breathing red halo
 * while idle, a spinning arc while linking, a full green ring once Android Auto is on the dash.
 */
class ConnectRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class State { IDLE, BUSY, LIVE, ERROR }

    private val dp = resources.displayMetrics.density
    private val red = ContextCompat.getColor(context, R.color.brand_orange)
    private val green = ContextCompat.getColor(context, R.color.status_live)
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ring_face) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8 * dp
        color = ContextCompat.getColor(context, R.color.ring_track)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val ticker = TimeAnimator().apply { setTimeListener { _, _, _ -> invalidate() } }
    private var startedAt = System.currentTimeMillis()

    var state: State = State.IDLE
        set(value) {
            if (field == value) return
            field = value
            startedAt = System.currentTimeMillis()
            syncTicker()
            invalidate()
        }

    init { setWillNotDraw(false) }

    private val animating get() = (state == State.IDLE || state == State.BUSY) && ValueAnimator.areAnimatorsEnabled()

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
        val cx = width / 2f
        val cy = height / 2f
        val faceR = minOf(width, height) / 2f - 18 * dp
        val ringR = faceR - 8 * dp
        val t = System.currentTimeMillis() - startedAt

        when (state) {
            State.IDLE -> if (animating) {
                val p = (t % 2400L) / 2400f
                if (p < 0.7f) {
                    val k = p / 0.7f
                    halo.color = red
                    halo.alpha = (0.45f * (1f - k) * 255).toInt()
                    canvas.drawCircle(cx, cy, faceR + 18 * dp * k, halo)
                }
            }
            State.LIVE -> {
                halo.color = green
                halo.alpha = (0.18f * 255).toInt()
                canvas.drawCircle(cx, cy, faceR + 6 * dp, halo)
            }
            else -> Unit
        }

        canvas.drawCircle(cx, cy, faceR, face)
        canvas.drawCircle(cx, cy, ringR, track)
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        when (state) {
            State.BUSY -> {
                arc.color = red
                val start = if (animating) (t % 1000L) / 1000f * 360f else 0f
                canvas.drawArc(oval, start - 90f, 90f, false, arc)
            }
            State.LIVE -> { arc.color = green; canvas.drawCircle(cx, cy, ringR, arc) }
            else -> Unit
        }
    }
}

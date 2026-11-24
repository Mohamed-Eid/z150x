package com.z150x.app

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Onboarding progress as a road: three checkpoints and a finish flag, with a small scooter
 * (gently bobbing) parked at the current step. Mirrors for right-to-left layouts.
 */
class RideProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val red = ContextCompat.getColor(context, R.color.brand_orange)
    private val off = ContextCompat.getColor(context, R.color.outline)
    private val flagOff = ContextCompat.getColor(context, R.color.status_idle)
    private val art = ScooterArt(mini = true).apply { color = ContextCompat.getColor(context, R.color.text_primary) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3 * dp; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val flag = Path().apply { moveTo(4f, 22f); lineTo(4f, 4f); lineTo(16f, 4f); lineTo(14f, 8f); lineTo(16f, 12f); lineTo(4f, 12f) }
    private val ticker = TimeAnimator().apply { setTimeListener { _, _, _ -> invalidate() } }

    /** 0..3 — the last step sits on the flag. */
    var step = 0
        set(value) { field = value.coerceIn(0, 3); invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) ticker.start()
    }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), (40 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val pad = 6.5f * dp
        val y = 28 * dp
        val span = width - 2 * pad
        fun xAt(i: Int): Float = if (rtl) width - pad - span * i / 3f else pad + span * i / 3f

        bar.color = off
        canvas.drawLine(xAt(0), y, xAt(3), y, bar)
        if (step > 0) { bar.color = red; canvas.drawLine(xAt(0), y, xAt(step), y, bar) }
        for (i in 0..2) {
            dot.color = if (i <= step) red else off
            canvas.drawCircle(xAt(i), y, 6.5f * dp, dot)
        }
        // Finish flag (18 dp, drawn from a 24-unit icon).
        canvas.save()
        val fs = 18 * dp / 24f
        canvas.translate(xAt(3) - 9 * dp, y - 18 * dp + 6 * dp)
        if (rtl) { canvas.translate(18 * dp, 0f); canvas.scale(-1f, 1f) }
        canvas.scale(fs, fs)
        flagPaint.color = if (step >= 3) red else flagOff
        canvas.drawPath(flag, flagPaint)
        canvas.restore()

        val t = System.currentTimeMillis()
        val bob = if (ValueAnimator.areAnimatorsEnabled()) (Math.sin(t / 500.0 * 2 * Math.PI) * 1.2 * dp).toFloat() else 0f
        val h = 20 * dp
        val w = art.widthFor(h)
        // Centred over its checkpoint, but kept inside the view at either end of the road.
        val left = (xAt(step) - w / 2f).coerceIn(0f, (width - w).coerceAtLeast(0f))
        art.draw(canvas, left, y - 6 * dp + bob, h, mirrored = rtl)
    }
}

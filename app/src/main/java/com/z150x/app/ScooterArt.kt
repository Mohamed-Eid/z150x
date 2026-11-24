package com.z150x.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The Z150X line art used by the home road strip, the splash and the onboarding progress bar.
 * The outline is traced from a side photo of the Zontes Z150X (windscreen, mirror, beak, stepped
 * seat, tail, hugger); seat edge, panel crease, glovebox, exhaust, headlight and the V-shaped LED
 * strip are drawn on top. Design units: 250 wide, tyres touching the ground at y = [GROUND];
 * speed lines sit at x < 0. [mini] draws just the outline and tyres for very small sizes.
 */
internal class ScooterArt(private val mini: Boolean = false) {

    private val outline = polygon(OUTLINE)
    private val details = Path().apply {
        for (line in DETAILS) {
            moveTo(line[0], line[1])
            var i = 2
            while (i < line.size) { lineTo(line[i], line[i + 1]); i += 2 }
        }
    }
    private val headlight = polygon(floatArrayOf(211f, 53f, 222f, 57f, 228f, 76f, 219f, 71f))
    private val drl = Path().apply { moveTo(197f, 88f); lineTo(212f, 84f); lineTo(226f, 80f) }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    var color = 0xFFF4F4F5.toInt()
    var accent = 0xFFE0261C.toInt()
    var muted = 0xFFA1A1AA.toInt()

    /** Width in px of the drawing when it is [heightPx] tall (top of the mirror to the ground). */
    fun widthFor(heightPx: Float) = heightPx * WIDTH / GROUND

    /**
     * @param left x of design-unit 0 · @param groundY where the tyres touch · @param heightPx drawing height
     * @param wheelDeg wheel rotation · @param speedPhase 0..1 for animated speed lines, null for none
     * @param headlight 0..1 headlight brightness · @param mirrored draw facing left (RTL progress bar)
     */
    fun draw(
        c: Canvas, left: Float, groundY: Float, heightPx: Float,
        wheelDeg: Float = 0f, speedPhase: Float? = null, headlight: Float = 1f, mirrored: Boolean = false,
    ) {
        val s = heightPx / GROUND
        // Line weights in screen px, whatever the size: ~1.6 dp-ish at the home strip size.
        val stroke = (heightPx / 30f).coerceIn(1.2f, 4f) / s
        c.save()
        c.translate(left, groundY - GROUND * s)
        if (mirrored) { c.translate(WIDTH * s, 0f); c.scale(-1f, 1f) }
        c.scale(s, s)

        if (speedPhase != null) {
            line.strokeWidth = stroke * 1.2f
            val lines = arrayOf(floatArrayOf(-4f, 62f, -30f), floatArrayOf(2f, 88f, -34f), floatArrayOf(-6f, 112f, -26f))
            for (i in lines.indices) {
                val p = (speedPhase + i / 3f) % 1f
                line.color = if (i == 1) muted else accent
                line.alpha = ((1f - p) * 0.9f * 255).toInt()
                val dx = -12f * p
                c.drawLine(lines[i][0] + dx, lines[i][1], lines[i][2] + dx, lines[i][1], line)
            }
            line.alpha = 255
        }

        line.color = color
        line.strokeWidth = stroke
        c.drawPath(outline, line)
        if (!mini) {
            line.strokeWidth = stroke * 0.7f
            c.drawPath(details, line)
            fill.color = accent
            fill.alpha = (255 * headlight.coerceIn(0.3f, 1f)).toInt()
            c.drawPath(this.headlight, fill)
            line.color = accent
            line.alpha = fill.alpha
            line.strokeWidth = stroke * 0.8f
            c.drawPath(drl, line)
            line.alpha = 255
            line.strokeWidth = stroke
            c.drawLine(14f, 51f, 34f, 55f, line)  // tail light
            line.color = color
        }

        for (w in WHEELS) {
            val (cx, cy, r, rim) = w
            line.strokeWidth = stroke
            c.drawCircle(cx, cy, r, line)
            if (mini) continue
            line.strokeWidth = stroke * 0.7f
            c.drawCircle(cx, cy, rim, line)
            c.save()
            c.rotate(wheelDeg, cx, cy)
            for (k in 0 until 5) {
                val a = Math.toRadians(k * 72.0)
                c.drawLine(cx, cy, cx + (rim * Math.cos(a)).toFloat(), cy + (rim * Math.sin(a)).toFloat(), line)
            }
            c.restore()
        }
        c.restore()
    }

    private companion object {
        const val WIDTH = 250f
        const val GROUND = 160f

        /** Rear and front wheel: centre x, centre y, tyre radius, rim radius. */
        val WHEELS = arrayOf(floatArrayOf(41f, 131f, 29f, 19f), floatArrayOf(214f, 131f, 28.5f, 18.5f))

        /** Seat edge, side-panel crease, lower skirt, leg shield, glovebox, exhaust, windscreen. */
        val DETAILS = arrayOf(
            floatArrayOf(31f, 47f, 45f, 53f, 62f, 59f, 80f, 68f, 100f, 72f, 120f, 72f, 131f, 77f, 136f, 84f),
            floatArrayOf(42f, 79f, 75f, 84f, 110f, 92f, 150f, 99f),
            floatArrayOf(108f, 127f, 165f, 130f, 186f, 113f),
            floatArrayOf(157f, 91f, 155f, 46f),
            floatArrayOf(160f, 49f, 178f, 45f, 184f, 72f, 165f, 77f, 160f, 49f),
            floatArrayOf(13f, 100f, 68f, 110f, 64f, 136f, 10f, 126f, 13f, 100f),
            floatArrayOf(186f, 21f, 206f, 40f, 203f, 44f, 186f, 33f),
        )

        /** Outer outline traced from the Z150X side photo (x, y pairs). */
        val OUTLINE = floatArrayOf(
        147.5f, 0f, 150.3f, 0.3f, 153.3f, 1.9f, 155f, 6.4f, 155f, 13.3f, 157.2f, 20.8f, 161.7f, 20.8f, 163.9f,
        22.8f, 169.7f, 33.6f, 172.2f, 36.1f, 174.2f, 35.3f, 175.3f, 37.2f, 183.9f, 38.9f, 184.4f, 36.9f,
        183.1f, 35.6f, 185.8f, 30.3f, 184.7f, 27.8f, 186.7f, 21.4f, 188.3f, 20f, 204.7f, 37.2f, 210.8f, 45f,
        211.1f, 45.6f, 209.7f, 45.6f, 213.1f, 48.6f, 213.9f, 51.4f, 218.1f, 55.8f, 224.4f, 67.8f, 227.8f,
        77.8f, 221.7f, 76.7f, 221.9f, 77.8f, 224.7f, 78.9f, 226.1f, 80.8f, 222.8f, 82.8f, 223.1f, 87.5f,
        214.4f, 90f, 213.6f, 91.1f, 229.2f, 96.7f, 241.4f, 103.9f, 244.7f, 106.9f, 241.7f, 107.5f, 245.6f,
        112.8f, 248.6f, 120.3f, 249.7f, 126.4f, 249.2f, 135f, 246.4f, 143.3f, 242.2f, 149.7f, 236.9f, 154.7f,
        229.2f, 159.2f, 204.7f, 159.2f, 195.6f, 153.6f, 189.4f, 146.4f, 185.6f, 137.2f, 184.4f, 128.6f, 180.3f,
        142.2f, 177.8f, 144.2f, 126.9f, 140.8f, 122.5f, 139.7f, 117.2f, 136.9f, 111.7f, 138.3f, 106.7f, 138.1f,
        84.7f, 143.3f, 70.3f, 145.6f, 63.9f, 153.3f, 54.4f, 159.2f, 30f, 159.2f, 21.9f, 154.4f, 15.3f, 146.9f,
        10.8f, 137.2f, 10f, 125.6f, 13.1f, 115.3f, 10.6f, 113.3f, 10.6f, 106.7f, 1.1f, 118.6f, 0.6f, 119.2f,
        0f, 117.8f, 1.9f, 110f, 13.1f, 89.2f, 12.5f, 86.4f, 16.7f, 83.6f, 16.7f, 82.5f, 15.3f, 82.2f, 15.8f,
        78.3f, 21.4f, 76.9f, 16.1f, 74.7f, 8.6f, 75.6f, 8.3f, 74.7f, 10.6f, 72.8f, 15.8f, 71.4f, 27.8f, 71.9f,
        28.9f, 71.1f, 23.6f, 65.6f, 20.8f, 64.7f, 20.8f, 63.1f, 15f, 52.2f, 12.5f, 49.7f, 21.7f, 49.7f, 20f,
        47.5f, 12.2f, 44.7f, 9.7f, 42.2f, 24.2f, 42.5f, 31.7f, 45.3f, 71.1f, 46.9f, 80.6f, 56.1f, 88.3f, 60f,
        99.7f, 61.1f, 116.7f, 60.6f, 121.7f, 61.9f, 129.7f, 69.2f, 137.2f, 80.3f, 137.2f, 82.8f, 134.7f, 84.7f,
        134.4f, 88.1f, 145.3f, 91.7f, 154.2f, 88.6f, 170f, 75.8f, 154.4f, 51.9f, 158.9f, 45.6f, 164.4f, 42.8f,
        163.6f, 41.9f, 157.8f, 45f, 160.3f, 42.2f, 160.6f, 36.9f, 159.2f, 35.3f, 156.9f, 35f, 155.8f, 31.9f,
        155.3f, 33.3f, 153.1f, 34.4f, 148.6f, 34.2f, 148.1f, 33.1f, 145.8f, 35f, 143.1f, 34.2f, 141.7f, 32.5f,
        141.9f, 30.3f, 146.7f, 28.1f, 146.7f, 25.6f, 150f, 22.5f, 153.3f, 22.5f, 154.4f, 23.3f, 155.3f, 22.5f,
        155.3f, 18.1f, 153.6f, 14.2f, 151.7f, 12.5f, 150f, 14.2f, 147.8f, 14.7f, 144.4f, 9.4f, 143.6f, 3.6f,
        145.3f, 3.3f
        )

        fun polygon(p: FloatArray) = Path().apply {
            moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
            close()
        }
    }
}

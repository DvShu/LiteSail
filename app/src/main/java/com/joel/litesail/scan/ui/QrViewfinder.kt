package com.joel.litesail.scan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.joel.litesail.R

internal class QrViewfinder(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val frame = RectF()
    private val mask = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val corner = Path()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.scanner_scrim)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.scanner_guide)
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }

    val windowSize: Float get() = frame.width()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = minOf(minOf(w, h) * 0.76f, 320 * density)
        val left = (w - side) / 2
        val top = (h - side) / 2
        val radius = minOf(16 * density, side * 0.15f)
        val arm = minOf(32 * density, side / 3)
        frame.set(left, top, left + side, top + side)

        mask.rewind()
        mask.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        mask.addRoundRect(frame, radius, radius, Path.Direction.CW)

        corner.rewind()
        corner.moveTo(left, top + arm)
        corner.lineTo(left, top + radius)
        corner.quadTo(left, top, left + radius, top)
        corner.lineTo(left + arm, top)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(mask, maskPaint)
        canvas.save()
        repeat(4) {
            canvas.drawPath(corner, cornerPaint)
            canvas.rotate(90f, frame.centerX(), frame.centerY())
        }
        canvas.restore()
    }
}

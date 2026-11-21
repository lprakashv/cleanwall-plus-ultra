package dev.lprakashv.cleanwall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal class UsageBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(value: Long, peak: Long) {
        val next = (value.toFloat() / peak.coerceAtLeast(1L)).coerceIn(0f, 1f)
        if (next == fraction) return
        fraction = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val right = width.toFloat()
        val bottom = height.toFloat()
        paint.color = HomeTheme.BOUNDARY
        canvas.drawRect(0f, 0f, right, bottom, paint)
        paint.color = HomeTheme.ACCENT
        canvas.drawRect(0f, 0f, right * fraction, bottom, paint)
    }
}

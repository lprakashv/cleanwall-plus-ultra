package dev.lprakashv.cleanwall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

internal class LaunchChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HomeTheme.MUTED
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    }
    private var counts = emptyList<Long>()
    private var initials = emptyList<String>()

    fun update(values: List<Long>, dayInitials: List<String> = emptyList()) {
        if (values == counts && dayInitials == initials) return
        counts = values.toList()
        initials = dayInitials.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (counts.isEmpty()) return
        val peak = counts.max().coerceAtLeast(1L)
        val cell = width.toFloat() / counts.size
        val axisHeight = if (initials.isEmpty()) 0f else axisPaint.fontSpacing + resources.displayMetrics.density * 2
        val bottom = (height - axisHeight).coerceAtLeast(2f)
        counts.forEachIndexed { index, count ->
            val barHeight = (bottom * count.toFloat() / peak).coerceIn(2f, bottom)
            paint.color = if (index == counts.lastIndex) HomeTheme.ACCENT else 0xFF8F9FD8.toInt()
            canvas.drawRoundRect(cell * (index + .19f), bottom - barHeight, cell * (index + .81f), bottom, 3f, 3f, paint)
            initials.getOrNull(index)?.let {
                canvas.drawText(it, cell * (index + .5f), height - axisPaint.descent(), axisPaint)
            }
        }
    }
}

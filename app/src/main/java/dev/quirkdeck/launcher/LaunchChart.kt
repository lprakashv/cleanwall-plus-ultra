package dev.quirkdeck.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

internal class LaunchChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var counts = emptyList<Long>()

    fun update(values: List<Long>) {
        if (values == counts) return
        counts = values.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (counts.isEmpty()) return
        val peak = counts.max().coerceAtLeast(1L)
        val cell = width.toFloat() / counts.size
        counts.forEachIndexed { index, count ->
            val barHeight = (height * count.toFloat() / peak).coerceAtLeast(2f)
            paint.color = if (index == counts.lastIndex) Color.rgb(156, 213, 230) else Color.rgb(96, 141, 176)
            canvas.drawRoundRect(cell * (index + .19f), height - barHeight, cell * (index + .81f), height.toFloat(), 3f, 3f, paint)
        }
    }
}

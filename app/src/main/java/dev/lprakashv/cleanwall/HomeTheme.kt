package dev.lprakashv.cleanwall

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import kotlin.math.roundToInt

internal class HomeTheme(context: Context, glassAlpha: Int) {
    private val density = context.resources.displayMetrics.density
    val surface = Color.argb(glassAlpha, 29, 35, 54)
    val dialogSurface = Color.argb(150 + glassAlpha * 100 / 255, 29, 35, 54)
    private val focusSurface = Color.argb((glassAlpha + 76).coerceAtMost(255), 55, 73, 105)
    private val selectedSurface = Color.argb(glassAlpha, 62, 79, 110)

    fun shape(fill: Int, radius: Int = 4, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        cornerRadius = radius * density
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(density.roundToInt(), stroke)
    }

    fun control(resting: Int = surface, selected: Boolean = false) = StateListDrawable().apply {
        val border = if (resting == Color.TRANSPARENT) Color.TRANSPARENT else BOUNDARY
        addState(intArrayOf(android.R.attr.state_focused), shape(focusSurface, stroke = border))
        addState(intArrayOf(android.R.attr.state_activated), shape(focusSurface, stroke = border))
        addState(intArrayOf(android.R.attr.state_selected), shape(selectedSurface, stroke = border))
        addState(intArrayOf(), shape(if (selected) selectedSurface else resting, stroke = border))
    }

    companion object {
        const val INK = Color.WHITE
        val MUTED = 0xFFE3EBF7.toInt()
        val ACCENT = 0xFFC1DCFF.toInt()
        const val BOUNDARY = 0x1ADAE7FF
    }
}

package dev.lprakashv.cleanwall

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal class WeekChart(
    context: Context,
    theme: HomeTheme,
    days: List<Day>,
    peak: Long,
    selected: Int,
    focusDay: (Int) -> Unit,
    openDay: (Int) -> Unit = focusDay
) : LinearLayout(context) {
    data class Day(val label: String, val caption: String, val description: String, val value: Long)

    private var selectedIndex = selected
    val cards = days.mapIndexed { index, day ->
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            id = View.generateViewId()
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusableInTouchMode = true
            background = theme.control(Color.TRANSPARENT)
            setPadding(dp(2), dp(7), dp(2), dp(6))
            contentDescription = day.description
            addView(caption(day.caption, 17f, HomeTheme.ACCENT))

            val graph = FrameLayout(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(View(context).apply { background = theme.shape(HomeTheme.BOUNDARY, radius = 2) },
                    FrameLayout.LayoutParams(dp(18), -1, Gravity.CENTER_HORIZONTAL))
            }
            val bar = View(context).apply { background = theme.shape(HomeTheme.ACCENT, radius = 2) }
            graph.addView(bar, FrameLayout.LayoutParams(dp(18), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            val fraction = (day.value.toFloat() / peak.coerceAtLeast(1)).coerceIn(0f, 1f)
            graph.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    val height = ((bottom - top) * fraction).roundToInt().coerceAtLeast(dp(2))
                    bar.layoutParams = bar.layoutParams.apply { this.height = height }
                }
            }
            addView(graph, LayoutParams(-1, 0, 1f).apply { topMargin = dp(3) })
            addView(caption(day.label, 13f, HomeTheme.INK), LayoutParams(-2, -2).apply { topMargin = dp(6) })
            isSelected = index == selected
            setOnFocusChangeListener { _, focused ->
                if (focused) {
                    select(index)
                    focusDay(index)
                }
            }
            setOnClickListener {
                select(index)
                openDay(index)
            }
        }
        addView(card, LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(3) })
        card
    }

    private fun select(index: Int) {
        cards[selectedIndex].isSelected = false
        selectedIndex = index
        cards[index].isSelected = true
    }

    private fun caption(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

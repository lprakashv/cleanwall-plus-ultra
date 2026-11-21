package dev.lprakashv.cleanwall

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

internal fun weatherDayView(context: Context, day: Weather.ForecastDay, theme: HomeTheme? = null): View {
    val locale = context.resources.configuration.locales[0]
    val date = day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMM", locale))
    val large = theme != null
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        if (theme != null) {
            isFocusable = true
            background = theme.control()
        }
        contentDescription = context.getString(R.string.weather_day_accessibility, date,
            context.getString(day.description), day.maximum)
        listOf(
            day.date.dayOfWeek.getDisplayName(if (large) TextStyle.SHORT else TextStyle.NARROW, locale) to
                if (large) 16f else 11f,
            day.symbol to if (large) 32f else 15f,
            context.getString(R.string.weather_degrees, day.maximum) to if (large) 22f else 12f
        ).forEach { (value, size) ->
            addView(TextView(context).apply {
                text = value
                textSize = size
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(HomeTheme.INK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }
}

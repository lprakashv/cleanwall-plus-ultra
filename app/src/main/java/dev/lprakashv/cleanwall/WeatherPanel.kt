package dev.lprakashv.cleanwall

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal class WeatherPanel(
    private val context: Context,
    private val city: String,
    private val current: Weather.Conditions,
    private val stale: Boolean,
    private val theme: HomeTheme
) {
    private val days = current.forecast

    fun show() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = column().apply {
            setPadding(dp(24), dp(18), dp(24), dp(20))
            background = theme.shape(theme.dialogSurface, stroke = HomeTheme.BOUNDARY)
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(column().apply {
            addView(text(string(R.string.weather_forecast_title), 30, bold = true))
            addView(text(string(if (stale) R.string.weather_forecast_stale_source else R.string.weather_forecast_source, city),
                14, HomeTheme.MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_location, 0, 0, 0)
                compoundDrawablePadding = dp(5)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("×", 26, bold = true).apply {
            contentDescription = string(R.string.close_usage)
            gravity = Gravity.CENTER
            background = theme.control(Color.TRANSPARENT)
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(header)

        val today = days.first()
        val metrics = LinearLayout(context)
        val now = metric(string(R.string.today), "${temperature(current.celsius)}  ${current.symbol}").apply {
            contentDescription = string(R.string.weather_current_accessibility,
                temperature(current.celsius), string(current.description))
        }
        addMetrics(metrics, listOf(
            now,
            metric(string(R.string.weather_high), temperature(today.maximum)),
            metric(string(R.string.weather_low), temperature(today.minimum)),
            metric(string(R.string.weather_humidity), string(R.string.weather_percent, current.humidity))
        ))
        root.addView(metrics, LinearLayout.LayoutParams(-1, dp(65)).apply { topMargin = dp(14) })

        val upcoming = LinearLayout(context)
        val details = surface()
        val predictions = days.drop(1)
        predictions.firstOrNull()?.let { first ->
            addMetrics(upcoming, predictions.map { day ->
                weatherDayView(context, day, theme).apply {
                    setOnFocusChangeListener { _, focused -> if (focused) showDay(details, day) }
                    setOnClickListener { showDay(details, day) }
                }
            })
            root.addView(text(string(R.string.weather_choose_day), 15, HomeTheme.MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            root.addView(upcoming, LinearLayout.LayoutParams(-1, dp(94)).apply { topMargin = dp(6) })
            root.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            showDay(details, first)
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.48f)
        }
        dialog.show()
        val screen = context.resources.configuration
        dialog.window?.setLayout(dp((screen.screenWidthDp - 60).coerceIn(1, 920)), -2)
        upcoming.getChildAt(0)?.requestFocus()
    }

    private fun showDay(details: LinearLayout, day: Weather.ForecastDay) {
        val locale = context.resources.configuration.locales[0]
        val date = day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMM", locale))
        details.removeAllViews()
        details.addView(text(date, 19, bold = true))
        details.addView(text("${day.symbol}  ${string(day.description)}", 16, HomeTheme.ACCENT))
        val readings = buildList {
            add(metric(string(R.string.weather_high), temperature(day.maximum)))
            add(metric(string(R.string.weather_low), temperature(day.minimum)))
            day.precipitationChance?.takeIf { it > 0 }?.let {
                add(metric(string(R.string.weather_precipitation_chance), string(R.string.weather_percent, it)))
            }
            day.precipitationMm?.takeIf { it > 0 }?.let {
                add(metric(string(R.string.weather_precipitation_total), string(R.string.weather_millimeters, it)))
            }
        }
        val metrics = LinearLayout(context)
        addMetrics(metrics, readings)
        details.addView(metrics, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(6) })
    }

    private fun addMetrics(row: LinearLayout, views: List<View>) {
        views.forEachIndexed { index, view ->
            row.addView(view, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun metric(name: String, value: String) = surface().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(5), dp(8), dp(5))
        addView(text(name, 13, HomeTheme.MUTED, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(text(value, 20, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun surface() = column().apply {
        background = theme.shape(theme.surface, stroke = HomeTheme.BOUNDARY)
        setPadding(dp(14), dp(12), dp(14), dp(10))
    }

    private fun text(value: String, size: Int, color: Int = HomeTheme.INK, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun temperature(degrees: Int) = string(R.string.temperature, degrees)
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}

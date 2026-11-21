package dev.lprakashv.cleanwall

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.roundToInt

internal class HomeWidgets(
    private val context: Context,
    specs: List<WidgetSpec>,
    availableWidth: Int,
    private val theme: HomeTheme,
    private val motion: () -> Boolean,
    private val runAction: (WidgetSpec) -> Unit
) {
    private val secondary = HomeTheme.MUTED
    private val ink = HomeTheme.INK
    private val accent = HomeTheme.ACCENT
    private val dataCards = mutableListOf<Pair<WidgetSpec, DataReading>>()

    fun uses(device: WidgetSource.Device) = dataCards.any { (spec, _) -> spec.source == device }
    val hasAppShortcuts get() = dataCards.any { (spec, _) -> spec.source is WidgetSource.App }
    val rowHeightDp get() = when {
        uses(WidgetSource.Device.WEATHER) -> 124
        dataCards.any { (_, reading) -> reading.chart != null || reading.apps.isNotEmpty() } -> 124
        else -> 98
    }
    private val row = LinearLayout(context)
    val tiles: List<View> get() = List(row.childCount, row::getChildAt)
    val container = HorizontalScrollView(context).apply {
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
    }

    private data class Reading(val primary: TextView, val detail: TextView)
    private class DataReading(
        val text: Reading, val icon: TextView, val chart: LaunchChart?, val apps: List<AppReading>,
        val weather: WeatherReading?
    ) {
        var content: WidgetContent? = null
    }
    private data class WeatherReading(
        val range: TextView, val humidity: TextView, val week: LinearLayout, val daysPerRow: Int
    )
    private data class AppReading(
        val row: LinearLayout, val icon: ImageView, val name: TextView, val duration: TextView, val bar: UsageBar
    ) {
        fun show(app: UsagePanel.AppCount?, peak: Long) {
            row.visibility = if (app == null) View.GONE else View.VISIBLE
            val artwork = app?.icon
            icon.setImageDrawable(artwork?.constantState?.newDrawable(row.resources) ?: artwork)
            name.text = app?.label
            duration.text = app?.let { activeDuration(row.context, it.count) }
            bar.update(app?.count ?: 0L, peak)
        }
    }

    init {
        specs.forEachIndexed { index, spec ->
            val cardWidth = spec.widthForViewport(availableWidth)
            val tile = dataCard(spec, cardWidth)
            row.addView(tile, LinearLayout.LayoutParams(dp(cardWidth), -1).apply {
                if (index > 0) marginStart = dp(WIDGET_GAP_DP)
            })
        }
        container.addView(row, FrameLayout.LayoutParams(-2, -1))
    }

    fun slide(tile: View, offset: Int) {
        val from = row.indexOfChild(tile)
        val target = from + offset
        if (from < 0 || target !in 0 until row.childCount) return
        val neighbor = row.getChildAt(target)
        row.removeViewAt(target)
        row.addView(neighbor, from)
        if (minOf(from, target) == 0) {
            for (index in 0..1) {
                val card = row.getChildAt(index)
                card.layoutParams = (card.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = if (index == 0) 0 else dp(WIDGET_GAP_DP)
                }
            }
        }
    }

    private fun dataCard(spec: WidgetSpec, cardWidth: Int): View {
        val source = spec.source
        val chart = if (ActivityComponent.CHART in spec.activityComponents) LaunchChart(context) else null
        val weather = if (source == WidgetSource.Device.WEATHER) WeatherReading(
            label(14f, secondary),
            label(13f, secondary),
            LinearLayout(context).apply { orientation = LinearLayout.VERTICAL },
            daysPerRow = if (cardWidth < 340) 3 else 6
        ) else null
        val apps = if (ActivityComponent.ROWS in spec.activityComponents) List(2) { appReading() } else emptyList()
        val style = spec.style
        val compactWeather = weather?.daysPerRow == 3
        val text = Reading(label(if (compactWeather) 20f else style.size, ink, style.bold).apply {
            maxLines = style.lines
        }, label(14f, secondary))
        val icon = label(if (compactWeather) 18f else if (spec.iconAtEnd) 27f else 25f, accent).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            maxWidth = dp(44)
        }
        val reading = DataReading(text, icon, chart, apps, weather)
        val title = context.getString(spec.title)
        dataCards.add(spec to reading)
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            id = View.generateViewId()
            isFocusable = true
            background = theme.control()
            elevation = dp(2).toFloat()
            setPadding(dp(15), dp(8), dp(15), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            addView(label(13f, accent, bold = true).apply { this.text = title })
            if (spec.action != WidgetAction.NONE) {
                setOnClickListener {
                    if (spec.action == WidgetAction.DETAILS) {
                        val lines = listOfNotNull(text.primary.text, reading.content?.detail, text.detail.text)
                        HomeSheet(context, title, theme).message(lines.filter { it.isNotBlank() }.distinct().joinToString("\n"))
                    } else {
                        runAction(spec)
                    }
                }
            }
            val headline = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (weather == null) {
                    if (!spec.iconAtEnd) addView(icon, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    addView(text.primary, LinearLayout.LayoutParams(0, -2, 1f))
                    if (spec.iconAtEnd) addView(icon, LinearLayout.LayoutParams(-2, -2))
                } else {
                    addView(text.primary, LinearLayout.LayoutParams(-2, -2))
                    addView(icon, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
                }
                if (chart != null) {
                    addView(chart, LinearLayout.LayoutParams(dp(minOf(104, cardWidth - 100)), dp(36)).apply {
                        marginStart = dp(6)
                    })
                }
            }
            if (weather != null) {
                text.detail.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_location, 0, 0, 0)
                text.detail.compoundDrawablePadding = dp(5)
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(headline)
                        addView(weather.range)
                        addView(text.detail)
                        addView(weather.humidity)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(weather.week, LinearLayout.LayoutParams(0, -2, if (compactWeather) 1f else 1.5f).apply {
                        marginStart = dp(8)
                    })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            } else {
                addView(headline)
                if (ActivityComponent.DETAIL_ICON in spec.activityComponents) text.detail.compoundDrawablePadding = dp(5)
                addView(text.detail)
                apps.forEach { addView(it.row, LinearLayout.LayoutParams(-1, dp(18))) }
            }
        }
        val initial = WidgetUpdates.Snapshot(
            values = if (source == null) emptyMap() else null,
            loading = source != null && source !is WidgetSource.Remote
        )
        showData(spec, reading, initial)
        return tile
    }

    fun showWeather(city: String, current: Weather.Conditions?, loading: Boolean, stale: Boolean) {
        val values = mapOf(
            "value" to if (current == null) {
                context.getString(if (loading) R.string.loading_weather else R.string.weather_unavailable)
            } else context.getString(R.string.temperature, current.celsius),
            "summary" to if (stale) context.getString(R.string.weather_stale, city) else city,
            "symbol" to current?.symbol.orEmpty()
        )
        val today = current?.forecast?.firstOrNull()
        dataCards.forEach { (spec, reading) ->
            val weather = reading.weather ?: return@forEach
            showData(spec, reading, WidgetUpdates.Snapshot(values))
            reading.icon.importantForAccessibility = if (current == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            reading.icon.contentDescription = current?.let { context.getString(it.description) }
            weather.range.text = today?.let {
                context.getString(R.string.weather_today_range, it.maximum, it.minimum)
            }
            weather.range.contentDescription = today?.let {
                context.getString(R.string.weather_today_range_accessibility, it.maximum, it.minimum)
            }
            weather.humidity.text = current?.let { context.getString(R.string.weather_humidity_reading, it.humidity) }
            weather.humidity.visibility = if (current == null) View.GONE else View.VISIBLE
            weather.week.apply {
                removeAllViews()
                visibility = if (current == null) View.GONE else View.VISIBLE
                current?.forecast.orEmpty().drop(1).chunked(weather.daysPerRow).forEach { days ->
                    addView(LinearLayout(context).apply {
                        weightSum = weather.daysPerRow.toFloat()
                        days.forEach { day ->
                            addView(weatherDayView(context, day), LinearLayout.LayoutParams(0, -2, 1f))
                        }
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
    }

    fun showAppShortcuts(values: (WidgetSource.App) -> Map<String, String>) {
        dataCards.forEach { (spec, reading) ->
            val source = spec.source as? WidgetSource.App ?: return@forEach
            showData(spec, reading, WidgetUpdates.Snapshot(values(source)))
        }
    }

    fun showData(source: WidgetSource, snapshot: WidgetUpdates.Snapshot) {
        dataCards.forEach { (spec, reading) ->
            if (spec.source == source) showData(spec, reading, snapshot)
        }
    }

    private fun showData(spec: WidgetSpec, reading: DataReading, snapshot: WidgetUpdates.Snapshot) {
        val content = snapshot.values?.let(spec.content)
        reading.content = content
        val icon = content?.icon
        val waitingForActivity = spec.source == WidgetSource.Device.ACTIVITY && snapshot.values == null
        val opensActivity = spec.action == WidgetAction.APP_ACTIVITY
        val primary = content?.text ?: context.getString(when {
            waitingForActivity && snapshot.failed -> R.string.usage_unavailable
            waitingForActivity -> R.string.usage_loading
            snapshot.loading -> R.string.widget_data_loading
            snapshot.failed || snapshot.values != null -> R.string.widget_data_unavailable
            else -> R.string.widget_data_waiting
        })
        val changed = reading.text.primary.text != primary || reading.icon.text != icon.orEmpty()
        if (changed) {
            reading.text.primary.text = primary
            reading.icon.text = icon
            if (spec.animate) animateReading(reading.text.primary)
        }
        reading.icon.visibility = if (icon.isNullOrBlank()) View.GONE else View.VISIBLE
        val detail = when {
            waitingForActivity && opensActivity && snapshot.failed -> context.getString(R.string.usage_settings_hint)
            opensActivity && reading.chart != null && (waitingForActivity || content?.detail == "") ->
                context.getString(R.string.usage_details_hint)
            content != null && snapshot.failed -> context.getString(R.string.widget_data_stale)
            content == null && !snapshot.loading && spec.action == WidgetAction.REFRESH ->
                context.getString(R.string.widget_data_refresh_hint)
            else -> content?.detail
        }
        if (reading.text.detail.text != detail.orEmpty()) reading.text.detail.text = detail
        reading.text.detail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun showActivity(
        totals: List<Pair<LocalDate, Long>>?, loaded: Boolean, apps: List<UsagePanel.AppCount>, blocks: Map<TimeBlock, Long>
    ) {
        val peakBlock = if (totals == null) null else blocks.maxByOrNull { it.value }?.takeIf { it.value > 0 }
        val values = totals?.lastOrNull()?.second?.let { today ->
            mapOf(
                "today" to activeDuration(context, today),
                "todayMillis" to today.toString(),
                "peak" to peakBlock?.let {
                    context.getString(R.string.activity_summary_item, context.getString(it.key.label), activeDuration(context, it.value))
                }.orEmpty(),
                "peakMillis" to (peakBlock?.value ?: 0L).toString()
            )
        }
        val snapshot = WidgetUpdates.Snapshot(values, loading = !loaded, failed = loaded && values == null)
        val peakAppTime = apps.firstOrNull()?.count ?: 0L
        dataCards.forEach { (spec, reading) ->
            if (spec.source != WidgetSource.Device.ACTIVITY) return@forEach
            showData(spec, reading, snapshot)
            if (ActivityComponent.DETAIL_ICON in spec.activityComponents) {
                reading.text.detail.setCompoundDrawablesRelativeWithIntrinsicBounds(peakBlock?.key?.icon ?: 0, 0, 0, 0)
            }
            reading.apps.forEachIndexed { index, appReading ->
                appReading.show(apps.getOrNull(index), peakAppTime)
            }
            reading.chart?.apply {
                visibility = if (totals == null) View.GONE else View.VISIBLE
                val days = totals.orEmpty()
                val locale = context.resources.configuration.locales[0]
                update(days.map { it.second }, days.map { (date, _) ->
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                })
                val format = DateTimeFormatter.ofPattern("EEEE, d MMM", locale)
                contentDescription = days.joinToString(", ") { (date, time) ->
                    context.getString(R.string.selected_day, date.format(format), activeDuration(context, time))
                }
            }
        }
    }

    private fun appReading(): AppReading {
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val name = label(13f, ink)
        val duration = label(13f, secondary).apply { gravity = Gravity.END }
        val bar = UsageBar(context)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(icon, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) })
            addView(name, LinearLayout.LayoutParams(0, -2, 2.4f))
            addView(duration, LinearLayout.LayoutParams(dp(59), -2))
            addView(bar, LinearLayout.LayoutParams(0, dp(4), 1f).apply { marginStart = dp(8) })
        }
        return AppReading(row, icon, name, duration, bar)
    }

    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun animateReading(view: View) {
        if (!motion() || !view.isAttachedToWindow) return
        view.animate().cancel()
        view.alpha = .45f
        view.translationY = dp(4).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}

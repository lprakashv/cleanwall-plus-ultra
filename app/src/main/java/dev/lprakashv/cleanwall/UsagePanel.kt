package dev.lprakashv.cleanwall

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal class UsagePanel(
    private val context: Context,
    private val days: List<Day>,
    private val motion: Boolean,
    private val theme: HomeTheme
) {
    data class AppCount(val id: String, val label: String, val count: Long, val icon: Drawable?)
    data class Day(val date: LocalDate, val apps: List<AppCount>) {
        val total = apps.sumOf { it.count }
    }

    private val dialog = Dialog(context)
    private val ink = HomeTheme.INK
    private val muted = HomeTheme.MUTED
    private val accent = HomeTheme.ACCENT
    private val dayName = DateTimeFormatter.ofPattern("EEE")
    private val dateName = DateTimeFormatter.ofPattern("EEEE, d MMM")
    private var selectedDay = days.lastIndex
    private var openApp: AppCount? = null
    private lateinit var details: LinearLayout
    private lateinit var dayCards: List<LinearLayout>
    private var appRows = emptyMap<String, View>()

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = column().apply {
            setPadding(dp(24), dp(18), dp(24), dp(20))
            background = theme.shape(theme.dialogSurface, stroke = HomeTheme.BOUNDARY)
        }

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = column().apply {
            addView(text(string(R.string.app_activity), 30, bold = true))
            addView(text(string(R.string.time_from_android), 14, muted))
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("×") { dialog.dismiss() }.apply {
            contentDescription = string(R.string.close_usage)
            textSize = 26f
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(header)

        val total = days.sumOf { it.total }
        val mostOpened = days.flatMap { it.apps }.groupBy { it.id }.values
            .maxByOrNull { items -> items.sumOf { it.count } }
        val metrics = LinearLayout(context)
        listOf(
            string(R.string.today) to duration(days.last().total),
            string(R.string.last_seven_days) to duration(total),
            string(R.string.most_active) to (mostOpened?.first()?.label ?: string(R.string.no_app_activity))
        ).forEachIndexed { index, (name, value) ->
            metrics.addView(metric(name, value), LinearLayout.LayoutParams(0, dp(65), 1f).apply {
                marginStart = if (index == 0) 0 else dp(10)
            })
        }
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val body = LinearLayout(context)
        body.addView(weekPanel(), LinearLayout.LayoutParams(0, -1, 1.25f))
        details = column().apply {
            background = theme.shape(theme.surface, stroke = HomeTheme.BOUNDARY)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        body.addView(details, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(12) })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })

        showDay(days[selectedDay])
        dialog.setContentView(root)
        dialog.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_BACK || openApp == null) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) backToDay()
            true
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.48f)
        }
        dialog.show()
        val screen = context.resources.configuration
        dialog.window?.setLayout(dp((screen.screenWidthDp - 60).coerceIn(1, 920)), dp((screen.screenHeightDp - 56).coerceIn(1, 470)))
        dayCards.last().requestFocus()
    }

    private fun weekPanel(): LinearLayout {
        val panel = column().apply {
            background = theme.shape(theme.surface, stroke = HomeTheme.BOUNDARY)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(text(string(R.string.last_seven_days), 20, bold = true))
        }
        val bars = WeekChart(context, theme, days.mapIndexed { index, day ->
            WeekChart.Day(
                label = if (index == days.lastIndex) string(R.string.today) else day.date.format(dayName),
                caption = duration(day.total, compact = true),
                description = context.getString(R.string.selected_day, day.date.format(dateName), duration(day.total)),
                value = day.total
            )
        }, days.maxOf { it.total }, selectedDay, ::selectDay) { index ->
            selectDay(index)
            appRows.values.firstOrNull()?.requestFocus()
        }
        dayCards = bars.cards
        panel.addView(bars, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        panel.addView(text(string(R.string.day_action), 13, muted), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        return panel
    }

    private fun metric(name: String, value: String) = column().apply {
        background = theme.shape(theme.surface, stroke = HomeTheme.BOUNDARY)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        addView(text(name, 13, muted, true))
        addView(text(value, 20, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    private fun selectDay(index: Int) {
        if (selectedDay == index && openApp == null) return
        selectedDay = index
        showDay(days[index])
    }

    private fun showDay(day: Day) {
        openApp = null
        details.removeAllViews()
        details.addView(text(day.date.format(dateName), 20, bold = true))
        details.addView(text(context.getString(R.string.app_total_for_day, duration(day.total), string(R.string.day_app_list)), 14, muted))
        val items = column()
        val peak = day.apps.firstOrNull()?.count ?: 1
        appRows = day.apps.associate { app ->
            val row = column().apply {
                id = View.generateViewId()
                isFocusable = true
                nextFocusLeftId = dayCards[selectedDay].id
                background = theme.control(Color.TRANSPARENT)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                val name = text(app.label, 16, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    app.icon?.let { addView(appIcon(it), LinearLayout.LayoutParams(dp(23), dp(23)).apply { marginEnd = dp(8) }) }
                    addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(duration(app.count), 14, accent))
                })
                addView(UsageBar(context).apply { update(app.count, peak) },
                    LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
                setOnClickListener { showApp(app) }
            }
            items.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            app.id to row
        }
        if (appRows.isEmpty()) items.addView(text(string(R.string.no_launches), 16, muted))
        details.addView(ScrollView(context).apply { addView(items) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(7) })
        dayCards.last().nextFocusRightId = appRows.values.firstOrNull()?.id ?: View.NO_ID
    }

    private fun showApp(app: AppCount) {
        openApp = app
        details.removeAllViews()
        details.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            app.icon?.let { addView(appIcon(it), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) }) }
            addView(text(app.label, 20, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        details.addView(text(string(R.string.app_trend), 14, muted))
        val counts = days.map { day -> day.apps.firstOrNull { it.id == app.id }?.count ?: 0L }
        val chart = LaunchChart(context).apply {
            update(counts)
            contentDescription = days.zip(counts).joinToString(", ") { (day, count) ->
                context.getString(R.string.selected_day, day.date.format(dateName), duration(count))
            }
        }
        details.addView(chart, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        val axis = LinearLayout(context)
        days.forEachIndexed { index, day ->
            val caption = text("${day.date.format(dayName)}\n${duration(counts[index], compact = true)}", 13, muted).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            axis.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        }
        details.addView(axis)
        val back = button(string(R.string.back_to_day), ::backToDay)
        back.nextFocusLeftId = dayCards[selectedDay].id
        details.addView(back, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        back.requestFocus()
        if (motion) {
            chart.alpha = .35f
            chart.animate().alpha(1f).setDuration(180).start()
        }
    }

    private fun backToDay() {
        val app = openApp ?: return
        showDay(days[selectedDay])
        (appRows[app.id] ?: dayCards[selectedDay]).requestFocus()
    }

    private fun duration(value: Long, compact: Boolean = false) = activeDuration(context, value, compact)

    private fun string(id: Int) = context.getString(id)

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun appIcon(icon: Drawable) = ImageView(context).apply {
        setImageDrawable(icon.constantState?.newDrawable(context.resources) ?: icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(value: String, action: () -> Unit) = text(value, 15, bold = true).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        background = theme.control(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

}

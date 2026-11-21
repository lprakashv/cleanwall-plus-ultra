package dev.quirkdeck.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal class UsagePanel(
    private val context: Context,
    private val days: List<Day>,
    private val motion: Boolean
) {
    data class AppCount(val id: String, val label: String, val count: Long)
    data class Day(val date: LocalDate, val apps: List<AppCount>) {
        val total = apps.sumOf { it.count }
    }

    private val dialog = Dialog(context)
    private val ink = Color.rgb(241, 247, 252)
    private val muted = Color.rgb(175, 194, 214)
    private val accent = Color.rgb(156, 213, 230)
    private val glass = 0x7A263B51
    private val border = 0x55788FA9
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF24394D.toInt(), 0xFF132235.toInt())
            ).apply {
                cornerRadius = dp(23).toFloat()
                setStroke(dp(1), border)
            }
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
            background = shape(glass, border)
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
            setDimAmount(.42f)
        }
        dialog.show()
        val screen = context.resources.configuration
        dialog.window?.setLayout(dp((screen.screenWidthDp - 60).coerceIn(1, 920)), dp((screen.screenHeightDp - 56).coerceIn(1, 470)))
        dayCards.last().requestFocus()
    }

    private fun weekPanel(): LinearLayout {
        val panel = column().apply {
            background = shape(glass, border)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(text(string(R.string.last_seven_days), 20, bold = true))
        }
        val peak = days.maxOf { it.total }.coerceAtLeast(1)
        val bars = LinearLayout(context)
        dayCards = days.mapIndexed { index, day ->
            val dayLabel = if (index == days.lastIndex) string(R.string.today) else day.date.format(dayName)
            val card = column().apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusableInTouchMode = true
                background = controlBackground()
                setPadding(dp(2), dp(7), dp(2), dp(6))
                contentDescription = context.getString(R.string.selected_day, day.date.format(dateName), duration(day.total))
                addView(text(duration(day.total, compact = true), 17, accent, true).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                val graph = FrameLayout(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    addView(View(context).apply { background = shape(0x553C5B76, radius = 6) },
                        FrameLayout.LayoutParams(dp(18), -1, Gravity.CENTER_HORIZONTAL))
                }
                val bar = View(context).apply { background = shape(accent, radius = 6) }
                graph.addView(bar, FrameLayout.LayoutParams(dp(18), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                graph.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) {
                        val height = ((bottom - top) * day.total.toFloat() / peak).roundToInt().coerceAtLeast(dp(2))
                        bar.layoutParams = bar.layoutParams.apply { this.height = height }
                    }
                }
                addView(graph, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(3) })
                addView(text(dayLabel, 13, bold = true).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                isSelected = index == selectedDay
                setOnFocusChangeListener { _, focused -> if (focused) selectDay(index) }
                setOnClickListener {
                    selectDay(index)
                    appRows.values.firstOrNull()?.requestFocus()
                }
            }
            bars.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = if (index == 0) 0 else dp(3)
            })
            card
        }
        panel.addView(bars, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        panel.addView(text(string(R.string.day_action), 13, muted), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        return panel
    }

    private fun metric(name: String, value: String) = column().apply {
        background = shape(glass, border)
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
        dayCards[selectedDay].isSelected = false
        selectedDay = index
        dayCards[index].isSelected = true
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
                background = controlBackground()
                setPadding(dp(10), dp(6), dp(10), dp(6))
                val name = text(app.label, 16, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(duration(app.count), 14, accent))
                })
                addView(LaunchShare(context, app.count.toFloat() / peak, accent),
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
        details.addView(text(app.label, 20, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
        background = controlBackground()
        setOnClickListener { action() }
    }

    private fun controlBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(0xCC3F617A.toInt(), accent, 12))
        addState(intArrayOf(android.R.attr.state_selected), shape(0x9939506F.toInt(), 0xAA9CD5E6.toInt(), 12))
        addState(intArrayOf(), shape(0x22426383, radius = 12))
    }

    private fun shape(color: Int, stroke: Int = color, radius: Int = 14) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        setStroke(dp(1), stroke)
    }

    private class LaunchShare(context: Context, private val share: Float, private val fill: Int) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            paint.color = 0x445F9CAA
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = fill
            canvas.drawRect(0f, 0f, width * share, height.toFloat(), paint)
        }
    }
}

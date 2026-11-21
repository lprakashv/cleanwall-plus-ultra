package dev.lprakashv.cleanwall

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

internal class HomeSheet(private val context: Context, title: String, private val theme: HomeTheme) {
    data class Choice(val title: String, val detail: String? = null, val icon: Drawable? = null)

    private val dialog = Dialog(context)
    private val body = column()
    private val ink = HomeTheme.INK
    private val muted = HomeTheme.MUTED
    private val accent = HomeTheme.ACCENT

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = column().apply {
            background = theme.shape(theme.dialogSurface, stroke = HomeTheme.BOUNDARY)
            setPadding(dp(22), dp(18), dp(22), dp(19))
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text(title, 27, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text("×", 26).apply {
            contentDescription = context.getString(R.string.close_usage)
            gravity = Gravity.CENTER
            isFocusableInTouchMode = true
            background = theme.control(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(8) })
        root.addView(heading)
        root.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(body)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(15) })
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.48f)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            attributes = attributes.apply { x = dp(32) }
        }
    }

    fun choices(options: List<String>, selected: Int = -1, choose: (Int) -> Unit) {
        items(options.map { Choice(it) }, selected, choose)
    }

    fun items(options: List<Choice>, selected: Int = -1, choose: (Int) -> Unit) {
        options.forEachIndexed { index, option ->
            val row = actionRow(option, selected == index) {
                dialog.dismiss()
                choose(index)
            }
            body.addView(row, LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(3) })
        }
        show()
        body.getChildAt(selected.coerceAtLeast(0))?.requestFocus()
    }

    fun message(message: String) {
        body.addView(text(message, 16, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        show()
    }

    fun checks(options: List<Choice>, selected: Set<Int>, change: (Int, Boolean) -> Unit) {
        val boxes = options.mapIndexed { index, option ->
            CheckBox(context).apply {
                text = option.title
                textSize = 16f
                setTextColor(ink)
                background = theme.control(Color.TRANSPARENT)
                isChecked = index in selected
                isFocusableInTouchMode = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                option.icon?.let { original ->
                    val icon = original.constantState?.newDrawable(context.resources)?.mutate() ?: original.mutate()
                    icon.setBounds(0, 0, dp(30), dp(30))
                    setCompoundDrawablesRelative(null, null, icon, null)
                    compoundDrawablePadding = dp(10)
                }
                setOnCheckedChangeListener { _, checked -> change(index, checked) }
            }
        }
        boxes.forEach { body.addView(it, LinearLayout.LayoutParams(-1, dp(43))) }
        show()
        boxes.firstOrNull()?.requestFocus()
    }

    fun input(hint: String, submitLabel: String, submit: (String) -> Unit) {
        val field = EditText(context).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            textSize = 17f
            setTextColor(ink)
            setHintTextColor(muted)
            background = theme.control()
            setPadding(dp(13), 0, dp(13), 0)
            setSelectAllOnFocus(true)
        }
        fun search() {
            val value = field.text.toString().trim().take(100)
            if (value.isBlank()) return
            dialog.dismiss()
            submit(value)
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            search()
            true
        }
        body.addView(field, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(actionRow(Choice(submitLabel), false, ::search), LinearLayout.LayoutParams(-1, dp(43)).apply {
            topMargin = dp(8)
        })
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        show()
        field.requestFocus()
    }

    private fun show() {
        dialog.show()
        val width = (context.resources.configuration.screenWidthDp - 80).coerceIn(1, 425)
        dialog.window?.setLayout(dp(width), -2)
    }

    private fun actionRow(choice: Choice, selected: Boolean, action: () -> Unit) =
        LinearLayout(context).apply {
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER_VERTICAL
            background = theme.control(Color.TRANSPARENT, selected)
            setPadding(dp(12), 0, dp(12), 0)
            choice.icon?.let { icon ->
                addView(android.widget.ImageView(context).apply {
                    setImageDrawable(icon)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
            }
            addView(text(choice.title, 16, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            choice.detail?.let { detail ->
                addView(text(detail, 13, muted).apply {
                    maxWidth = dp(145)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }
            addView(text(if (selected) "✓" else "›", 18, accent).apply {
                gravity = Gravity.END
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(23), -2))
            setOnClickListener { action() }
        }

    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}

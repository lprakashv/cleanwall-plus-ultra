package dev.quirkdeck.launcher

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

internal class HomeSheet(private val context: Context, title: String) {
    data class Choice(val title: String, val detail: String? = null)

    private val dialog = Dialog(context)
    private val body = column()
    private val ink = Color.rgb(233, 241, 249)
    private val muted = Color.rgb(164, 185, 207)
    private val accent = Color.rgb(176, 218, 237)
    private val divider = 0x26BED7F3
    private val focusState = intArrayOf(android.R.attr.state_focused)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF263B51.toInt(), 0xFF142334.toInt())
            ).apply {
                cornerRadius = dp(23).toFloat()
                setStroke(dp(1), 0x557C99B2)
            }
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
            setTextColor(focusColors(ink))
            isFocusableInTouchMode = true
            background = focusBackground(radius = 20)
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

    fun input(hint: String, submitLabel: String, submit: (String) -> Unit) {
        val field = EditText(context).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            textSize = 17f
            setTextColor(ink)
            setHintTextColor(muted)
            background = StateListDrawable().apply {
                addState(focusState, rounded(0xFF182B40.toInt(), accent, 11))
                addState(intArrayOf(), rounded(0xFF182B40.toInt(), divider, 11))
            }
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
            background = focusBackground(radius = 12, selected = selected)
            setPadding(dp(12), 0, dp(12), 0)
            addView(text(choice.title, 16, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isDuplicateParentStateEnabled = true
                setTextColor(focusColors(ink))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            choice.detail?.let { detail ->
                addView(text(detail, 13, muted).apply {
                    maxWidth = dp(145)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    isDuplicateParentStateEnabled = true
                    setTextColor(focusColors(muted))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }
            addView(text(if (selected) "✓" else "›", 18, accent).apply {
                gravity = Gravity.END
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isDuplicateParentStateEnabled = true
                setTextColor(focusColors(accent))
            }, LinearLayout.LayoutParams(dp(23), -2))
            setOnClickListener { action() }
        }

    private fun focusColors(resting: Int) = ColorStateList(
        arrayOf(focusState, intArrayOf()), intArrayOf(0xFF122335.toInt(), resting)
    )

    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun focusBackground(radius: Int, selected: Boolean = false) = StateListDrawable().apply {
        addState(focusState, rounded(accent, accent, radius))
        addState(intArrayOf(), rounded(if (selected) 0x5341617C else 0x22426383, if (selected) divider else 0, radius))
    }

    private fun rounded(color: Int, border: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (border != 0) setStroke(dp(1), border)
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}

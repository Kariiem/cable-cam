package com.cablecam.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.cablecam.R

/** Small helpers for the plain-View UI: rounded surfaces, buttons, segmented controls. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.col(id: Int): Int = getColor(id)

fun Context.rounded(fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(dp(1), stroke)
}

/** A background with a ripple on top. */
fun Context.pressable(fill: Int, radiusDp: Int, stroke: Int = 0): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(col(R.color.text_dim) and 0x40FFFFFF or 0x30000000), rounded(fill, radiusDp, stroke), null)

fun Context.label(s: String, sizeSp: Float = 14f, color: Int = col(R.color.text), bold: Boolean = false, mono: Boolean = false) =
    TextView(this).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(col(R.color.card), 20, col(R.color.card_stroke))
    setPadding(dp(16), dp(14), dp(16), dp(16))
}

fun Context.button(text: String, fill: Int, textColor: Int, heightDp: Int = 48, onClick: () -> Unit) = TextView(this).apply {
    this.text = text; textSize = 15f; setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER; minHeight = dp(heightDp)
    setPadding(dp(16), 0, dp(16), 0)
    background = pressable(fill, 14)
    isClickable = true; isFocusable = true
    setOnClickListener { onClick() }
}

fun Context.filledButton(text: String, onClick: () -> Unit) = button(text, col(R.color.accent), col(R.color.on_accent), 52, onClick)
fun Context.tonalButton(text: String, onClick: () -> Unit) = button(text, col(R.color.accent_container), col(R.color.accent), 44, onClick)
fun Context.textButton(text: String, onClick: () -> Unit) = button(text, 0, col(R.color.accent), 40, onClick)

/** A pill-shaped row of options; one is selected. */
class Segmented(context: Context, items: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
    private val views = items.mapIndexed { i, t ->
        TextView(context).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER; isSingleLine = true
            setPadding(context.dp(14), 0, context.dp(14), 0); minHeight = context.dp(38)
            isClickable = true; isFocusable = true
            setOnClickListener { if (selected != i) onSelect(i) }
        }
    }
    var selected = -1
        set(v) { field = v; style() }

    init {
        background = context.rounded(context.col(R.color.field), 12)
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        views.forEach { addView(it, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        style()
    }

    private fun style() {
        views.forEachIndexed { i, v ->
            val on = i == selected
            v.background = if (on) context.rounded(context.col(R.color.accent_container), 10) else null
            v.setTextColor(context.col(if (on) R.color.accent else R.color.text_dim))
            v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}

/** Status dot + text. */
fun dotBackground(context: Context, color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(context.dp(l), context.dp(t), context.dp(r), context.dp(b))
}

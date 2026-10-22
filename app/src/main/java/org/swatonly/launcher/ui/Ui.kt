package org.swatonly.launcher.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.swatonly.launcher.App
import org.swatonly.launcher.theme.SwayTheme

/** View helpers that take their colours from the active manjaro-sway theme. */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    val theme: SwayTheme get() = App.instance.themes.current()

    val mono: Typeface get() = Typeface.MONOSPACE
    val monoBold: Typeface get() = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    /** Ripple on a flat background, like a hovered waybar module. */
    fun pressable(ctx: Context, base: Int = 0x00000000, radius: Float = dpf(ctx, 6f)): RippleDrawable {
        val mask = rounded(0xFFFFFFFF.toInt(), radius)
        val content = if (base == 0) null else rounded(base, radius)
        return RippleDrawable(ColorStateList.valueOf((theme.accent and 0x00FFFFFF) or 0x55000000), content, mask)
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 14f, color: Int = theme.text, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = if (bold) monoBold else mono
        }

    fun heading(ctx: Context, s: String): TextView = text(ctx, s, 13f, theme.accent, bold = true).apply {
        setPadding(dp(ctx, 16), dp(ctx, 20), dp(ctx, 16), dp(ctx, 6))
        isAllCaps = true
        letterSpacing = 0.08f
    }

    fun vertical(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun matchWrap() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        text(ctx, label, 14f, if (primary) theme.background else theme.text, bold = true).apply {
            gravity = Gravity.CENTER
            val p = dp(ctx, 14)
            setPadding(p, dp(ctx, 10), p, dp(ctx, 10))
            background = pressable(ctx, if (primary) theme.accent else theme.surface)
            setOnClickListener { onClick() }
        }

    /** A dialog styled like a rofi window: theme background, accent border. */
    fun dialog(ctx: Context, title: String?, content: View, scroll: Boolean = true): AlertDialog {
        val wrap = vertical(ctx)
        wrap.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        if (title != null) {
            wrap.addView(text(ctx, title, 16f, theme.accent, bold = true).apply {
                setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 8))
            })
        }
        if (scroll) {
            val sv = ScrollView(ctx)
            sv.addView(content)
            wrap.addView(sv, matchWrap())
        } else wrap.addView(content)
        val d = AlertDialog.Builder(ctx).setView(wrap).create()
        d.window?.setBackgroundDrawable(rounded(theme.background, dpf(ctx, 10f), dp(ctx, 2), theme.accent))
        return d
    }

    /** One selectable row in a dialog or settings list. */
    fun row(ctx: Context, title: String, subtitle: String? = null, value: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = horizontal(ctx)
        row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        val texts = vertical(ctx)
        texts.addView(text(ctx, title, 15f))
        if (!subtitle.isNullOrEmpty()) texts.addView(text(ctx, subtitle, 12f, theme.subtext).apply { setPadding(0, dp(ctx, 2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            row.addView(text(ctx, value, 13f, theme.accent).apply {
                setPadding(dp(ctx, 12), 0, 0, 0)
                maxWidth = dp(ctx, 160)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "value"
            })
        }
        if (onClick != null) {
            row.background = pressable(ctx, radius = 0f)
            row.setOnClickListener { onClick() }
        }
        return row
    }

    fun choose(ctx: Context, title: String, options: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit) {
        val list = vertical(ctx)
        var dlg: AlertDialog? = null
        for ((value, label) in options) {
            val mark = if (value == current) "●  " else "○  "
            list.addView(row(ctx, mark + label) { onPick(value); dlg?.dismiss() })
        }
        dlg = dialog(ctx, title, list)
        dlg.show()
    }

    fun prompt(ctx: Context, title: String, initial: String, hint: String = "", multiLine: Boolean = false, onOk: (String) -> Unit) {
        val box = vertical(ctx)
        box.setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 12))
        val edit = EditText(ctx).apply {
            setText(initial)
            setHint(hint)
            setTextColor(theme.text)
            setHintTextColor(theme.subtext)
            typeface = mono
            background = rounded(theme.surface, dpf(ctx, 6f))
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
            if (!multiLine) isSingleLine = true
        }
        box.addView(edit, matchWrap())
        val buttons = horizontal(ctx).apply { gravity = Gravity.END; setPadding(0, dp(ctx, 12), 0, 0) }
        var d: AlertDialog? = null
        buttons.addView(button(ctx, "Cancel") { d?.dismiss() })
        buttons.addView(View(ctx), LinearLayout.LayoutParams(dp(ctx, 8), 1))
        buttons.addView(button(ctx, "OK", primary = true) { onOk(edit.text.toString()); d?.dismiss() })
        box.addView(buttons, matchWrap())
        d = dialog(ctx, title, box, scroll = false)
        d.show()
    }

    fun confirm(ctx: Context, title: String, message: String, ok: String = "OK", onOk: () -> Unit) {
        val box = vertical(ctx)
        box.setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 12))
        box.addView(text(ctx, message, 14f))
        val buttons = horizontal(ctx).apply { gravity = Gravity.END; setPadding(0, dp(ctx, 16), 0, 0) }
        var d: AlertDialog? = null
        buttons.addView(button(ctx, "Cancel") { d?.dismiss() })
        buttons.addView(View(ctx), LinearLayout.LayoutParams(dp(ctx, 8), 1))
        buttons.addView(button(ctx, ok, primary = true) { onOk(); d?.dismiss() })
        box.addView(buttons, matchWrap())
        d = dialog(ctx, title, box, scroll = false)
        d.show()
    }

    fun windowBackground(): ColorDrawable = ColorDrawable(theme.background)

    @Suppress("unused")
    fun selectable(normal: Int, selected: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(selected))
        addState(intArrayOf(), ColorDrawable(normal))
    }
}

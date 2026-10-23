package org.swatonly.xmrvault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.math.BigDecimal
import java.math.RoundingMode

const val ORANGE = 0xFFFF6600.toInt()
const val DIM = 0xFFAAAAAA.toInt()
const val WARN = 0xFFFFB74D.toInt()
const val BAD = 0xFFEF5350.toInt()

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(20), dp(20), dp(28))
}

fun LinearLayout.text(s: CharSequence, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false, mono: Boolean = false): TextView {
    val t = TextView(context)
    t.text = s
    t.textSize = size
    t.setTextColor(color)
    t.setTextIsSelectable(false)
    if (bold || mono) t.typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    t.setPadding(0, context.dp(6), 0, context.dp(6))
    addView(t)
    return t
}

fun LinearLayout.title(s: String) = text(s, 24f, ORANGE, bold = true)

fun LinearLayout.button(s: String, onClick: () -> Unit): Button {
    val b = Button(context)
    b.text = s
    b.isAllCaps = false
    b.filterTouchesWhenObscured = true
    b.setOnClickListener { onClick() }
    addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(6)
    })
    return b
}

fun LinearLayout.spacer(h: Int = 12) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(h)))

/**
 * A text field with every keyboard leak we can turn off: no suggestions, no learning, no
 * autofill, no accessibility exposure on 14+.
 */
fun LinearLayout.field(hint: String, kind: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1, value: String = ""): EditText {
    val e = EditText(context)
    e.hint = hint
    e.setText(value)
    e.setHintTextColor(DIM)
    e.setTextColor(Color.WHITE)
    e.inputType = if (kind == InputType.TYPE_CLASS_TEXT) {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
    } else kind
    e.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    e.isLongClickable = true
    if (lines > 1) { e.minLines = lines; e.gravity = Gravity.TOP or Gravity.START }
    e.typeface = Typeface.MONOSPACE
    e.filterTouchesWhenObscured = true
    sensitive(e)
    addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return e
}

fun sensitive(v: View) {
    if (Build.VERSION.SDK_INT >= 34) v.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
}

/** Take a field's text as UTF-8 bytes and clear the field. The caller zeroes the result. */
fun Editable.takeBytes(): ByteArray {
    val chars = CharArray(length)
    getChars(0, length, chars, 0)
    clear()
    val bb = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
    chars.fill('\u0000')
    val out = ByteArray(bb.remaining())
    bb.get(out)
    if (bb.hasArray()) bb.array().fill(0)
    return out
}

/** Atomic units -> "1.5" */
fun xmr(atomic: String): String = try {
    BigDecimal(atomic).movePointLeft(12).stripTrailingZeros().toPlainString()
} catch (_: Exception) { "?" }

/** "1.5" -> atomic units, or null. */
fun parseXmr(s: String): Long? = try {
    val d = BigDecimal(s.trim().replace(',', '.'))
    if (d.signum() <= 0 || d.scale() > 12) null
    else d.movePointRight(12).setScale(0, RoundingMode.UNNECESSARY).longValueExact().takeIf { it > 0 }
} catch (_: Exception) { null }

/** Group an address in fours so it can be checked chunk by chunk. */
fun grouped(address: String) = address.chunked(4).joinToString(" ")

fun qrView(ctx: Context, modules: BooleanArray): ImageView {
    val n = kotlin.math.sqrt(modules.size.toDouble()).toInt()
    val quiet = 4
    val size = n + 2 * quiet
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(Color.WHITE)
    for (y in 0 until n) for (x in 0 until n) {
        if (modules[y * n + x]) bmp.setPixel(x + quiet, y + quiet, Color.BLACK)
    }
    val iv = ImageView(ctx)
    val d = BitmapDrawable(ctx.resources, bmp)
    d.isFilterBitmap = false
    iv.setImageDrawable(d)
    iv.adjustViewBounds = true
    iv.layoutParams = LinearLayout.LayoutParams(ctx.dp(260), ctx.dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    return iv
}

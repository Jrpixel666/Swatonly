package org.swatonly.xmrvault

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.security.SecureRandom

/**
 * A PIN pad that never touches the system keyboard (so no IME can log it), shuffles its digits
 * every time it is shown (smudge and shoulder-surfing resistance), keeps the digits in a byte
 * array that is zeroed after use, and hides itself from accessibility services on Android 14+.
 */
class PinPad(
    ctx: Context,
    private val maxLen: Int = 32,
    private val onSubmit: (ByteArray) -> Unit,
) : LinearLayout(ctx) {
    private val buf = ByteArray(maxLen)
    private var len = 0
    private val dots = TextView(ctx)
    private val grid = GridLayout(ctx)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        dots.textSize = 28f
        dots.gravity = Gravity.CENTER
        dots.setTextColor(Color.WHITE)
        dots.minHeight = dp(48)
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        grid.columnCount = 3
        addView(grid, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        if (Build.VERSION.SDK_INT >= 34) {
            setAccessibilityDataSensitive(ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        filterTouchesWhenObscured = true
        shuffle()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** New random digit layout. */
    fun shuffle() {
        grid.removeAllViews()
        val digits = (0..9).toMutableList()
        val rnd = SecureRandom()
        for (i in digits.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            digits[i] = digits[j].also { digits[j] = digits[i] }
        }
        val keys = digits.take(9).map { it.toString() } + listOf("⌫", digits[9].toString(), "OK")
        for (k in keys) {
            val b = Button(context)
            b.text = k
            b.textSize = 22f
            b.isAllCaps = false
            b.filterTouchesWhenObscured = true
            b.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                press(k)
            }
            val lp = GridLayout.LayoutParams()
            lp.width = dp(88); lp.height = dp(64)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            grid.addView(b, lp)
        }
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> if (len > 0) { len--; buf[len] = 0 }
            "OK" -> if (len > 0) {
                val pin = buf.copyOf(len)
                clear()
                onSubmit(pin)
                return
            }
            else -> if (len < maxLen) { buf[len++] = k[0].code.toByte() }
        }
        dots.text = "•".repeat(len)
    }

    fun clear() {
        buf.fill(0)
        len = 0
        dots.text = ""
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}

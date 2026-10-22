package org.swatonly.launcher.wayland

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.swatonly.launcher.App
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The SurfaceView a Linux window is drawn into, and its input: touch is
 * turned into pointer clicks/drags/scrolls (or forwarded as wl_touch),
 * hardware keys and soft keyboard text become evdev key presses.
 */
@SuppressLint("ViewConstructor")
class WlView(context: Context, private val server: WaylandServer, private val window: Window) : SurfaceView(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val handler = Handler(Looper.getMainLooper())
    private val touchMode = App.instance.prefs.touchMode == "touch"

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var twoFinger = false
    private var longPressed = false
    private var lastScrollX = 0f
    private var lastScrollY = 0f
    private var mouseButtons = 0

    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        server.pointerButton(window, downX, downY, Keys.BTN_RIGHT, true)
        server.pointerButton(window, downX, downY, Keys.BTN_RIGHT, false)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- touch --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(e)
        if (touchMode) return onRawTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y
                dragging = false; twoFinger = false; longPressed = false
                server.pointerMotion(window, e.x, e.y)
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                if (dragging) server.pointerButton(window, e.getX(0), e.getY(0), Keys.BTN_LEFT, false)
                dragging = false
                twoFinger = true
                lastScrollX = avgX(e); lastScrollY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && e.pointerCount >= 2) {
                    val x = avgX(e); val y = avgY(e)
                    val s = server.scale.toDouble()
                    val dy = (lastScrollY - y) / s
                    val dx = (lastScrollX - x) / s
                    if (abs(dy) >= 1) { server.pointerAxis(window, x, y, 0, dy, 0, true); lastScrollY = y }
                    if (abs(dx) >= 1) { server.pointerAxis(window, x, y, 1, dx, 0, true); lastScrollX = x }
                } else if (!twoFinger && !longPressed) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                        handler.removeCallbacks(longPress)
                        dragging = true
                        server.pointerMotion(window, downX, downY)
                        server.pointerButton(window, downX, downY, Keys.BTN_LEFT, true)
                    }
                    server.pointerMotion(window, e.x, e.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                when {
                    twoFinger || longPressed -> {}
                    dragging -> server.pointerButton(window, e.x, e.y, Keys.BTN_LEFT, false)
                    else -> {
                        server.pointerButton(window, downX, downY, Keys.BTN_LEFT, true)
                        server.pointerButton(window, downX, downY, Keys.BTN_LEFT, false)
                    }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) server.pointerButton(window, e.x, e.y, Keys.BTN_LEFT, false)
                dragging = false
            }
        }
        return true
    }

    private fun avgX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun avgY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun onRawTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestFocus()
                val i = e.actionIndex
                server.touchDown(window, e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) server.touchMotion(window, e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> server.touchUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) server.touchUp(e.getPointerId(i))
        }
        return true
    }

    // ---- mouse --------------------------------------------------------------

    private fun onMouse(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        server.pointerMotion(window, e.x, e.y)
        syncButtons(e)
        return true
    }

    private fun syncButtons(e: MotionEvent) {
        val now = e.buttonState
        val changed = now xor mouseButtons
        if (changed == 0) return
        for ((bit, btn) in listOf(
            MotionEvent.BUTTON_PRIMARY to Keys.BTN_LEFT,
            MotionEvent.BUTTON_SECONDARY to Keys.BTN_RIGHT,
            MotionEvent.BUTTON_TERTIARY to Keys.BTN_MIDDLE,
        )) {
            if (changed and bit != 0) server.pointerButton(window, e.x, e.y, btn, now and bit != 0)
        }
        mouseButtons = now
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> server.pointerMotion(window, e.x, e.y)
            MotionEvent.ACTION_HOVER_EXIT -> server.pointerLeave(window)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> syncButtons(e)
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                // wheel up (v > 0) scrolls up: negative axis values
                if (v != 0f) server.pointerAxis(window, e.x, e.y, 0, -v * 15.0, if (v > 0) -1 else 1, false)
                if (h != 0f) server.pointerAxis(window, e.x, e.y, 1, h * 15.0, if (h > 0) 1 else -1, false)
            }
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    // ---- keyboard -------------------------------------------------------------

    private val sticky = LinkedHashSet<Int>()
    var onStickyCleared: (() -> Unit)? = null

    fun toggleSticky(code: Int): Boolean {
        return if (code in sticky) { sticky -= code; server.key(code, false); false }
        else { sticky += code; server.key(code, true); true }
    }

    private fun releaseSticky() {
        if (sticky.isEmpty()) return
        for (c in sticky) server.key(c, false)
        sticky.clear()
        onStickyCleared?.invoke()
    }

    fun tapKey(code: Int, shift: Boolean = false) {
        if (shift) server.key(Keys.KEY_LEFTSHIFT, true)
        server.key(code, true)
        server.key(code, false)
        if (shift) server.key(Keys.KEY_LEFTSHIFT, false)
        releaseSticky()
    }

    private fun typeText(text: CharSequence) {
        for (ch in text) {
            val k = Keys.forChar(ch) ?: continue
            tapKey(k.first, k.second)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(keyCode, event, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(keyCode, event, false) || super.onKeyUp(keyCode, event)

    private fun handleKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER -> return false
        }
        val code = Keys.evdev(keyCode)
        if (code != null) {
            // soft keyboards send shifted symbols with a meta state but no shift key
            val needShift = down && event.isShiftPressed && Keys.modifierMask(code) == 0 &&
                event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
            if (needShift) server.key(Keys.KEY_LEFTSHIFT, true)
            server.key(code, down)
            if (needShift) server.key(Keys.KEY_LEFTSHIFT, false)
            if (!down && Keys.modifierMask(code) == 0) releaseSticky()
            return true
        }
        if (down) {
            val ch = event.unicodeChar
            if (ch != 0) { typeText(String(Character.toChars(ch))); return true }
        }
        return false
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            /** Types the difference between what is on screen and [target]. */
            private fun replaceComposing(target: String) {
                var common = 0
                while (common < composing.length && common < target.length && composing[common] == target[common]) common++
                repeat(composing.length - common) { tapKey(Keys.KEY_BACKSPACE) }
                typeText(target.substring(common))
                composing = target
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean { composing = ""; return true }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(64)) { tapKey(Keys.KEY_BACKSPACE) }
                repeat(afterLength.coerceAtMost(64)) { tapKey(Keys.KEY_DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val down = event.action == KeyEvent.ACTION_DOWN
                if (!handleKey(event.keyCode, event, down) && down) {
                    if (event.keyCode == KeyEvent.KEYCODE_ENTER) tapKey(Keys.KEY_ENTER)
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                tapKey(Keys.KEY_ENTER)
                return true
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}

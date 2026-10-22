package org.swatonly.launcher.wayland

import android.view.KeyEvent

/**
 * Android key codes -> Linux evdev codes (what wl_keyboard.key carries; the
 * keymap adds 8), and characters -> (evdev code, needs shift) on the US
 * layout of assets/keymap/us.xkb, for text that comes from soft keyboards.
 */
object Keys {
    const val KEY_ESC = 1
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_ENTER = 28
    const val KEY_LEFTCTRL = 29
    const val KEY_LEFTSHIFT = 42
    const val KEY_RIGHTSHIFT = 54
    const val KEY_LEFTALT = 56
    const val KEY_SPACE = 57
    const val KEY_CAPSLOCK = 58
    const val KEY_RIGHTCTRL = 97
    const val KEY_RIGHTALT = 100
    const val KEY_HOME = 102
    const val KEY_UP = 103
    const val KEY_PAGEUP = 104
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_END = 107
    const val KEY_DOWN = 108
    const val KEY_PAGEDOWN = 109
    const val KEY_DELETE = 111
    const val KEY_LEFTMETA = 125
    const val KEY_RIGHTMETA = 126

    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112

    // modifier masks of the keymap (xkb real modifier indices)
    const val MOD_SHIFT = 1
    const val MOD_LOCK = 2
    const val MOD_CTRL = 4
    const val MOD_ALT = 8
    const val MOD_SUPER = 64

    fun modifierMask(code: Int): Int = when (code) {
        KEY_LEFTSHIFT, KEY_RIGHTSHIFT -> MOD_SHIFT
        KEY_LEFTCTRL, KEY_RIGHTCTRL -> MOD_CTRL
        KEY_LEFTALT, KEY_RIGHTALT -> MOD_ALT
        KEY_LEFTMETA, KEY_RIGHTMETA -> MOD_SUPER
        else -> 0
    }

    private val androidToEvdev: Map<Int, Int> = HashMap<Int, Int>().apply {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val letterCodes = intArrayOf(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44)
        for (i in letters.indices) put(KeyEvent.KEYCODE_A + i, letterCodes[i])
        put(KeyEvent.KEYCODE_0, 11)
        for (i in 1..9) put(KeyEvent.KEYCODE_0 + i, 1 + i)
        put(KeyEvent.KEYCODE_ESCAPE, KEY_ESC)
        put(KeyEvent.KEYCODE_MINUS, 12)
        put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, KEY_BACKSPACE)
        put(KeyEvent.KEYCODE_TAB, KEY_TAB)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, KEY_ENTER)
        put(KeyEvent.KEYCODE_CTRL_LEFT, KEY_LEFTCTRL)
        put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, KEY_LEFTSHIFT)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)
        put(KeyEvent.KEYCODE_COMMA, 51)
        put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, KEY_RIGHTSHIFT)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(KeyEvent.KEYCODE_ALT_LEFT, KEY_LEFTALT)
        put(KeyEvent.KEYCODE_SPACE, KEY_SPACE)
        put(KeyEvent.KEYCODE_CAPS_LOCK, KEY_CAPSLOCK)
        for (i in 0 until 10) put(KeyEvent.KEYCODE_F1 + i, 59 + i)
        put(KeyEvent.KEYCODE_NUM_LOCK, 69)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 70)
        put(KeyEvent.KEYCODE_NUMPAD_7, 71); put(KeyEvent.KEYCODE_NUMPAD_8, 72); put(KeyEvent.KEYCODE_NUMPAD_9, 73)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74)
        put(KeyEvent.KEYCODE_NUMPAD_4, 75); put(KeyEvent.KEYCODE_NUMPAD_5, 76); put(KeyEvent.KEYCODE_NUMPAD_6, 77)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(KeyEvent.KEYCODE_NUMPAD_1, 79); put(KeyEvent.KEYCODE_NUMPAD_2, 80); put(KeyEvent.KEYCODE_NUMPAD_3, 81)
        put(KeyEvent.KEYCODE_NUMPAD_0, 82)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 83)
        put(KeyEvent.KEYCODE_F11, 87)
        put(KeyEvent.KEYCODE_F12, 88)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, KEY_RIGHTCTRL)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98)
        put(KeyEvent.KEYCODE_SYSRQ, 99)
        put(KeyEvent.KEYCODE_ALT_RIGHT, KEY_RIGHTALT)
        put(KeyEvent.KEYCODE_MOVE_HOME, KEY_HOME)
        put(KeyEvent.KEYCODE_DPAD_UP, KEY_UP)
        put(KeyEvent.KEYCODE_PAGE_UP, KEY_PAGEUP)
        put(KeyEvent.KEYCODE_DPAD_LEFT, KEY_LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, KEY_RIGHT)
        put(KeyEvent.KEYCODE_MOVE_END, KEY_END)
        put(KeyEvent.KEYCODE_DPAD_DOWN, KEY_DOWN)
        put(KeyEvent.KEYCODE_PAGE_DOWN, KEY_PAGEDOWN)
        put(KeyEvent.KEYCODE_INSERT, 110)
        put(KeyEvent.KEYCODE_FORWARD_DEL, KEY_DELETE)
        put(KeyEvent.KEYCODE_BREAK, 119)
        put(KeyEvent.KEYCODE_META_LEFT, KEY_LEFTMETA)
        put(KeyEvent.KEYCODE_META_RIGHT, KEY_RIGHTMETA)
        put(KeyEvent.KEYCODE_MENU, 127)
    }

    fun evdev(androidKeyCode: Int): Int? = androidToEvdev[androidKeyCode]

    /** (evdev code, shift) for a character on the US layout, or null. */
    fun forChar(c: Char): Pair<Int, Boolean>? {
        if (c in 'a'..'z') return androidToEvdev[KeyEvent.KEYCODE_A + (c - 'a')]!! to false
        if (c in 'A'..'Z') return androidToEvdev[KeyEvent.KEYCODE_A + (c - 'A')]!! to true
        if (c in '1'..'9') return (1 + (c - '0')) to false
        return when (c) {
            '0' -> 11 to false
            ' ' -> KEY_SPACE to false
            '\n' -> KEY_ENTER to false
            '\t' -> KEY_TAB to false
            '!' -> 2 to true; '@' -> 3 to true; '#' -> 4 to true; '$' -> 5 to true; '%' -> 6 to true
            '^' -> 7 to true; '&' -> 8 to true; '*' -> 9 to true; '(' -> 10 to true; ')' -> 11 to true
            '-' -> 12 to false; '_' -> 12 to true; '=' -> 13 to false; '+' -> 13 to true
            '[' -> 26 to false; '{' -> 26 to true; ']' -> 27 to false; '}' -> 27 to true
            ';' -> 39 to false; ':' -> 39 to true; '\'' -> 40 to false; '"' -> 40 to true
            '`' -> 41 to false; '~' -> 41 to true; '\\' -> 43 to false; '|' -> 43 to true
            ',' -> 51 to false; '<' -> 51 to true; '.' -> 52 to false; '>' -> 52 to true
            '/' -> 53 to false; '?' -> 53 to true
            else -> null
        }
    }
}

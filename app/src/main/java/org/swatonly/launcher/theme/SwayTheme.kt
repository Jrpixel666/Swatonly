package org.swatonly.launcher.theme

import android.graphics.Color

/**
 * A manjaro-sway theme (/usr/share/sway/themes/<name>/theme.conf), parsed the
 * way sway reads it: `set $var value` lines, with `$var` references expanded.
 */
class SwayTheme(
    val name: String,
    val vars: Map<String, String>,
    val footColors: Map<String, String>,
    val packages: List<String>,
    /** "rootfs" when read from the Manjaro install, "bundled" when from the APK. */
    val source: String,
) {
    private fun color(vararg keys: String, fallback: Int): Int {
        for (k in keys) {
            val v = vars[k] ?: continue
            parseColor(v)?.let { return it }
        }
        return fallback
    }

    val background: Int get() = color("background-color", "base", "color0", fallback = 0xFF141A1B.toInt())
    val text: Int get() = color("text-color", "text", "color6", fallback = 0xFFEEEEEE.toInt())
    val accent: Int get() = color("accent-color", "color12", fallback = 0xFF16A085.toInt())
    val error: Int get() = color("color8", "red", fallback = 0xFFCD3F45.toInt())
    val warning: Int get() = color("color9", "peach", fallback = 0xFFDB7B55.toInt())
    val surface: Int get() = color("surface0", "mantle", "color1", fallback = blend(background, text, 0.08f))
    val subtext: Int get() = color("subtext0", "color5", fallback = blend(background, text, 0.65f))
    val isDark: Boolean get() = vars["gtk-color-scheme"]?.contains("light") != true && luminance(background) < 0.5f

    val guiFont: String get() = vars["gui-font"] ?: "Roboto 11"
    val termFont: String get() = vars["term-font"] ?: "JetBrainsMono NF"
    val gtkTheme: String get() = vars["gtk-theme"] ?: ""
    val iconTheme: String get() = vars["icon-theme"] ?: "Papirus-Dark"

    val displayName: String
        get() = name.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** The 16 ANSI colours + fg/bg for the built-in terminal, from foot-theme.ini. */
    fun terminalColors(): Map<String, Int> {
        val out = HashMap<String, Int>()
        footColors["foreground"]?.let { parseColor("#$it") }?.let { out["foreground"] = it }
        footColors["background"]?.let { parseColor("#$it") }?.let { out["background"] = it }
        for (i in 0..7) {
            footColors["regular$i"]?.let { parseColor("#$it") }?.let { out["color$i"] = it }
            footColors["bright$i"]?.let { parseColor("#$it") }?.let { out["color${i + 8}"] = it }
        }
        if ("foreground" !in out) out["foreground"] = text
        if ("background" !in out) out["background"] = background
        return out
    }

    companion object {
        fun parse(name: String, themeConf: String, footIni: String?, packages: String?, source: String): SwayTheme {
            val raw = LinkedHashMap<String, String>()
            for (line in themeConf.lineSequence()) {
                val t = line.trim()
                if (!t.startsWith("set ")) continue
                val rest = t.removePrefix("set ").trim()
                if (!rest.startsWith("$")) continue
                val sp = rest.indexOfFirst { it == ' ' || it == '\t' }
                if (sp < 0) continue
                val key = rest.substring(1, sp)
                var value = rest.substring(sp + 1).trim()
                val hash = value.indexOf(" #")
                if (hash > 0 && !value.startsWith("\"")) value = value.substring(0, hash).trim()
                raw[key] = value.trim('"')
            }
            val resolved = raw.mapValues { (_, v) -> expand(v, raw, 0) }
            return SwayTheme(name, resolved, parseFoot(footIni), packages?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: emptyList(), source)
        }

        private fun expand(value: String, vars: Map<String, String>, depth: Int): String {
            if (depth > 8 || !value.contains('$')) return value
            val sb = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '$') {
                    var j = i + 1
                    while (j < value.length && (value[j].isLetterOrDigit() || value[j] == '-' || value[j] == '_')) j++
                    val key = value.substring(i + 1, j)
                    val v = vars[key]
                    if (v != null) sb.append(expand(v, vars, depth + 1)) else sb.append(value, i, j)
                    i = j
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }

        private fun parseFoot(ini: String?): Map<String, String> {
            if (ini == null) return emptyMap()
            val out = HashMap<String, String>()
            var inColors = false
            for (line in ini.lineSequence()) {
                val t = line.substringBefore('#').trim()
                if (t.startsWith("[")) {
                    // a merged file has both; the dark (first) block wins
                    inColors = (t == "[colors]" || t == "[colors-dark]" || t == "[colors-light]") && out.isEmpty()
                    continue
                }
                if (!inColors) continue
                val eq = t.indexOf('=')
                if (eq > 0) out[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
            }
            return out
        }

        fun parseColor(v: String): Int? {
            val s = v.trim().trim('"')
            if (!s.startsWith("#")) return null
            return try {
                when (s.length) {
                    7 -> Color.parseColor(s)
                    9 -> { // sway uses #RRGGBBAA
                        val rgb = s.substring(1, 7); val a = s.substring(7, 9)
                        Color.parseColor("#$a$rgb")
                    }
                    else -> null
                }
            } catch (_: IllegalArgumentException) { null }
        }

        fun blend(a: Int, b: Int, t: Float): Int {
            val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
            val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
            val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
            return Color.rgb(r, g, bl)
        }

        fun luminance(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

        fun hex(c: Int): String = String.format("#%06x", c and 0xFFFFFF)
    }
}

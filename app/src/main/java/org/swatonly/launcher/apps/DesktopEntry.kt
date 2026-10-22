package org.swatonly.launcher.apps

import java.util.Locale

/** Parses the [Desktop Entry] group of a freedesktop .desktop file. */
object DesktopEntry {

    fun parse(id: String, text: String): AppEntry.Linux? {
        val v = HashMap<String, String>()
        var inMain = false
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[")) { inMain = t == "[Desktop Entry]"; continue }
            if (!inMain || t.startsWith("#")) continue
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            v[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
        }
        if (v["Type"] != "Application") return null
        if (v["NoDisplay"].equals("true", true) || v["Hidden"].equals("true", true)) return null
        v["OnlyShowIn"]?.let { only -> if (only.split(';').none { it.equals("sway", true) || it.equals("wlroots", true) }) return null }
        v["NotShowIn"]?.let { not -> if (not.split(';').any { it.equals("sway", true) }) return null }
        val exec = v["Exec"] ?: return null
        val name = localized(v, "Name") ?: return null
        return AppEntry.Linux(
            id = id,
            label = name,
            exec = exec,
            icon = v["Icon"],
            terminal = v["Terminal"].equals("true", true),
            categories = v["Categories"]?.split(';')?.filter { it.isNotEmpty() } ?: emptyList(),
            description = localized(v, "Comment") ?: localized(v, "GenericName") ?: "",
            startupWmClass = v["StartupWMClass"],
            keywords = localized(v, "Keywords")?.split(';')?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }

    private fun localized(v: Map<String, String>, key: String): String? {
        val loc = Locale.getDefault()
        return v["$key[${loc.language}_${loc.country}]"] ?: v["$key[${loc.language}]"] ?: v[key]
    }

    /**
     * Turns Exec= into a shell command: field codes dropped, quoting kept
     * (the desktop entry spec's quoting is a subset of the shell's).
     */
    fun shellCommand(exec: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            if (c == '%' && i + 1 < exec.length) {
                when (exec[i + 1]) {
                    '%' -> sb.append('%')
                    else -> {} // %f %F %u %U %i %c %k ...: no file to pass
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString().trim()
    }
}

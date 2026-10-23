package org.swatonly.launcher.linux

import android.content.Context
import org.swatonly.launcher.App

/**
 * Sway keybindings, read like manjaro-sway's help.sh reads them: every
 * `bindsym` in /etc/sway/modes and the user's config, described by the
 * `## Category // Description ##` comment above it.
 */
object Keybindings {

    data class Binding(val keys: String, val command: String, val mode: String, val file: String)

    private val BIND = Regex("""^\s*\$?(bindsym|bindcode)\s+((?:--\S+\s+)*)(\S+)\s+(.+)$""")
    private val MODE = Regex("""^\s*mode\s+(?:--\S+\s+)*"?([^"{]+?)"?\s*\{\s*$""")
    private val HELP = Regex("""^\s*##\s*(.+?)\s*##\s*$""")

    fun load(ctx: Context): List<Binding> {
        val rootFs = App.instance.rootFs
        val vars = HashMap<String, String>()
        for (v in Tweaks.definitionVars(ctx)) vars[v.name] = v.value
        val sources = ArrayList<Pair<String, String>>()
        val modesDir = rootFs.resolve("/etc/sway/modes")
        if (modesDir != null && modesDir.isDirectory) {
            modesDir.listFiles()?.sortedBy { it.name }?.forEach { sources += "/etc/sway/modes/${it.name}" to it.readText() }
        } else {
            ctx.assets.list("manjaro-sway/sway/modes")?.sorted()?.forEach { n ->
                try { sources += "/etc/sway/modes/$n" to ctx.assets.open("manjaro-sway/sway/modes/$n").bufferedReader().use { it.readText() } } catch (_: Exception) {}
            }
        }
        for (p in listOf("/root/.config/sway/config")) rootFs.resolve(p)?.takeIf { it.isFile }?.let { sources += p to it.readText() }
        rootFs.resolve("/root/.config/sway/config.d")?.listFiles()?.filter { it.name.endsWith(".conf") }?.sortedBy { it.name }?.forEach {
            sources += "/root/.config/sway/config.d/${it.name}" to it.readText()
        }

        val out = ArrayList<Binding>()
        for ((file, text) in sources) {
            var mode = ""
            var help: String? = null
            for (line in text.lines()) {
                HELP.find(line)?.let { help = it.groupValues[1].replace(" // ", " · "); return@let }
                // manjaro-sway names modes with pango-markup variables ($mode_resize): show "resize"
                MODE.find(line)?.let { mode = it.groupValues[1].trim().removePrefix("\$mode_").removePrefix("\$"); return@let }
                if (line.trim() == "}") { mode = ""; continue }
                val m = BIND.find(line) ?: continue
                val keys = prettyKeys(expand(m.groupValues[3], vars))
                val cmd = m.groupValues[4].trim()
                out += Binding(keys, help ?: expand(cmd, vars), mode, file)
                help = null
            }
        }
        return out.sortedBy { it.mode }
    }

    private fun expand(s: String, vars: Map<String, String>): String =
        Regex("""\$([A-Za-z0-9_-]+)""").replace(s) { vars[it.groupValues[1]]?.trim('"') ?: it.value }

    private fun prettyKeys(k: String): String = k.split('+').joinToString(" + ") {
        when (it) {
            "Mod4" -> "Super"
            "Mod1" -> "Alt"
            "Return" -> "Enter"
            else -> it
        }
    }
}

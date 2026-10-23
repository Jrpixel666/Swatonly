package org.swatonly.launcher.linux

import android.content.Context
import org.swatonly.launcher.App

/**
 * Sway keybindings, read like manjaro-sway's help.sh reads them: every
 * `bindsym` in /etc/sway/modes and the user's config, described by the
 * `## Category // Description ##` comment above it.
 */
object Keybindings {

    data class Binding(
        val keys: String,
        /** The help description, or the expanded command when there is none. */
        val command: String,
        val mode: String,
        val file: String,
        /** The sway command as written (variables unexpanded: sway expands them itself). */
        val raw: String = "",
        /** "Launch", "Action", "Navigation"…: the help comment's first part. */
        val category: String? = null,
        /** The help comment's description, shared by the bindings under one comment. */
        val help: String? = null,
        /** The comment line right above the binding inside a mode block ("# lock"). */
        val note: String? = null,
        /** The `$mode_*` variable a mode block was declared with. */
        val modeVar: String = "",
    )

    private val BIND = Regex("""^\s*\$?(bindsym|bindcode)\s+((?:--\S+\s+)*)(\S+)\s+(.+)$""")
    private val MODE = Regex("""^\s*mode\s+(?:--\S+\s+)*"?([^"{]+?)"?\s*\{\s*$""")
    private val COMMENT = Regex("""^\s*#\s*([^#].*?)\s*$""")
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

        return parse(sources, vars)
    }

    /** Parses [sources] (path to text) like help.sh; [vars] expands the keys. */
    fun parse(sources: List<Pair<String, String>>, vars: Map<String, String>): List<Binding> {
        val out = ArrayList<Binding>()
        for ((file, text) in sources) {
            var mode = ""
            var modeVar = ""
            var help: String? = null
            // the help comment of a group of bindings ("Move focus" over focus left/down/up/right)
            var group: List<String>? = null
            var note: String? = null
            for (line in text.lines()) {
                if (line.isBlank()) { group = null; note = null; continue }
                HELP.find(line)?.let { m ->
                    help = m.groupValues[1].replace(" // ", " · ")
                    group = m.groupValues[1].split("//").map { it.trim() }
                    return@let
                }
                if (HELP.containsMatchIn(line)) continue
                // manjaro-sway names modes with pango-markup variables ($mode_resize): show "resize"
                MODE.find(line)?.let {
                    modeVar = it.groupValues[1].trim()
                    mode = modeVar.removePrefix("\$mode_").removePrefix("\$")
                    group = null; note = null
                    return@let
                }
                if (line.trim() == "}") { mode = ""; modeVar = ""; group = null; continue }
                COMMENT.find(line)?.let { note = it.groupValues[1]; return@let }
                if (line.trimStart().startsWith("#")) continue
                val m = BIND.find(line) ?: continue
                val keys = prettyKeys(expand(m.groupValues[3], vars))
                val cmd = m.groupValues[4].trim()
                val g = group
                out += Binding(keys, help ?: expand(cmd, vars), mode, file, raw = cmd,
                    category = g?.getOrNull(0), help = g?.getOrNull(1), note = note, modeVar = modeVar)
                help = null
            }
        }
        return out.sortedBy { it.mode }
    }

    fun expand(s: String, vars: Map<String, String>): String =
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

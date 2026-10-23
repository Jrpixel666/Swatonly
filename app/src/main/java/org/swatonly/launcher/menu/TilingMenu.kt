package org.swatonly.launcher.menu

import android.content.Context
import org.swatonly.launcher.linux.Keybindings
import org.swatonly.launcher.linux.Tweaks

/**
 * The tiling window manager's own menu, built from the install's sway
 * configuration the way manjaro-sway's help.sh reads it: every
 * `## Category // Description ##` binding becomes an entry under its category
 * (Launch, Action, Navigation, Setting…), bindings sharing one comment become
 * a submenu, and a binding that enters a sway mode (`$mode_shutdown`,
 * `$mode_screenshot`…) opens that mode's keys as a submenu instead of
 * waiting for a key press. Entries send their sway command through
 * /opt/swatonly/wm-msg, so sway expands $term, $menu and the rest itself.
 */
object TilingMenu {

    const val ROOT = "wm"

    private val ICONS = mapOf(
        "launch" to "", "action" to "", "navigation" to "󰆾", "setting" to "",
        "utility" to "", "resize" to "󰩨", "modes" to "󰘳",
    )

    fun addTo(ctx: Context, into: LinkedHashMap<String, MenuModel.Item>, prefix: String) {
        val binds = try { Keybindings.load(ctx) } catch (_: Exception) { return }
        val vars = HashMap<String, String>()
        try { for (v in Tweaks.definitionVars(ctx)) vars[v.name] = v.value } catch (_: Exception) {}
        build(binds, into, prefix) { Keybindings.expand(it, vars) }
    }

    /** Adds the menu for [binds] under [prefix]; [expand] fills in sway variables for labels. */
    fun build(binds: List<Keybindings.Binding>, into: LinkedHashMap<String, MenuModel.Item>, prefix: String, expand: (String) -> String) {
        if (binds.isEmpty()) return
        val root = prefix + ROOT
        into[root] = submenu(root, "Tiling (sway)", "󱂬", "the window manager's keybindings and modes")

        val modes = binds.filter { it.mode.isNotEmpty() }.groupBy { it.modeVar }
        val usedModes = HashSet<String>()
        val defaults = binds.filter { it.mode.isEmpty() && it.category != null }

        // consecutive bindings under one help comment form a group
        val groups = ArrayList<List<Keybindings.Binding>>()
        for (b in defaults) {
            val last = groups.lastOrNull()?.last()
            if (last != null && last.category == b.category && last.help == b.help && last.file == b.file) {
                groups[groups.size - 1] = groups.last() + b
            } else groups += listOf(b)
        }

        for (group in groups) {
            val first = group.first()
            val catLabel = first.category!!.trim()
            val cat = "$root.${slug(catLabel)}"
            if (cat !in into) into[cat] = submenu(cat, catLabel, ICONS[slug(catLabel)] ?: "󰌌", null)
            val label = first.help?.ifEmpty { null } ?: humanize(expand, first.raw)
            val id = unique(into, "$cat.${slug(label)}")

            // a binding that enters a mode: the mode's own keys, as a submenu
            val modeVar = Regex("""^mode\s+(?:--\S+\s+)*("?)(\$\w+)\1\s*$""").find(first.raw)?.groupValues?.get(2)
            val mode = modeVar?.let { modes[it] }
            if (mode != null) {
                usedModes += modeVar
                into[id] = submenu(id, label, "󰘳", first.keys)
                addMode(expand, into, id, mode)
                continue
            }

            val distinct = group.distinctBy { it.raw }
            if (distinct.size == 1) {
                into[id] = action(id, label, first.keys, first.raw)
            } else {
                into[id] = submenu(id, label, ICONS[slug(catLabel)] ?: "󰌌", distinct.first().keys + " …")
                for (b in distinct) {
                    val cid = unique(into, "$id.${slug(b.raw)}")
                    into[cid] = action(cid, humanize(expand, b.raw), b.keys, b.raw)
                }
            }
        }

        // modes nothing in the default mode enters
        val rest = modes.filterKeys { it !in usedModes && it.isNotEmpty() }
        if (rest.isNotEmpty()) {
            val m = "$root.modes"
            into[m] = submenu(m, "Modes", "󰘳", null)
            for ((v, mode) in rest) {
                val id = unique(into, "$m.${slug(mode.first().mode)}")
                into[id] = submenu(id, mode.first().mode.replaceFirstChar(Char::uppercase), "󰘳", v)
                addMode(expand, into, id, mode)
            }
        }
        val reload = "$root.reload"
        into[reload] = action(reload, "Reload sway configuration", null, "reload")
    }

    private fun addMode(expand: (String) -> String, into: LinkedHashMap<String, MenuModel.Item>, parent: String, mode: List<Keybindings.Binding>) {
        val entries = mode.distinctBy { stripModeSwitch(it.raw) }
        for (b in entries) {
            val cmd = stripModeSwitch(b.raw)
            if (cmd.isEmpty()) continue // "Return to default mode"
            val note = b.note?.trimEnd('.')?.takeIf { it.split(' ').size <= 4 }
            val help = b.help?.ifEmpty { null }
            val label = when {
                help != null && entries.count { it.help == b.help } > 1 -> "$help: ${humanize(expand, cmd)}"
                help != null -> help
                note != null && entries.count { it.note == b.note } > 1 -> "$note (${b.keys})"
                else -> note ?: humanize(expand, cmd)
            }
            val id = unique(into, "$parent.${slug(label)}")
            // leave the mode afterwards, like the binding does
            into[id] = action(id, label, b.keys, "$cmd, mode default")
        }
    }

    /** `mode "default", exec $locking` -> `exec $locking` */
    private fun stripModeSwitch(raw: String): String =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && !Regex("""^mode\s+"?default"?$""").matches(it) }.joinToString(", ")

    /** A short label for a sway command: `exec $term_cwd` -> "Term cwd", `workspace $ws3` -> "workspace 3". */
    private fun humanize(expand: (String) -> String, raw: String): String {
        val first = stripModeSwitch(raw).split(',').first().trim()
        val exec = Regex("""^exec\s+(?:--\S+\s+)*"?\$(\w+)"?(.*)$""").find(first)
        if (exec != null) {
            val name = exec.groupValues[1].replace('_', ' ').replaceFirstChar(Char::uppercase)
            val args = exec.groupValues[2].trim()
            return if (args.isEmpty()) name else "$name $args".take(48)
        }
        if (first.startsWith("exec ")) return first.removePrefix("exec ").trim().trim('"').take(48)
        return expand(first).trim('"').take(48)
    }

    private fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "x" }

    private fun unique(into: Map<String, MenuModel.Item>, id: String): String {
        if (id !in into) return id
        var n = 2
        while ("$id-$n" in into) n++
        return "$id-$n"
    }

    private fun submenu(id: String, label: String, icon: String, hint: String?): MenuModel.Item {
        val (text, glyph) = splitGlyphs(label)
        return MenuModel.Item(id, text, glyph.ifEmpty { icon }, null, null, null, false, null, null, null, null, hint = hint)
    }

    private fun action(id: String, label: String, keys: String?, sway: String): MenuModel.Item {
        val (text, glyph) = splitGlyphs(label)
        return MenuModel.Item(id, text, glyph, null, "/opt/swatonly/wm-msg ${quote(sway)}", null, false, null, null, null, null, hint = keys)
    }

    /** manjaro-sway's help comments carry Nerd Font glyphs ("Scale up 󰁝"): they become the entry's icon. */
    private fun splitGlyphs(label: String): Pair<String, String> {
        val text = StringBuilder()
        val glyph = StringBuilder()
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            val pua = cp in 0xE000..0xF8FF || cp in 0xF0000..0x10FFFF
            (if (pua) glyph else text).appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return text.toString().trim().ifEmpty { label } to glyph.toString()
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

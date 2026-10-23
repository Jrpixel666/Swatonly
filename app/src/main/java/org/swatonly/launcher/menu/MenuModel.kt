package org.swatonly.launcher.menu

import android.content.Context
import android.graphics.Typeface
import org.json.JSONObject
import org.swatonly.launcher.App
import org.swatonly.launcher.linux.Proot

/**
 * The system menu, read from the install the way its own menu reads it:
 * Omarchy's omarchy-menu.jsonc when Omarchy is installed, the Manjaro Sway
 * menu (same format, bundled) otherwise, plus ~/.config/swatonly/menu.jsonc.
 * Dotted ids make the tree; an entry with an action runs, the rest open.
 */
class MenuModel private constructor(val source: String, private val items: LinkedHashMap<String, Item>) {

    data class Item(
        val id: String,
        val label: String,
        val icon: String,
        val title: String?,
        val action: String?,
        /** Swatonly extension: a launcher page instead of a command. */
        val launcher: String?,
        /** Swatonly extension: run the action in the launcher's terminal. */
        val terminal: Boolean,
        val provider: String?,
        val whenCond: String?,
        val checkedCond: String?,
        val disabledCond: String?,
    ) {
        val parent: String get() = id.substringBeforeLast('.', "")
        val isSubmenu: Boolean get() = action == null && launcher == null && provider == null
    }

    /** Result of evaluating an entry's shell conditions in the rootfs. */
    data class State(val visible: Boolean, val checked: Boolean, val disabled: Boolean)

    fun item(id: String): Item? = items[id]

    fun children(parent: String): List<Item> = items.values.filter { it.parent == parent }

    /** Every runnable entry, for searching the whole tree. */
    fun all(): List<Item> = items.values.toList()

    fun breadcrumb(id: String): String {
        if (id.isEmpty()) return ""
        val parts = id.split('.')
        return (1..parts.size).mapNotNull { items[parts.take(it).joinToString(".")]?.label }.joinToString(" › ")
    }

    /**
     * Evaluates when/checked/disabled for [entries] in one proot call.
     * Without a rootfs every entry shows, unchecked.
     */
    fun evaluate(ctx: Context, entries: List<Item>): Map<String, State> {
        val withConds = entries.filter { it.whenCond != null || it.checkedCond != null || it.disabledCond != null }
        val out = HashMap<String, State>()
        for (e in entries) out[e.id] = State(visible = true, checked = false, disabled = false)
        if (withConds.isEmpty() || !App.instance.rootFs.isExtracted) return out
        val script = StringBuilder()
        script.append("rt=\${XDG_RUNTIME_DIR:-/run/user/0}\n")
        script.append("export SWAYSOCK=\$(ls -t \$rt/sway-ipc.*.sock 2>/dev/null | head -1)\n")
        script.append("export HYPRLAND_INSTANCE_SIGNATURE=\$(ls -t \$rt/hypr 2>/dev/null | head -1)\n")
        script.append("c() { timeout 4 bash -c \"\$1\" >/dev/null 2>&1 </dev/null && echo 1 || echo 0; }\n")
        for (e in withConds) {
            fun line(kind: String, cond: String?) {
                if (cond != null) script.append("echo \"@@${e.id} $kind \$(c ${Proot.quote(cond)})\"\n")
            }
            line("when", e.whenCond)
            line("checked", e.checkedCond)
            line("disabled", e.disabledCond)
        }
        val (_, output) = try {
            Proot.run(ctx, listOf("/bin/bash", "-lc", script.toString()), timeoutSec = 30)
        } catch (_: Exception) { return out }
        val results = HashMap<String, MutableMap<String, Boolean>>()
        for (l in output.lines()) {
            if (!l.startsWith("@@")) continue
            val p = l.removePrefix("@@").trim().split(' ')
            if (p.size == 3) results.getOrPut(p[0]) { HashMap() }[p[1]] = p[2] == "1"
        }
        for (e in withConds) {
            val r = results[e.id] ?: continue
            out[e.id] = State(
                visible = if (e.whenCond != null) r["when"] ?: true else true,
                checked = r["checked"] ?: false,
                disabled = r["disabled"] ?: false,
            )
        }
        return out
    }

    companion object {
        /** Menu ids of the Manjaro Sway menu when it is nested under Omarchy's. */
        const val NESTED = "swatonly"

        fun load(ctx: Context): MenuModel {
            val app = App.instance
            val rootFs = app.rootFs
            val wantOmarchy = when (app.prefs.menuSource) {
                "omarchy" -> true
                "manjaro-sway" -> false
                else -> rootFs.omarchyInstalled
            }
            val items = LinkedHashMap<String, Item>()
            val source: String
            if (wantOmarchy) {
                val text = listOf("/etc/swatonly/omarchy-menu.jsonc", "/usr/share/omarchy/default/omarchy/omarchy-menu.jsonc")
                    .firstNotNullOfOrNull { rootFs.resolve(it)?.takeIf { f -> f.isFile }?.readText() }
                    ?: asset(ctx, "omarchy/menu.jsonc")
                parseInto(text, items, "")
                // the Manjaro Sway menu stays reachable from Omarchy's root
                items[NESTED] = Item(NESTED, "Manjaro Sway", "", null, null, null, false, null, null, null, null)
                parseInto(asset(ctx, "menus/manjaro-sway.jsonc"), items, "$NESTED.")
                source = if (items.size > 1) "omarchy" else "manjaro-sway"
            } else {
                parseInto(asset(ctx, "menus/manjaro-sway.jsonc"), items, "")
                source = "manjaro-sway"
            }
            rootFs.resolve("/root/.config/swatonly/menu.jsonc")?.takeIf { it.isFile }?.let {
                try { parseInto(it.readText(), items, "") } catch (_: Exception) {}
            }
            return MenuModel(source, items)
        }

        private fun asset(ctx: Context, path: String): String? = try {
            ctx.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) { null }

        private fun parseInto(jsonc: String?, into: LinkedHashMap<String, Item>, prefix: String) {
            if (jsonc.isNullOrBlank()) return
            val json = try { JSONObject(stripJsonc(jsonc)) } catch (_: Exception) { return }
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = json.optJSONObject(id) ?: continue
                fun s(k: String) = o.optString(k, "").ifEmpty { null }
                into[prefix + id] = Item(
                    id = prefix + id,
                    label = s("label") ?: id.substringAfterLast('.'),
                    icon = if (s("iconFont") == null) o.optString("icon", "") else "",
                    title = s("title"),
                    action = s("action"),
                    launcher = s("launcher"),
                    terminal = o.optBoolean("terminal", false),
                    provider = s("provider"),
                    whenCond = s("when"),
                    checkedCond = s("checked"),
                    disabledCond = s("disabled"),
                )
            }
        }

        /** JSONC -> JSON: drops // and /* */ comments outside strings and trailing commas. */
        fun stripJsonc(src: String): String {
            val sb = StringBuilder(src.length)
            var i = 0
            var inStr = false
            while (i < src.length) {
                val c = src[i]
                if (inStr) {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                    if (c == '"') inStr = false
                    i++
                    continue
                }
                when {
                    c == '"' -> { inStr = true; sb.append(c); i++ }
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                        val end = src.indexOf("*/", i + 2)
                        i = if (end < 0) src.length else end + 2
                    }
                    c == ',' -> {
                        var j = i + 1
                        while (j < src.length && src[j].isWhitespace()) j++
                        if (j < src.length && (src[j] == '}' || src[j] == ']')) i++ else { sb.append(c); i++ }
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }

        @Volatile
        private var iconFont: Typeface? = null
        @Volatile
        private var iconFontSearched = false

        /** A Nerd Font from the install for the menu's glyphs; null when none is installed. */
        fun iconTypeface(): Typeface? {
            if (iconFontSearched) return iconFont
            iconFontSearched = true
            val fonts = App.instance.rootFs.resolve("/usr/share/fonts") ?: return null
            val f = fonts.walkTopDown().maxDepth(3).filter { it.isFile && it.name.contains("NerdFont") && it.name.endsWith(".ttf") }
                .sortedBy { if (it.name.contains("Regular")) 0 else 1 }.firstOrNull()
            iconFont = f?.let { try { Typeface.createFromFile(it) } catch (_: Exception) { null } }
            return iconFont
        }

        fun resetIconFont() { iconFontSearched = false; iconFont = null }
    }
}

/** Installed fonts, for Omarchy's "fonts" menu provider. */
internal object FontList {
    fun monospace(ctx: Context): List<String> {
        if (!App.instance.rootFs.isExtracted) return emptyList()
        val (_, out) = try {
            Proot.run(ctx, listOf("/bin/bash", "-lc", "fc-list :spacing=100 family | cut -d, -f1 | sort -u"), timeoutSec = 30)
        } catch (_: Exception) { return emptyList() }
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("proot") }
    }
}

package org.swatonly.launcher.linux

import android.content.Context
import org.swatonly.launcher.App
import java.io.File

/**
 * Tweaks are discovered from the Manjaro install itself: every `set $var`
 * in /etc/sway/autostart (session daemons) and /etc/sway/definitions (theme
 * application, terminal, launcher, keys...). They are applied the way
 * manjaro-sway intends overrides to be made: variables in
 * ~/.config/sway/definitions.d, options in ~/.config/sway/config.d.
 */
object Tweaks {

    sealed class Tweak(val key: String, val title: String, val description: String, val section: String) {
        class Toggle(key: String, title: String, description: String, section: String, val default: Boolean) :
            Tweak(key, title, description, section)

        class Choice(key: String, title: String, description: String, section: String, val options: List<Pair<String, String>>, val default: String) :
            Tweak(key, title, description, section)

        class Text(key: String, title: String, description: String, section: String, val default: String) :
            Tweak(key, title, description, section)
    }

    const val SECTION_AUTOSTART = "Session daemons (/etc/sway/autostart)"
    const val SECTION_THEMING = "Theme application (/etc/sway/definitions)"
    const val SECTION_WM = "Window manager"
    const val SECTION_VARS = "Sway variables (/etc/sway/definitions)"

    private const val VARS_FILE = "root/.config/sway/definitions.d/90-swatonly-tweaks.conf"
    private const val OPTS_FILE = "root/.config/sway/config.d/50-swatonly-tweaks.conf"

    /** A `set $name value` line, with the comment block above it. */
    data class SwayVar(val name: String, val value: String, val comment: String)

    fun parseVars(text: String): List<SwayVar> {
        val out = ArrayList<SwayVar>()
        val comment = StringBuilder()
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.startsWith("#") -> {
                    val c = t.trimStart('#').trim()
                    if (!c.startsWith("shellcheck") && c.isNotEmpty()) comment.appendLine(c)
                }
                t.startsWith("set $") -> {
                    val rest = t.removePrefix("set $")
                    val sp = rest.indexOfFirst { it == ' ' || it == '\t' }
                    if (sp > 0) out += SwayVar(rest.substring(0, sp), rest.substring(sp + 1).trim(), comment.toString().trim())
                    comment.clear()
                }
                t.isEmpty() -> comment.clear()
                else -> comment.clear()
            }
        }
        return out
    }

    private fun source(ctx: Context, guest: String, asset: String): String {
        val f = App.instance.rootFs.resolve(guest)
        if (f != null && f.isFile) return f.readText()
        return try { ctx.assets.open(asset).bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
    }

    fun autostartVars(ctx: Context) = parseVars(source(ctx, "/etc/sway/autostart", "manjaro-sway/sway/autostart"))
    fun definitionVars(ctx: Context) = parseVars(source(ctx, "/etc/sway/definitions", "manjaro-sway/sway/definitions"))

    private fun humanize(name: String): String {
        val n = name.removePrefix("initialize_").removePrefix("apply_").removePrefix("configure_")
        val words = n.split('_', '-').filter { it.isNotEmpty() }
        return words.joinToString(" ").replaceFirstChar(Char::uppercase)
    }

    private fun describe(v: SwayVar): String {
        val cmd = v.value.trim('\'', '"')
        val bin = Regex("""command -v ([\w.-]+)""").find(cmd)?.groupValues?.get(1)
            ?: Regex("""systemctl --user [^']*?([\w-]+)$""").find(cmd)?.groupValues?.get(1)
        val parts = ArrayList<String>()
        if (v.comment.isNotEmpty()) parts += v.comment.lines().joinToString(" ")
        if (bin != null) parts += "runs $bin"
        if (parts.isEmpty()) parts += cmd.take(90)
        return parts.joinToString(" · ")
    }

    fun all(ctx: Context): List<Tweak> {
        val out = ArrayList<Tweak>()
        for (v in autostartVars(ctx)) {
            if (v.name == "apply_background" || v.name.startsWith("initialize_") || v.value.startsWith("'")) {
                out += Tweak.Toggle("var:${v.name}", humanize(v.name), describe(v), SECTION_AUTOSTART, true)
            }
        }
        val defs = definitionVars(ctx)
        for (v in defs) {
            if (v.name.startsWith("apply_") || v.name.startsWith("configure_") || v.name.startsWith("generate_")) {
                out += Tweak.Toggle("var:${v.name}", humanize(v.name), describe(v), SECTION_THEMING, true)
            }
        }

        out += Tweak.Choice("opt:gaps_inner", "Inner gaps", "space between tiled windows", SECTION_WM,
            listOf("0", "2", "4", "6", "8", "10", "14", "20").map { it to "$it px" }, "")
        out += Tweak.Choice("opt:gaps_outer", "Outer gaps", "space around the workspace", SECTION_WM,
            listOf("0", "2", "4", "6", "8", "10", "14", "20").map { it to "$it px" }, "")
        out += Tweak.Choice("opt:smart_gaps", "Smart gaps", "hide gaps when a workspace has one window", SECTION_WM,
            listOf("on" to "on", "off" to "off", "inverse_outer" to "inverse outer"), "on")
        out += Tweak.Choice("opt:default_border", "Window borders", "border drawn around tiled windows", SECTION_WM,
            listOf("pixel 1" to "1 px", "pixel 2" to "2 px", "pixel 3" to "3 px", "normal" to "title bar", "none" to "none"), "")
        out += Tweak.Choice("opt:focus_follows_mouse", "Focus follows mouse", "move focus when the pointer enters a window", SECTION_WM,
            listOf("no" to "no", "yes" to "yes", "always" to "always"), "no")
        out += Tweak.Choice("opt:workspace_auto_back_and_forth", "Workspace back and forth", "switching to the current workspace returns to the previous one", SECTION_WM,
            listOf("no" to "no", "yes" to "yes"), "")
        out += Tweak.Choice("opt:autotile", "Layout of new workspaces", "workspace_layout", SECTION_WM,
            listOf("default" to "split", "stacking" to "stacking", "tabbed" to "tabbed"), "")
        out += Tweak.Text("opt:xkb_layout", "Keyboard layout", "xkb layout(s) for sway, e.g. us,de", SECTION_WM, "")

        for (v in defs) {
            if (v.name.startsWith("apply_") || v.name.startsWith("configure_") || v.name.startsWith("generate_")) continue
            if (v.name.startsWith("ws")) continue
            when (v.name) {
                "mod" -> out += Tweak.Choice("var:mod", "Modifier key (\$mod)", "the key all sway shortcuts use", SECTION_VARS,
                    listOf("Mod4" to "Super (logo key)", "Mod1" to "Alt"), v.value)
                else -> out += Tweak.Text("var:${v.name}", "\$${v.name}", v.comment.lines().firstOrNull()?.takeIf { it.isNotEmpty() } ?: "sway variable", SECTION_VARS, v.value)
            }
        }
        return out
    }

    fun value(t: Tweak): String = App.instance.prefs.tweak(t.key, when (t) {
        is Tweak.Toggle -> if (t.default) "on" else "off"
        is Tweak.Choice -> t.default
        is Tweak.Text -> t.default
    })

    fun set(t: Tweak, value: String) {
        val default = when (t) {
            is Tweak.Toggle -> if (t.default) "on" else "off"
            is Tweak.Choice -> t.default
            is Tweak.Text -> t.default
        }
        App.instance.prefs.setTweak(t.key, if (value == default) null else value)
    }

    /** Writes the override files into the rootfs. */
    fun write(ctx: Context) {
        val rootFs = App.instance.rootFs
        if (!rootFs.isExtracted) return
        val prefs = App.instance.prefs
        val tweaks = all(ctx).associateBy { it.key }

        val vars = StringBuilder("# Generated by the Swatonly launcher (Settings > Tweaks). Edits are overwritten.\n")
        val opts = StringBuilder("# Generated by the Swatonly launcher (Settings > Tweaks). Edits are overwritten.\n")
        for (key in prefs.tweakKeys().sorted()) {
            val t = tweaks[key] ?: continue
            val v = prefs.tweak(key, "")
            when {
                key.startsWith("var:") && t is Tweak.Toggle -> if (v == "off") vars.append("set \$${key.removePrefix("var:")} 'true'\n")
                key.startsWith("var:") -> if (v.isNotEmpty()) vars.append("set \$${key.removePrefix("var:")} $v\n")
                key == "opt:gaps_inner" -> if (v.isNotEmpty()) opts.append("gaps inner $v\n")
                key == "opt:gaps_outer" -> if (v.isNotEmpty()) opts.append("gaps outer $v\n")
                key == "opt:autotile" -> if (v.isNotEmpty()) opts.append("workspace_layout $v\n")
                key == "opt:xkb_layout" -> if (v.isNotEmpty()) opts.append("input type:keyboard xkb_layout \"$v\"\n")
                key.startsWith("opt:") -> if (v.isNotEmpty()) opts.append("${key.removePrefix("opt:")} $v\n")
            }
        }
        File(rootFs.dir, VARS_FILE).apply { parentFile?.mkdirs(); writeText(vars.toString()) }
        File(rootFs.dir, OPTS_FILE).apply { parentFile?.mkdirs(); writeText(opts.toString()) }
    }

    fun applyAndReload(ctx: Context) {
        write(ctx)
        Session.reloadSway(ctx)
    }

    /**
     * Installs a theme into the Linux user config, as manjaro-sway-theme does
     * (blocking; call off the main thread):
     * the active theme becomes theme.conf, the other one waits as the
     * theme-toggle.sh alternative; foot gets the matching colours.
     */
    fun applyTheme(ctx: Context) {
        val app = App.instance
        val rootFs = app.rootFs
        if (!rootFs.isExtracted) return
        val prefs = app.prefs
        val night = prefs.activeVariant == "night"
        val active = if (night) prefs.nightTheme else prefs.dayTheme
        val other = if (night) prefs.dayTheme else prefs.nightTheme
        val defs = File(rootFs.dir, "root/.config/sway/definitions.d").apply { mkdirs() }
        app.themes.rawFile(active, "theme.conf")?.let { File(defs, "theme.conf").writeText(it) }
        val otherName = if (night) "theme.dark.conf_" else "theme.light.conf_"
        File(defs, "theme.dark.conf_").delete()
        File(defs, "theme.light.conf_").delete()
        app.themes.rawFile(other, "theme.conf")?.let { File(defs, otherName).writeText(it) }
        val foot = File(rootFs.dir, "root/.config/foot").apply { mkdirs() }
        app.themes.rawFile(active, "foot-theme.ini")?.let { File(foot, "foot-theme.ini").writeText(it) }
        // an Omarchy theme is also applied the Omarchy way (Hyprland, walker, btop, nvim...)
        if (active.startsWith("omarchy:") && rootFs.omarchyInstalled) {
            Proot.run(ctx, listOf("/bin/bash", "-lc", "omarchy-theme-set ${Proot.quote(active.removePrefix("omarchy:"))}"), timeoutSec = 180)
        }
        Session.reloadSway(ctx)
    }
}

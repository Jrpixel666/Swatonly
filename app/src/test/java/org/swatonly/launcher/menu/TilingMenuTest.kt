package org.swatonly.launcher.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.swatonly.launcher.linux.Keybindings
import org.swatonly.launcher.linux.Tweaks
import java.io.File

/** Builds the tiling menu from the manjaro-sway release staged by scripts/fetch-manjaro-sway.sh. */
class TilingMenuTest {

    private val staged = File("src/main/assets/manjaro-sway/sway")

    private fun menu(): Map<String, MenuModel.Item> {
        assumeTrue("run scripts/fetch-manjaro-sway.sh first", File(staged, "modes").isDirectory)
        val vars = Tweaks.parseVars(File(staged, "definitions").readText()).associate { it.name to it.value }
        val sources = File(staged, "modes").listFiles()!!.sortedBy { it.name }.map { "/etc/sway/modes/${it.name}" to it.readText() }
        val items = LinkedHashMap<String, MenuModel.Item>()
        TilingMenu.build(Keybindings.parse(sources, vars).sortedBy { it.mode }, items, "") { Keybindings.expand(it, vars) }
        if (System.getenv("DUMP_MENU") != null) items.values.forEach { println("${it.id}  |  ${it.label}  |  ${it.hint}  |  ${it.action}") }
        return items
    }

    private fun children(items: Map<String, MenuModel.Item>, parent: String) = items.values.filter { it.parent == parent }

    @Test
    fun categoriesComeFromTheHelpComments() {
        val items = menu()
        val cats = children(items, TilingMenu.ROOT).map { it.label }
        assertTrue(cats.toString(), cats.containsAll(listOf("Launch", "Action", "Navigation")))
        // "Terminal" covers $mod+Return and $mod+Shift+Return
        val terminal = children(items, "wm.launch").first { it.label == "Terminal" }
        val term = children(items, terminal.id).first()
        assertEquals("/opt/swatonly/wm-msg 'exec \$term_cwd'", term.action)
        assertTrue(term.hint!!.contains("Enter"))
    }

    @Test
    fun modesBecomeSubmenus() {
        val items = menu()
        val exit = children(items, "wm.launch").first { it.label == "Exit Menu" }
        assertTrue(exit.isSubmenu)
        val labels = children(items, exit.id).map { it.label }
        assertTrue(labels.toString(), labels.containsAll(listOf("lock", "logout", "reboot", "suspend", "shutdown")))
        // "Return to default mode" (Escape) is not an entry
        assertTrue(children(items, exit.id).none { it.action!!.contains("'mode default'") })
        val lock = children(items, exit.id).first { it.label == "lock" }
        assertEquals("/opt/swatonly/wm-msg 'exec \$locking, mode default'", lock.action)
    }

    @Test
    fun groupedBindingsBecomeOneSubmenu() {
        val items = menu()
        val focus = children(items, "wm.navigation").first { it.label == "Move focus" }
        assertTrue(focus.isSubmenu)
        val dirs = children(items, focus.id).map { it.label }
        // the vim-style duplicates ($left_alt…) collapse into one entry per command
        assertEquals(listOf("focus left", "focus down", "focus up", "focus right"), dirs)
        val ws = children(items, "wm.navigation").first { it.label == "Switch workspace" }
        assertNotNull(children(items, ws.id).firstOrNull { it.label == "workspace number 1" })
        // Nerd Font glyphs in the help comments move to the icon
        val scale = children(items, "wm.action").first { it.label == "Scale up" }
        assertTrue(scale.icon.isNotEmpty())
    }
}

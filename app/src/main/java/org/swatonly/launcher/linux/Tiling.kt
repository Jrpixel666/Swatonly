package org.swatonly.launcher.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.swatonly.launcher.App
import java.util.concurrent.Executors

/**
 * The tiling buttons: each one sends a sway command to the running desktop
 * through /opt/swatonly/wm-msg, which translates it for Hyprland when Omarchy's
 * window manager is the one running.
 */
object Tiling {

    /** One button. [command] is sway syntax; "menu:" / "launcher:" ids are handled in the UI. */
    data class Button(val id: String, val label: String, val title: String, val command: String)

    val ALL: List<Button> = listOf(
        Button("menu", "☰", "System menu", "menu:"),
        Button("launcher", "⌕", "App launcher (rofi / walker)", "launcher:"),
        Button("terminal", ">_", "New terminal", "exec:term"),
        Button("ws_prev", "‹", "Previous workspace", "workspace prev_on_output"),
        Button("ws1", "1", "Workspace 1", "workspace number 1"),
        Button("ws2", "2", "Workspace 2", "workspace number 2"),
        Button("ws3", "3", "Workspace 3", "workspace number 3"),
        Button("ws4", "4", "Workspace 4", "workspace number 4"),
        Button("ws5", "5", "Workspace 5", "workspace number 5"),
        Button("ws_next", "›", "Next workspace", "workspace next_on_output"),
        Button("focus_left", "◂", "Focus left", "focus left"),
        Button("focus_down", "▾", "Focus down", "focus down"),
        Button("focus_up", "▴", "Focus up", "focus up"),
        Button("focus_right", "▸", "Focus right", "focus right"),
        Button("move_left", "⇠", "Move window left", "move left"),
        Button("move_down", "⇣", "Move window down", "move down"),
        Button("move_up", "⇡", "Move window up", "move up"),
        Button("move_right", "⇢", "Move window right", "move right"),
        Button("splith", "◫", "Split horizontally", "splith"),
        Button("splitv", "⊟", "Split vertically", "splitv"),
        Button("split_toggle", "⇋", "Toggle split direction", "layout toggle split"),
        Button("tabbed", "▭", "Tabbed layout", "layout tabbed"),
        Button("stacking", "☰", "Stacking layout", "layout stacking"),
        Button("float", "◈", "Toggle floating", "floating toggle"),
        Button("fullscreen", "⛶", "Toggle fullscreen", "fullscreen toggle"),
        Button("sticky", "⚲", "Toggle sticky", "sticky toggle"),
        Button("grow_w", "↔+", "Wider", "resize grow width 40px"),
        Button("shrink_w", "↔−", "Narrower", "resize shrink width 40px"),
        Button("grow_h", "↕+", "Taller", "resize grow height 40px"),
        Button("shrink_h", "↕−", "Shorter", "resize shrink height 40px"),
        Button("to_ws1", "→1", "Send window to workspace 1", "move container to workspace number 1"),
        Button("to_ws2", "→2", "Send window to workspace 2", "move container to workspace number 2"),
        Button("to_ws3", "→3", "Send window to workspace 3", "move container to workspace number 3"),
        Button("scratch_send", "⇓S", "Send to scratchpad", "move scratchpad"),
        Button("scratch_show", "S", "Show scratchpad", "scratchpad show"),
        Button("kill", "✕", "Close window", "kill"),
        Button("reload", "↻", "Reload window manager", "reload"),
    )

    val DEFAULT = listOf("menu", "launcher", "terminal", "ws1", "ws2", "ws3", "ws4", "focus_left", "focus_right",
        "move_left", "move_right", "splith", "splitv", "tabbed", "float", "fullscreen", "kill")

    fun byId(id: String): Button? = ALL.firstOrNull { it.id == id }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Sends one sway-syntax command (or "exec:<cmd>") to the running window manager. */
    fun send(ctx: Context, command: String) {
        val app = ctx.applicationContext
        if (!Session.swayRunning) {
            Toast.makeText(ctx, "Start the desktop first", Toast.LENGTH_SHORT).show()
            return
        }
        val argv = when {
            command == "exec:term" -> listOf("/opt/swatonly/wm-msg", "exec", "command -v footclient >/dev/null && footclient || foot || alacritty || kitty")
            command.startsWith("exec:") -> listOf("/opt/swatonly/wm-msg", "exec", command.removePrefix("exec:"))
            else -> listOf("/opt/swatonly/wm-msg") + command.split(' ').filter { it.isNotEmpty() }
        }
        executor.execute {
            val (code, out) = try { Proot.run(app, argv, timeoutSec = 15) } catch (e: Exception) { -1 to (e.message ?: "") }
            if (code != 0) main.post { Toast.makeText(app, out.lines().lastOrNull { it.isNotBlank() } ?: "failed ($code)", Toast.LENGTH_SHORT).show() }
        }
    }

    /** The desktop's own app launcher: Omarchy's menu under Hyprland, rofi ($menu) under sway. */
    fun openWmLauncher(ctx: Context) {
        val wm = App.instance.rootFs.runningWm
        send(ctx, if (wm == "hyprland") "exec:omarchy-launch-walker || omarchy-menu apps || walker"
        else "exec:rofi -show combi -combi-modes drun,run -show-icons || wofi --show drun")
    }
}

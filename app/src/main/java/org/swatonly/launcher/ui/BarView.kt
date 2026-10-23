package org.swatonly.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.swatonly.launcher.App
import org.swatonly.launcher.R
import org.swatonly.launcher.apps.AppEntry
import org.swatonly.launcher.apps.AppRepository
import org.swatonly.launcher.apps.IconLoader
import org.swatonly.launcher.linux.Session
import org.swatonly.launcher.linux.Tiling
import org.swatonly.launcher.wayland.Window
import org.swatonly.launcher.wayland.WaylandServer
import java.util.Date

/**
 * The bottom bar, styled like manjaro-sway's waybar: the green Monero logo on
 * the left opens "My apps" (long press: the system menu), pinned apps,
 * workspaces and open Linux windows in the middle, clock and battery modules,
 * then "+" (add apps to the bar) and settings.
 */
@SuppressLint("ViewConstructor")
class BarView(context: Context, private val callbacks: Callbacks) : LinearLayout(context) {

    interface Callbacks {
        fun onLogo()
        fun onLogoLong()
        fun onAdd()
        fun onSettings()
        fun onPinnedLong(entry: AppEntry, view: View)
        fun onWindow(w: Window)
    }

    private val theme get() = App.instance.themes.current()
    private val prefs get() = App.instance.prefs
    private val items = LinearLayout(context)
    private val clock = Ui.text(context, "", 14f, bold = true)
    private val battery = Ui.text(context, "", 13f)
    private val iconPx get() = Ui.dp(context, prefs.barIconSizeDp)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        build()
    }

    fun build() {
        removeAllViews()
        items.removeAllViews()
        val alpha = (prefs.barOpacity.coerceIn(30, 100) * 255 / 100) shl 24
        background = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor((theme.background and 0x00FFFFFF) or alpha) },
        ))
        val pad = Ui.dp(context, 4)
        setPadding(pad, pad, pad, pad)

        // left: the logo (always green, as asked) -> My apps
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.ic_monero)
            setColorFilter(context.getColor(R.color.monero_green))
            val p = Ui.dp(context, 7)
            setPadding(p + Ui.dp(context, 3), p, p + Ui.dp(context, 3), p)
            background = Ui.pressable(context)
            contentDescription = context.getString(R.string.my_apps)
            setOnClickListener { callbacks.onLogo() }
            setOnLongClickListener { callbacks.onLogoLong(); true }
        }
        addView(logo, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(separator())

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
        }
        items.orientation = HORIZONTAL
        items.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(items, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // right: waybar-like modules
        clock.setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
        clock.gravity = Gravity.CENTER
        addView(battery, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER })
        battery.gravity = Gravity.CENTER
        battery.setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 6), 0)
        battery.visibility = if (prefs.showBattery) View.VISIBLE else View.GONE
        addView(clock, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(separator())
        addView(iconButton(R.drawable.ic_plus, context.getString(R.string.add_to_bar)) { callbacks.onAdd() })
        addView(iconButton(R.drawable.ic_settings, context.getString(R.string.settings)) { callbacks.onSettings() })

        refreshItems()
        tick()
    }

    private fun separator(): View = View(context).apply {
        setBackgroundColor((theme.text and 0x00FFFFFF) or 0x33000000)
        layoutParams = LayoutParams(Ui.dp(context, 1), Ui.dp(context, 22)).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
        }
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(theme.text)
        val p = Ui.dp(context, 9)
        setPadding(p, p, p, p)
        background = Ui.pressable(context)
        contentDescription = desc
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** Pinned apps, then running Linux windows (like waybar's workspaces/taskbar). */
    fun refreshItems() {
        items.removeAllViews()
        for (key in prefs.pinned) {
            val e = AppRepository.find(key) ?: continue
            items.addView(appButton(e))
        }
        if (Session.swayRunning && prefs.workspacesOnBar > 0) {
            if (items.childCount > 0) items.addView(separator())
            for (n in 1..prefs.workspacesOnBar) items.addView(workspaceButton(n))
        }
        if (prefs.showWindowsOnBar) {
            val windows = WaylandServer.instance?.windowList().orEmpty()
            if (windows.isNotEmpty() && items.childCount > 0) items.addView(separator())
            for (w in windows) items.addView(windowButton(w))
        }
    }

    private fun appButton(e: AppEntry): View {
        val iv = ImageView(context)
        val p = Ui.dp(context, 5)
        iv.setPadding(p, p, p, p)
        iv.background = Ui.pressable(context)
        iv.contentDescription = e.label
        IconLoader.load(context, e, iconPx, iv)
        iv.setOnClickListener { AppRepository.launch(context, e) }
        iv.setOnLongClickListener { callbacks.onPinnedLong(e, iv); true }
        iv.layoutParams = LayoutParams(iconPx + 2 * p, iconPx + 2 * p).apply { setMargins(Ui.dp(context, 1), 0, Ui.dp(context, 1), 0) }
        return iv
    }

    /** Like waybar's sway/workspaces: switches the desktop's workspace and shows it. */
    private fun workspaceButton(n: Int): View = Ui.text(context, "$n", 13f, theme.text, bold = true).apply {
        gravity = Gravity.CENTER
        background = Ui.pressable(context)
        contentDescription = "Workspace $n"
        setOnClickListener {
            Tiling.send(context, "workspace number $n")
            WaylandServer.instance?.focusWindowByAppId("sway", context)
        }
        layoutParams = LayoutParams(Ui.dp(context, 30), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun windowButton(w: Window): View {
        val box = LinearLayout(context)
        box.orientation = HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val p = Ui.dp(context, 5)
        box.setPadding(p, p, Ui.dp(context, 8), p)
        // waybar "focused workspace": accent underline
        box.background = LayerDrawable(arrayOf(Ui.pressable(context, (theme.accent and 0x00FFFFFF) or 0x33000000),
            GradientDrawable().apply { setColor(theme.accent) })).apply {
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, Ui.dp(context, 2))
        }
        val entry = AppRepository.linuxEntryForAppId(w.appId)
        val iv = ImageView(context)
        val size = (iconPx * 0.8f).toInt()
        if (entry != null) IconLoader.load(context, entry, size, iv) else iv.setImageBitmap(IconLoader.letterBitmap(if (w.isSway) "S" else w.title, size))
        box.addView(iv, LayoutParams(size, size))
        val label = Ui.text(context, (if (w.isSway) App.instance.rootFs.runningWm ?: "sway" else w.title).take(14), 12f).apply { setPadding(Ui.dp(context, 6), 0, 0, 0); maxLines = 1 }
        box.addView(label)
        box.setOnClickListener { callbacks.onWindow(w) }
        box.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(Ui.dp(context, 2), 0, Ui.dp(context, 2), 0) }
        return box
    }

    fun tick() {
        val pattern = if (prefs.clock24h) "HH:mm" else "h:mm a"
        clock.text = DateFormat.format(pattern, Date())
    }

    fun setBattery(level: Int, charging: Boolean) {
        val icon = when {
            charging -> "⚡"
            level >= 80 -> "█"
            level >= 50 -> "▆"
            level >= 20 -> "▃"
            else -> "▁"
        }
        battery.text = "$icon $level%"
        battery.setTextColor(if (level < 15 && !charging) theme.error else theme.text)
    }
}

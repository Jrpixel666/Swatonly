package org.swatonly.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.swatonly.launcher.App
import org.swatonly.launcher.BuildConfig
import org.swatonly.launcher.R
import org.swatonly.launcher.apps.AppEntry
import org.swatonly.launcher.apps.AppRepository
import org.swatonly.launcher.apps.IconLoader
import org.swatonly.launcher.linux.Session
import org.swatonly.launcher.linux.Tweaks
import org.swatonly.launcher.wayland.Window
import org.swatonly.launcher.wayland.WaylandServer
import org.swatonly.launcher.wayland.WindowActivity
import java.io.File
import java.util.Date
import kotlin.math.abs

/** The home screen: the manjaro-sway desktop look, with a waybar-style bar at the bottom. */
class LauncherActivity : Activity() {

    private val app get() = App.instance
    private val prefs get() = app.prefs
    private val theme get() = app.themes.current()

    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var bar: BarView
    private lateinit var drawer: AppDrawer
    private lateinit var drawerHost: FrameLayout
    private var info: LinearLayout? = null
    private lateinit var gestures: GestureDetector

    private val appsListener: () -> Unit = { refreshBar(); drawer.refresh(); updateInfo() }
    private val themeListener: () -> Unit = { recreate() }
    private val windowsListener: () -> Unit = { refreshBar(); updateInfo() }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    if (level >= 0) bar.setBattery(level * 100 / scale.coerceAtLeast(1),
                        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                }
                else -> { bar.tick(); updateInfo() }
            }
        }
    }

    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = AppRepository.reload(this@LauncherActivity)
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = AppRepository.reload(this@LauncherActivity)
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = AppRepository.reload(this@LauncherActivity)
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = AppRepository.reload(this@LauncherActivity)
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = AppRepository.reload(this@LauncherActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (prefs.wallpaper == "system") {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or (if (theme.isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)

        root = FrameLayout(this)
        root.fitsSystemWindows = false
        wallpaper = WallpaperView(this)
        root.addView(wallpaper, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = FrameLayout(this)
        content.fitsSystemWindows = true
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (prefs.showInfoWidget) {
            val w = buildInfo()
            info = w
            content.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                setMargins(Ui.dp(this@LauncherActivity, 20), Ui.dp(this@LauncherActivity, 28), Ui.dp(this@LauncherActivity, 20), 0)
            })
        }

        bar = BarView(this, barCallbacks)
        content.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48), Gravity.BOTTOM))

        drawerHost = FrameLayout(this)
        drawerHost.visibility = View.GONE
        drawerHost.setBackgroundColor(0x66000000)
        drawerHost.setOnClickListener { closeDrawer() }
        drawer = AppDrawer(this) { closeDrawer() }
        drawerHost.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            val m = Ui.dp(this@LauncherActivity, 12)
            setMargins(m, m, m, Ui.dp(this@LauncherActivity, 56))
        })
        content.addView(drawerHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (dy < -Ui.dp(this@LauncherActivity, 60) && abs(vy) > abs(vx)) { openDrawer(); return true }
                if (dy > Ui.dp(this@LauncherActivity, 60) && abs(vy) > abs(vx)) { expandNotifications(); return true }
                return false
            }
            override fun onLongPress(e: MotionEvent) { homeMenu() }
            override fun onDoubleTap(e: MotionEvent): Boolean { toggleDayNight(); return true }
        })

        app.themes.addListener(themeListener)
        AppRepository.addListener(appsListener)
        getSystemService(LauncherApps::class.java).registerCallback(launcherCallback)
        AppRepository.reload(this)

        if (intent?.getBooleanExtra(EXTRA_SETUP_CHOICE, false) == true) {
            app.setupChoiceAsked = true
            root.post { showSetupChoice() }
        } else if (!app.setupChoiceAsked && prefs.askSetupEveryStart) {
            app.setupChoiceAsked = true
            root.post { showSetupChoice() }
        } else if (!app.setupChoiceAsked) {
            app.setupChoiceAsked = true
            if (prefs.lastSetupChoice in DESKTOP_CHOICES && app.rootFs.swayInstalled && prefs.autostartSway) Session.startSway(this)
        }
        if (intent?.getBooleanExtra(EXTRA_MENU, false) == true) root.post { showSystemMenu() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (drawerHost.visibility != View.VISIBLE) {
            val barTop = bar.top + (bar.parent as View).top
            if (ev.y < barTop || ev.actionMasked != MotionEvent.ACTION_DOWN) gestures.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(tickReceiver, f)
        WaylandServer.instance?.addListener(windowsListener)
        bar.tick()
        refreshBar()
        updateInfo()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
        WaylandServer.instance?.removeListener(windowsListener)
    }

    override fun onDestroy() {
        app.themes.removeListener(themeListener)
        AppRepository.removeListener(appsListener)
        try { getSystemService(LauncherApps::class.java).unregisterCallback(launcherCallback) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already home: close the menu
        closeDrawer()
        if (intent?.getBooleanExtra(EXTRA_SETUP_CHOICE, false) == true) showSetupChoice()
        if (intent?.getBooleanExtra(EXTRA_MENU, false) == true) showSystemMenu()
    }

    /** The install's own menu (Omarchy's, or Manjaro Sway's), drawn by the launcher. */
    fun showSystemMenu(route: String = "") {
        closeDrawer()
        SystemMenu(this) { openDrawer() }.show(route)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerHost.visibility == View.VISIBLE) closeDrawer()
    }

    fun refreshBar() {
        if (::bar.isInitialized) bar.refreshItems()
    }

    // ---- drawer -------------------------------------------------------------

    private fun openDrawer() {
        if (drawerHost.visibility == View.VISIBLE) return
        drawer.open()
        drawerHost.visibility = View.VISIBLE
        drawer.translationY = Ui.dpf(this, 40f)
        drawer.alpha = 0f
        drawer.animate().translationY(0f).alpha(1f).setDuration(160).start()
    }

    private fun closeDrawer() {
        if (drawerHost.visibility != View.VISIBLE) return
        drawer.hideKeyboard()
        drawer.animate().translationY(Ui.dpf(this, 40f)).alpha(0f).setDuration(120).withEndAction { drawerHost.visibility = View.GONE }.start()
    }

    private val barCallbacks = object : BarView.Callbacks {
        override fun onLogo() { if (drawerHost.visibility == View.VISIBLE) closeDrawer() else openDrawer() }
        override fun onLogoLong() = showSystemMenu()
        override fun onAdd() = showAddToBar()
        override fun onSettings() { startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java)) }
        override fun onPinnedLong(entry: AppEntry, view: View) = pinnedMenu(entry)
        override fun onWindow(w: Window) = WindowActivity.focus(this@LauncherActivity, w)
    }

    // ---- "+": add apps to the bar ----------------------------------------------

    private fun showAddToBar() {
        val box = Ui.vertical(this)
        val searchBox = EditText(this).apply {
            hint = "filter"
            setHintTextColor(theme.subtext)
            setTextColor(theme.text)
            typeface = Ui.mono
            isSingleLine = true
            background = Ui.rounded(theme.surface, Ui.dpf(this@LauncherActivity, 6f))
            setPadding(Ui.dp(this@LauncherActivity, 12), Ui.dp(this@LauncherActivity, 8), Ui.dp(this@LauncherActivity, 12), Ui.dp(this@LauncherActivity, 8))
        }
        box.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(this@LauncherActivity, 12), 0, Ui.dp(this@LauncherActivity, 12), Ui.dp(this@LauncherActivity, 6))
        })
        val list = Ui.vertical(this)
        box.addView(list)
        val pinned = prefs.pinned.toMutableList()
        val all = AppRepository.apps + AppRepository.actions().filter { a -> AppRepository.apps.none { it.key == a.key } }
        fun fill(q: String) {
            list.removeAllViews()
            for (e in all.filter { q.isEmpty() || it.label.contains(q, true) }.take(250)) {
                val row = Ui.horizontal(this)
                row.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6))
                val iv = ImageView(this)
                IconLoader.load(this, e, Ui.dp(this, 32), iv)
                row.addView(iv, LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)))
                val label = Ui.text(this, e.label + if (e is AppEntry.Linux) "  ·linux" else "", 14f).apply { setPadding(Ui.dp(this@LauncherActivity, 12), 0, 0, 0) }
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val cb = CheckBox(this).apply {
                    isChecked = e.key in pinned
                    buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
                    setOnCheckedChangeListener { _, on -> if (on) { if (e.key !in pinned) pinned += e.key } else pinned -= e.key }
                }
                row.addView(cb)
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                list.addView(row)
            }
        }
        fill("")
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { fill(s.toString().trim()) }
        })
        val d = Ui.dialog(this, getString(R.string.add_to_bar), box)
        d.setOnDismissListener {
            prefs.pinned = pinned
            refreshBar()
        }
        d.show()
    }

    private fun pinnedMenu(e: AppEntry) {
        val box = Ui.vertical(this)
        var d: AlertDialog? = null
        fun move(delta: Int) {
            val l = prefs.pinned.toMutableList()
            val i = l.indexOf(e.key)
            val j = (i + delta).coerceIn(0, l.size - 1)
            if (i >= 0 && i != j) { l.removeAt(i); l.add(j, e.key); prefs.pinned = l }
            refreshBar()
        }
        box.addView(Ui.row(this, "Open") { AppRepository.launch(this, e); d?.dismiss() })
        if (e is AppEntry.Android) box.addView(Ui.row(this, "Open in split screen", "side by side with the app on screen") {
            AppRepository.launchAdjacent(this, e); d?.dismiss()
        })
        box.addView(Ui.row(this, "Move left") { move(-1); d?.dismiss() })
        box.addView(Ui.row(this, "Move right") { move(1); d?.dismiss() })
        box.addView(Ui.row(this, "Remove from bar") { prefs.pinned = prefs.pinned - e.key; refreshBar(); d?.dismiss() })
        d = Ui.dialog(this, e.label, box)
        d.show()
    }

    // ---- home screen extras ------------------------------------------------------

    private fun buildInfo(): LinearLayout {
        val box = Ui.vertical(this)
        box.addView(Ui.text(this, "", 44f, theme.text, bold = true).apply { tag = "clock" })
        box.addView(Ui.text(this, "", 15f, theme.subtext).apply { tag = "date" })
        box.addView(View(this).apply { setBackgroundColor(theme.accent) }, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 2)).apply {
            setMargins(0, Ui.dp(this@LauncherActivity, 10), 0, Ui.dp(this@LauncherActivity, 10))
        })
        box.addView(Ui.text(this, "", 13f, theme.text).apply { tag = "status"; setLineSpacing(0f, 1.25f) })
        val actions = Ui.horizontal(this).apply { tag = "actions"; setPadding(0, Ui.dp(this@LauncherActivity, 12), 0, 0) }
        box.addView(actions)
        return box
    }

    private fun updateInfo() {
        val w = info ?: return
        (w.findViewWithTag<TextView>("clock")).text = DateFormat.format(if (prefs.clock24h) "HH:mm" else "h:mm", Date())
        (w.findViewWithTag<TextView>("date")).text = DateFormat.format("EEEE, d MMMM", Date())
        val rootFs = app.rootFs
        val linux = when {
            Session.swayRunning -> "● ${rootFs.runningWm ?: "desktop"} running"
            rootFs.omarchyInstalled -> "○ sway + omarchy installed"
            rootFs.swayInstalled -> "○ sway installed"
            rootFs.installedMode != null -> "○ manjaro base"
            rootFs.isExtracted -> "◌ setup unfinished"
            else -> "· launcher only"
        }
        val windows = WaylandServer.instance?.windowCount() ?: 0
        val variant = if (prefs.activeVariant == "night") "night" else "day"
        (w.findViewWithTag<TextView>("status")).text = buildString {
            append("manjaro-sway  ${BuildConfig.MANJARO_SWAY_TAG}\n")
            if (rootFs.omarchyInstalled) append("omarchy       ${File(rootFs.dir, "etc/swatonly/omarchy-ref").takeIf { it.isFile }?.readText()?.trim()?.take(7) ?: "installed"}\n")
            append("linux         $linux\n")
            if (windows > 0) append("windows       $windows\n")
            append("theme         ${theme.name} ($variant)")
        }
        val actions = w.findViewWithTag<LinearLayout>("actions")
        actions.removeAllViews()
        fun chip(label: String, onClick: () -> Unit) {
            actions.addView(Ui.text(this, label, 12f, theme.text, bold = true).apply {
                background = Ui.pressable(this@LauncherActivity, (theme.surface and 0x00FFFFFF) or 0xCC000000.toInt(), Ui.dpf(this@LauncherActivity, 14f))
                setPadding(Ui.dp(this@LauncherActivity, 12), Ui.dp(this@LauncherActivity, 6), Ui.dp(this@LauncherActivity, 12), Ui.dp(this@LauncherActivity, 6))
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.dp(this@LauncherActivity, 8) })
        }
        chip("☰ menu") { showSystemMenu() }
        if (rootFs.isExtracted) chip(">_ terminal") { AppRepository.runAction(this, "terminal") }
        if (rootFs.swayInstalled) chip(if (Session.swayRunning) "◆ desktop" else "▶ desktop") { Session.startSway(this) }
        else chip("⇩ omarchy") { InstallActivity.start(this, "omarchy") }
        chip(if (prefs.activeVariant == "night") "☀ day" else "☾ night") { toggleDayNight() }
    }

    private fun toggleDayNight() {
        prefs.activeVariant = if (prefs.activeVariant == "night") "day" else "night"
        IconLoader.clear()
        if (prefs.syncThemeToLinux) Thread { Tweaks.applyTheme(this) }.start()
        app.themes.invalidate()
    }

    private fun expandNotifications() {
        try {
            @Suppress("WrongConstant")
            val sbs = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbs)
        } catch (_: Exception) {}
    }

    private fun homeMenu() {
        val box = Ui.vertical(this)
        var d: AlertDialog? = null
        box.addView(Ui.row(this, "My apps") { d?.dismiss(); openDrawer() })
        box.addView(Ui.row(this, "System menu", if (app.rootFs.omarchyInstalled) "Omarchy" else "Manjaro Sway") { d?.dismiss(); showSystemMenu() })
        box.addView(Ui.row(this, "Themes", "day ${prefs.dayTheme} · night ${prefs.nightTheme}") { d?.dismiss(); startActivity(SettingsActivity.intent(this, "themes")) })
        box.addView(Ui.row(this, "Tweaks", "from the Manjaro install") { d?.dismiss(); startActivity(SettingsActivity.intent(this, "tweaks")) })
        box.addView(Ui.row(this, "Wallpaper", prefs.wallpaper) {
            d?.dismiss()
            Ui.choose(this, "Wallpaper", WALLPAPERS, prefs.wallpaper) { prefs.wallpaper = it; recreate() }
        })
        box.addView(Ui.row(this, "Switch to ${if (prefs.activeVariant == "night") "day" else "night"} theme") { d?.dismiss(); toggleDayNight() })
        box.addView(Ui.row(this, "Setup: launcher only / launcher + Omarchy") { d?.dismiss(); showSetupChoice() })
        box.addView(Ui.row(this, "Settings") { d?.dismiss(); startActivity(Intent(this, SettingsActivity::class.java)) })
        d = Ui.dialog(this, "Swatonly", box)
        d.show()
    }

    // ---- setup choice: shown every time the launcher starts (configurable) ------

    fun showSetupChoice() {
        val rootFs = app.rootFs
        val box = Ui.vertical(this)
        box.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 14))
        var d: AlertDialog? = null

        box.addView(Ui.text(this, "How do you want to run Swatonly this time?", 14f, theme.subtext).apply {
            setPadding(Ui.dp(this@LauncherActivity, 2), 0, 0, Ui.dp(this@LauncherActivity, 12))
        })

        fun option(title: String, body: String, onPick: () -> Unit) {
            val card = Ui.vertical(this)
            card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
            card.background = Ui.pressable(this, theme.surface, Ui.dpf(this, 8f))
            card.addView(Ui.text(this, title, 16f, theme.accent, bold = true))
            card.addView(Ui.text(this, body, 13f, theme.text).apply { setPadding(0, Ui.dp(this@LauncherActivity, 4), 0, 0) })
            card.setOnClickListener { onPick(); d?.dismiss() }
            box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(this@LauncherActivity, 10)
            })
        }

        option("Launcher only",
            "The manjaro-sway look as your Android home screen. Nothing is installed or started on the Linux side.") {
            prefs.lastSetupChoice = "launcher"
        }
        val omarchyBody = when {
            rootFs.omarchyInstalled ->
                "Manjaro Sway + Omarchy are installed: start the desktop (proot, no root). Linux apps open tiled, with Omarchy's menu, themes and tools."
            rootFs.swayInstalled ->
                "Manjaro Sway is installed. Add Omarchy on top (its menu, themes, tools and the packages your CPU has), then start the desktop."
            else ->
                "Download Manjaro, install sway and the manjaro-sway-settings from release ${BuildConfig.MANJARO_SWAY_TAG}, then Omarchy, all under proot without root. Firmware and kernel modules are skipped."
        }
        option(if (rootFs.omarchyInstalled) "Launcher + Omarchy" else "Launcher + Omarchy (install)", omarchyBody) {
            prefs.lastSetupChoice = "omarchy"
            if (rootFs.omarchyInstalled) Session.startSway(this) else InstallActivity.start(this, "omarchy")
        }
        if (rootFs.swayInstalled && !rootFs.omarchyInstalled) {
            box.addView(Ui.row(this, "Start Manjaro Sway without Omarchy", "the desktop as it is now") {
                prefs.lastSetupChoice = "sway"; Session.startSway(this); d?.dismiss()
            })
        }

        val ask = CheckBox(this).apply {
            text = "Ask every time the launcher starts"
            setTextColor(theme.text)
            typeface = Ui.mono
            isChecked = prefs.askSetupEveryStart
            buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            setOnCheckedChangeListener { _, on -> prefs.askSetupEveryStart = on }
        }
        box.addView(ask)

        if (!isDefaultHome()) {
            box.addView(Ui.row(this, "Make Swatonly the default home app", "so Home opens this launcher") {
                requestDefaultHome(); d?.dismiss()
            })
        }

        d = Ui.dialog(this, "Welcome to Manjaro Sway", box)
        d.show()
    }

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = packageManager.resolveActivity(i, 0)
        return res?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    companion object {
        const val EXTRA_SETUP_CHOICE = "setup_choice"
        const val EXTRA_MENU = "system_menu"
        private val DESKTOP_CHOICES = setOf("sway", "omarchy")

        val WALLPAPERS = listOf(
            "generated" to "manjaro-sway (theme colours)",
            "gruvbox-dark" to "gruvbox dark",
            "gruvbox-light" to "gruvbox light",
            "solid" to "solid theme colour",
            "system" to "Android wallpaper",
        )
    }
}

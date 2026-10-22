package org.swatonly.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.swatonly.launcher.App
import org.swatonly.launcher.BuildConfig
import org.swatonly.launcher.R
import org.swatonly.launcher.apps.AppRepository
import org.swatonly.launcher.apps.IconLoader
import org.swatonly.launcher.linux.LinuxService
import org.swatonly.launcher.linux.PacmanConf
import org.swatonly.launcher.linux.Session
import org.swatonly.launcher.linux.Tweaks
import org.swatonly.launcher.term.TerminalActivity
import org.swatonly.launcher.theme.SwayTheme
import java.io.File

/**
 * Everything this install can set up: the manjaro-sway themes, the tweaks
 * found in the Manjaro install, the bar and launcher, how Linux windows
 * behave, and the proot side itself.
 */
class SettingsActivity : Activity() {

    private val app get() = App.instance
    private val prefs get() = app.prefs
    private val theme get() = app.themes.current()

    private val stack = ArrayList<String>()
    private lateinit var title: TextView
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = theme.background
        window.navigationBarColor = theme.background
        val root = Ui.vertical(this)
        root.setBackgroundColor(theme.background)

        val header = Ui.horizontal(this)
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10))
        header.setBackgroundColor(theme.surface)
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_arch)
            setColorFilter(getColor(R.color.arch_green))
            val p = Ui.dp(this@SettingsActivity, 8)
            setPadding(p, p, p, p)
            background = Ui.pressable(this@SettingsActivity)
            setOnClickListener { goBack() }
        }
        header.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        title = Ui.text(this, "", 18f, theme.text, bold = true).apply { setPadding(Ui.dp(this@SettingsActivity, 8), 0, 0, 0) }
        header.addView(title)
        root.addView(header, Ui.matchWrap())

        scroll = ScrollView(this)
        body = Ui.vertical(this)
        body.setPadding(0, 0, 0, Ui.dp(this, 32))
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        open("main")
        intent.getStringExtra(EXTRA_PAGE)?.let { open(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render(stack.last())
        } else finish()
    }

    private fun open(page: String) {
        stack += page
        render(page)
    }

    private fun refresh() = render(stack.last())

    private fun render(page: String) {
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        when (page) {
            "main" -> mainPage()
            "themes" -> themesPage()
            "tweaks" -> tweaksPage()
            "launcher" -> launcherPage()
            "windows" -> windowsPage()
            "linux" -> linuxPage()
            "about" -> aboutPage()
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun heading(s: String) = body.addView(Ui.heading(this, s))

    private fun note(s: String) = body.addView(Ui.text(this, s, 12f, theme.subtext).apply {
        setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 8))
    })

    private fun item(title: String, subtitle: String? = null, value: String? = null, onClick: (() -> Unit)? = null) =
        body.addView(Ui.row(this, title, subtitle, value, onClick))

    private fun nav(title: String, subtitle: String, page: String) = item("$title  ›", subtitle) { open(page) }

    private fun switch(title: String, subtitle: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        lateinit var row: LinearLayout
        row = Ui.row(this, title, subtitle, if (get()) "on" else "off") {
            set(!get())
            (row.findViewWithTag<TextView>("value"))?.text = if (get()) "on" else "off"
        }
        body.addView(row)
    }

    private fun choice(title: String, subtitle: String?, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit) {
        val label = options.firstOrNull { it.first == get() }?.second ?: get()
        item(title, subtitle, label) {
            Ui.choose(this, title, options, get()) { set(it); refresh() }
        }
    }

    private fun swatches(t: SwayTheme): View {
        val row = Ui.horizontal(this)
        for (c in listOf(t.background, t.surface, t.accent, t.text, t.error)) {
            val v = View(this)
            v.background = GradientDrawable().apply {
                setColor(c); cornerRadius = Ui.dpf(this@SettingsActivity, 4f)
                setStroke(1, (theme.text and 0x00FFFFFF) or 0x33000000)
            }
            row.addView(v, LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)).apply { rightMargin = Ui.dp(this@SettingsActivity, 4) })
        }
        return row
    }

    private fun bg(task: () -> Unit, done: (() -> Unit)? = null) {
        Thread {
            try { task() } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show() } }
            if (done != null) runOnUiThread(done)
        }.start()
    }

    // ---- pages ----------------------------------------------------------------

    private fun mainPage() {
        title.text = getString(R.string.settings)
        val rootFs = app.rootFs
        heading("Manjaro Sway")
        nav("Themes", "day ${prefs.dayTheme} · night ${prefs.nightTheme} · from ${if (rootFs.swayInstalled) "the install" else "manjaro-sway ${BuildConfig.MANJARO_SWAY_TAG}"}", "themes")
        nav("Tweaks", "session daemons, theme application, window manager and sway variables found in the install", "tweaks")
        heading("Launcher")
        nav("Bar & home screen", "bar icons, clock, wallpaper, menu", "launcher")
        nav("Linux windows", "apps tiled inside sway, scale, input", "windows")
        heading("System")
        nav("Linux (proot)", when {
            rootFs.swayInstalled -> "Manjaro Sway installed" + if (Session.swayRunning) " · sway running" else ""
            rootFs.installedMode != null -> "Manjaro base installed"
            rootFs.isExtracted -> "setup not finished"
            else -> "not installed (launcher only)"
        }, "linux")
        item("Default home app", "use Swatonly when pressing Home") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        item("Run setup choice", "launcher only / launcher + sway") {
            finish()
            startActivity(Intent(this, LauncherActivity::class.java)
                .putExtra(LauncherActivity.EXTRA_SETUP_CHOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        nav("About", "version, manjaro-sway release, licences", "about")
    }

    private fun themesPage() {
        title.text = "Themes"
        heading("Active")
        item("Day / night", "theme-toggle.sh: switch between the two themes", prefs.activeVariant) {
            prefs.activeVariant = if (prefs.activeVariant == "night") "day" else "night"
            themeChanged()
        }
        heading("Themes from the manjaro install")
        note(if (app.rootFs.swayInstalled) "Read from /usr/share/sway/themes in the Manjaro install." else "Bundled from manjaro-sway ${BuildConfig.MANJARO_SWAY_TAG} (install Sway to read them from the system).")
        for (name in app.themes.names()) {
            val t = app.themes.load(name) ?: continue
            val marks = buildString {
                if (name == prefs.dayTheme) append("☀ day ")
                if (name == prefs.nightTheme) append("☾ night")
            }.trim()
            val row = Ui.row(this, t.displayName, "${t.gtkTheme.ifEmpty { "gtk default" }} · ${t.iconTheme}", marks.ifEmpty { null }) {
                Ui.choose(this, t.displayName, listOf("day" to "Use as day theme", "night" to "Use as night theme", "packages" to "Install its packages (${t.packages.size})"), null) { what ->
                    when (what) {
                        "day" -> { prefs.dayTheme = name; themeChanged() }
                        "night" -> { prefs.nightTheme = name; themeChanged() }
                        "packages" -> installThemePackages(t)
                    }
                }
            }
            row.addView(swatches(t), 0)
            (row.getChildAt(1) as View).setPadding(Ui.dp(this, 12), 0, 0, 0)
            body.addView(row)
        }
        heading("Linux side")
        switch("Apply to sway & foot", "copy theme.conf and foot-theme.ini into ~/.config like manjaro-sway-theme does", { prefs.syncThemeToLinux }) {
            prefs.syncThemeToLinux = it
            if (it) bg({ Tweaks.applyTheme(this) })
        }
    }

    private fun themeChanged() {
        IconLoader.clear()
        app.themes.invalidate()
        if (prefs.syncThemeToLinux) bg({ Tweaks.applyTheme(this) })
        intent.putExtra(EXTRA_PAGE, stack.last())
        recreate()
    }

    private fun installThemePackages(t: SwayTheme) {
        if (!app.rootFs.isExtracted) { Toast.makeText(this, "Install Manjaro first", Toast.LENGTH_SHORT).show(); return }
        val pkgs = t.packages.joinToString(" ")
        TerminalActivity.start(this, "Theme ${t.name}", listOf("/bin/bash", "-lc",
            "pacman -S --needed $pkgs || echo 'some packages may only be in the AUR (yay)'; read -rp 'Press enter to close'"))
    }

    private fun tweaksPage() {
        title.text = "Tweaks"
        val rootFs = app.rootFs
        note(if (rootFs.isExtracted)
            "Discovered from /etc/sway/autostart and /etc/sway/definitions in the Manjaro install. Changes are written to ~/.config/sway/definitions.d and config.d, then sway reloads."
        else "Manjaro is not installed: these come from the bundled manjaro-sway ${BuildConfig.MANJARO_SWAY_TAG} and are applied once it is.")
        val tweaks = Tweaks.all(this)
        var section = ""
        for (t in tweaks) {
            if (t.section != section) { section = t.section; heading(section) }
            when (t) {
                is Tweaks.Tweak.Toggle -> switch(t.title, t.description, { Tweaks.value(t) == "on" }) { on ->
                    Tweaks.set(t, if (on) "on" else "off"); bg({ Tweaks.applyAndReload(this) })
                }
                is Tweaks.Tweak.Choice -> {
                    val opts = if (t.default.isEmpty()) listOf("" to "manjaro default") + t.options else t.options
                    choice(t.title, t.description, opts, { Tweaks.value(t) }) { v ->
                        Tweaks.set(t, v); bg({ Tweaks.applyAndReload(this) })
                    }
                }
                is Tweaks.Tweak.Text -> item(t.title, t.description, Tweaks.value(t).ifEmpty { "default" }) {
                    Ui.prompt(this, t.title, Tweaks.value(t), t.default) { v ->
                        Tweaks.set(t, v.trim()); bg({ Tweaks.applyAndReload(this) }); refresh()
                    }
                }
            }
        }
        heading("More")
        item("Reset all tweaks", "back to the manjaro-sway defaults") {
            Ui.confirm(this, "Reset tweaks", "Remove every override made here?") {
                prefs.tweakKeys().forEach { prefs.setTweak(it, null) }
                bg({ Tweaks.applyAndReload(this) }); refresh()
            }
        }
        if (rootFs.isExtracted) {
            item("Edit sway config", "~/.config/sway/config in a terminal editor") {
                TerminalActivity.start(this, "sway config", listOf("/bin/bash", "-lc",
                    "\${EDITOR:-\$(command -v helix || command -v nano || echo vi)} ~/.config/sway/config"))
            }
            item("Edit waybar style", "~/.config/waybar/style.css") {
                TerminalActivity.start(this, "waybar style", listOf("/bin/bash", "-lc",
                    "mkdir -p ~/.config/waybar; \${EDITOR:-\$(command -v helix || command -v nano || echo vi)} ~/.config/waybar/style.css"))
            }
            item("Reload sway", "swaymsg reload") { Session.reloadSway(this) }
        }
    }

    private fun launcherPage() {
        title.text = "Bar & home screen"
        heading("Bottom bar")
        choice("Icon size", null, listOf(24, 28, 32, 36, 40).map { "$it" to "$it dp" }, { "${prefs.barIconSizeDp}" }) { prefs.barIconSizeDp = it.toInt(); app.themes.invalidate() }
        choice("Bar opacity", null, listOf(60, 75, 85, 95, 100).map { "$it" to "$it %" }, { "${prefs.barOpacity}" }) { prefs.barOpacity = it.toInt(); app.themes.invalidate() }
        switch("24-hour clock", null, { prefs.clock24h }) { prefs.clock24h = it; app.themes.invalidate() }
        switch("Battery module", null, { prefs.showBattery }) { prefs.showBattery = it; app.themes.invalidate() }
        switch("Open Linux windows on the bar", "like waybar's taskbar", { prefs.showWindowsOnBar }) { prefs.showWindowsOnBar = it; app.themes.invalidate() }
        item("Clear the bar", "${prefs.pinned.size} pinned apps") { prefs.pinned = emptyList(); app.themes.invalidate(); refresh() }
        heading("Home screen")
        choice("Wallpaper", null, LauncherActivity.WALLPAPERS, { prefs.wallpaper }) { prefs.wallpaper = it; app.themes.invalidate() }
        switch("Info widget", "clock, release, Linux status and quick actions", { prefs.showInfoWidget }) { prefs.showInfoWidget = it; app.themes.invalidate() }
        heading("My apps (menu)")
        switch("Grid layout", "rofi list when off", { prefs.drawerGrid }) { prefs.drawerGrid = it; app.themes.invalidate() }
        switch("Android apps", null, { prefs.showAndroidApps }) { prefs.showAndroidApps = it; AppRepository.reload(this) }
        switch("Linux apps", "desktop entries of the Manjaro install", { prefs.showLinuxApps }) { prefs.showLinuxApps = it; AppRepository.reload(this) }
        switch("CLI / TUI apps", "Terminal=true entries (btop, pulsemixer, bluetuith, pacseek…)", { prefs.showCliApps }) { prefs.showCliApps = it; AppRepository.reload(this) }
        item("Reload app list", null) { IconLoader.clear(); AppRepository.reload(this); Toast.makeText(this, "Reloading…", Toast.LENGTH_SHORT).show() }
        heading("Start")
        switch("Ask launcher only / launcher + sway on every start", null, { prefs.askSetupEveryStart }) { prefs.askSetupEveryStart = it }
        switch("Start sway with \"Launcher + Sway\"", "otherwise only the compositor starts", { prefs.autostartSway }) { prefs.autostartSway = it }
    }

    private fun windowsPage() {
        title.text = "Linux windows"
        heading("Window manager")
        switch("Open Linux apps inside sway", "sway tiles them like on the desktop; off: each app fills the screen on its own", { prefs.appsInSway }) { prefs.appsInSway = it }
        if (!app.rootFs.swayInstalled) note("Sway is not installed yet: Settings › Linux › Install Manjaro Sway adds the full sway tiling window manager.")
        heading("Display")
        choice("Scale (HiDPI)", "wl_output scale; restart the Linux session to apply",
            listOf("1" to "1×", "2" to "2×", "3" to "3×", "4" to "4×"), { "${prefs.outputScale}" }) { prefs.outputScale = it.toInt() }
        heading("Input")
        choice("Touch input", "pointer: tap = click, long press = right click, two fingers = scroll",
            listOf("pointer" to "emulate a mouse", "touch" to "send touch events"), { prefs.touchMode }) { prefs.touchMode = it }
        switch("Extra keys row", "Esc, Tab, Ctrl, Alt, Super, arrows under each window", { prefs.showExtraKeys }) { prefs.showExtraKeys = it }
        switch("Open CLI / TUI apps in foot", "instead of the built-in terminal (needs foot)", { prefs.cliInFoot }) { prefs.cliInFoot = it }
        item("Restart Linux session", "stops every Linux program and the compositor") {
            Ui.confirm(this, "Restart", "Close all Linux windows and programs?", "Restart") {
                Session.stopAll()
                LinuxService.start(this)
            }
        }
    }

    private fun linuxPage() {
        title.text = "Linux (proot)"
        val rootFs = app.rootFs
        heading("Status")
        val status = when {
            rootFs.swayInstalled -> "Manjaro Sway"
            rootFs.installedMode != null -> "Manjaro base"
            rootFs.isExtracted -> "rootfs unpacked, setup unfinished"
            else -> "not installed"
        }
        val installedAt = File(rootFs.dir, "etc/swatonly/installed-at").takeIf { it.isFile }?.readText()?.trim()
        item("Install", installedAt?.let { "since $it" }, status)
        val sizeRow = Ui.row(this, "Disk usage", rootFs.dir.path, "…")
        body.addView(sizeRow)
        if (rootFs.isExtracted) bg({ val mb = rootFs.sizeOnDiskMb(); runOnUiThread { sizeRow.findViewWithTag<TextView>("value")?.text = "$mb MB" } })
        else sizeRow.findViewWithTag<TextView>("value")?.text = "0 MB"
        item("Running", Session.runningNames().joinToString(", ").ifEmpty { "nothing" }, "${WaylandServer_windowCount()} windows")

        heading("Session")
        if (rootFs.swayInstalled) {
            item(if (Session.swayRunning) "Show Sway desktop" else "Start Sway desktop", "sway nested in the launcher's compositor") { Session.startSway(this) }
            if (Session.swayRunning) item("Stop sway", null) { Session.stopSway(); refresh() }
        }
        if (rootFs.isExtracted) {
            item("Terminal", "bash -l as root (proot)") { AppRepository.runAction(this, "terminal") }
            item("Update system", "pacman -Syu") { AppRepository.runAction(this, "update") }
            item("Install packages", "pacseek, or pacman -S") { AppRepository.runAction(this, "packages") }
        }
        item("Stop all Linux programs", null) { Session.stopAll(); LinuxService.running?.stopSelf(); refresh() }

        heading("Install")
        if (!rootFs.swayInstalled) item("Install Manjaro Sway", "launcher + sway") { InstallActivity.start(this, "sway") }
        if (rootFs.installedMode == null && !rootFs.isExtracted) item("Install Manjaro base only", "Linux apps and CLI tools, no sway") { InstallActivity.start(this, "base") }
        if (rootFs.isExtracted) item("Repair / re-run setup", "runs setup.sh ${rootFs.installedMode ?: "sway"} again") {
            InstallActivity.start(this, rootFs.installedMode ?: "sway")
        }
        item("Rootfs URL", "tar.gz / tar.xz; empty = ${rootFs.defaultSource().label}", prefs.rootfsUrl.ifEmpty { "default" }) {
            Ui.prompt(this, "Rootfs URL", prefs.rootfsUrl, "https://…/rootfs.tar.gz") { prefs.rootfsUrl = it.trim(); refresh() }
        }

        heading("proot")
        switch("Skip firmware & kernel modules", "NoExtract in pacman.conf: they are useless under proot", { prefs.skipFirmwareAndModules }) {
            prefs.skipFirmwareAndModules = it
            bg({ PacmanConf.patch(rootFs, it) })
        }
        switch("Share storage as /sdcard", null, { prefs.bindStorage }) { prefs.bindStorage = it }
        item("Extra bind mounts", "one per line, host:guest", prefs.extraBinds.lines().count { it.isNotBlank() }.toString()) {
            Ui.prompt(this, "Extra binds", prefs.extraBinds, "/storage/emulated/0/Download:/root/Downloads", multiLine = true) { prefs.extraBinds = it; refresh() }
        }
        switch("Emulate memfd with ashmem", "only if Linux apps fail to create shared memory", { prefs.ashmemMemfd }) { prefs.ashmemMemfd = it }

        heading("Logs")
        val logs = rootFs.logDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (logs.isEmpty()) note("No logs yet.")
        for (f in logs.take(20)) item(f.name, "${f.length() / 1024} KB") { showLog(f) }

        heading("Danger zone")
        if (rootFs.isExtracted) item("Delete the Manjaro install", "frees the disk space; the launcher keeps working") {
            Ui.confirm(this, "Delete Manjaro", "This removes ${rootFs.dir.path} and everything in it.", "Delete") {
                Session.stopAll()
                bg({ rootFs.delete() }) { AppRepository.reload(this); refresh() }
            }
        }
    }

    @Suppress("FunctionName")
    private fun WaylandServer_windowCount(): Int = org.swatonly.launcher.wayland.WaylandServer.instance?.windowCount() ?: 0

    private fun showLog(f: File) {
        val text = try { f.readText().takeLast(20000) } catch (e: Exception) { e.toString() }
        val tv = Ui.text(this, text, 11f, theme.text).apply {
            setTextIsSelectable(true)
            setPadding(Ui.dp(this@SettingsActivity, 12), 0, Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 12))
        }
        Ui.dialog(this, f.name, tv).show()
    }

    private fun aboutPage() {
        title.text = "About"
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_arch)
            setColorFilter(getColor(R.color.arch_green))
        }
        val head = Ui.vertical(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, Ui.dp(this@SettingsActivity, 24), 0, Ui.dp(this@SettingsActivity, 8)) }
        head.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)))
        head.addView(Ui.text(this, "Swatonly ${BuildConfig.VERSION_NAME}", 18f, theme.text, bold = true).apply { setPadding(0, Ui.dp(this@SettingsActivity, 12), 0, 0) })
        head.addView(Ui.text(this, "Android home screen launcher with the look of Manjaro Sway", 13f, theme.subtext))
        body.addView(head, Ui.matchWrap())
        heading("Built from")
        item("manjaro-sway", "github.com/manjaro-sway/manjaro-sway", BuildConfig.MANJARO_SWAY_TAG) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/manjaro-sway/manjaro-sway/releases/tag/${BuildConfig.MANJARO_SWAY_TAG}")))
        }
        item("proot", "Termux build, user-space chroot without root")
        item("terminal", "Termux terminal-view")
        heading("Licence of manjaro-sway")
        val license = try { assets.open("manjaro-sway/LICENSE").bufferedReader().use { it.readText() } } catch (_: Exception) { "MIT" }
        note(license)
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        fun intent(ctx: Context, page: String) = Intent(ctx, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}

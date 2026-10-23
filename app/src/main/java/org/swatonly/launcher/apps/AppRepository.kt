package org.swatonly.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.widget.Toast
import org.swatonly.launcher.App
import org.swatonly.launcher.linux.Session
import org.swatonly.launcher.term.TerminalActivity
import org.swatonly.launcher.ui.InstallActivity
import org.swatonly.launcher.ui.SettingsActivity
import java.io.File
import java.util.concurrent.Executors

/** Android apps + the desktop entries of the Manjaro install, as one menu. */
object AppRepository {

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun find(key: String): AppEntry? = apps.firstOrNull { it.key == key } ?: actions().firstOrNull { it.key == key }

    fun reload(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute {
            val list = ArrayList<AppEntry>()
            try { list += loadAndroid(app) } catch (_: Exception) {}
            try { list += loadLinux() } catch (_: Exception) {}
            list += actions()
            list.sortBy { it.label.lowercase() }
            main.post {
                apps = list
                listeners.toList().forEach { it() }
            }
        }
    }

    private fun loadAndroid(ctx: Context): List<AppEntry> {
        val la = ctx.getSystemService(LauncherApps::class.java)
        val um = ctx.getSystemService(UserManager::class.java)
        val out = ArrayList<AppEntry>()
        for (user in um.userProfiles) {
            val serial = um.getSerialNumberForUser(user)
            for (info in la.getActivityList(null, user)) {
                if (info.componentName.packageName == ctx.packageName) continue
                val cats = mutableListOf(MenuCategory.ANDROID)
                androidCategory(info.applicationInfo)?.let { cats += it }
                out += AppEntry.Android(info.componentName, info.label.toString(), cats, serial)
            }
        }
        return out
    }

    private fun androidCategory(ai: ApplicationInfo): String? = when (ai.category) {
        ApplicationInfo.CATEGORY_GAME -> "Games"
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Multimedia"
        ApplicationInfo.CATEGORY_IMAGE -> "Graphics"
        ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_NEWS -> "Internet"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Office"
        ApplicationInfo.CATEGORY_MAPS -> "Accessories"
        else -> null
    }

    private fun loadLinux(): List<AppEntry> {
        val rootFs = App.instance.rootFs
        if (!rootFs.isExtracted) return emptyList()
        val byId = LinkedHashMap<String, AppEntry.Linux>()
        // later directories override earlier ones, like XDG_DATA_DIRS precedence
        for (dir in listOf("/usr/share/applications", "/usr/local/share/applications", "/root/.local/share/applications")) {
            val host = rootFs.resolve(dir) ?: continue
            if (!host.isDirectory) continue
            host.walkTopDown().maxDepth(3).filter { it.name.endsWith(".desktop") }.forEach { f ->
                val id = f.relativeTo(host).path.replace(File.separatorChar, '-')
                val text = try {
                    (if (f.isFile) f else rootFs.resolve("$dir/${f.relativeTo(host).path}"))?.readText()
                } catch (_: Exception) { null } ?: return@forEach
                val entry = DesktopEntry.parse(id, text)
                if (entry == null) byId.remove(id) else byId[id] = withMenuCategories(entry)
            }
        }
        return byId.values.toList()
    }

    private fun withMenuCategories(e: AppEntry.Linux): AppEntry.Linux {
        val cats = MenuCategory.fromFreedesktop(e.categories).toMutableList()
        if (e.terminal || "ConsoleOnly" in e.categories) cats.add(0, MenuCategory.CLI)
        return e.copy(categories = cats)
    }

    fun actions(): List<AppEntry.Action> {
        val rootFs = App.instance.rootFs
        val out = ArrayList<AppEntry.Action>()
        out += AppEntry.Action("menu", "System menu", if (rootFs.omarchyInstalled) "Omarchy menu" else "Manjaro Sway menu")
        out += AppEntry.Action("terminal", "Terminal", "Manjaro shell (built-in terminal)")
        if (rootFs.swayInstalled) out += AppEntry.Action("sway", "Desktop", if (rootFs.omarchyInstalled) "Manjaro Sway + Omarchy as one window" else "The full Manjaro Sway session as one window")
        if (!rootFs.swayInstalled) out += AppEntry.Action("install", "Install Manjaro Sway", "Download Manjaro and install sway under proot")
        if (!rootFs.omarchyInstalled) out += AppEntry.Action("install-omarchy", "Install Omarchy", "Manjaro Sway plus Omarchy's menu, themes and tools")
        if (rootFs.isExtracted) {
            out += AppEntry.Action("update", "Update system", "pacman -Syu")
            out += AppEntry.Action("packages", "Install packages", "pacman -S in a terminal")
        }
        out += AppEntry.Action("settings", "Launcher settings", "Themes, tweaks, bar and Linux options")
        return out
    }

    /** Linux apps that run in a terminal: everything Terminal=true. */
    fun isCli(e: AppEntry) = e is AppEntry.Linux && e.terminal

    fun launch(ctx: Context, e: AppEntry) {
        when (e) {
            is AppEntry.Android -> {
                if (App.instance.prefs.androidSplitScreen && ctx is android.app.Activity && ctx.isInMultiWindowMode) {
                    launchAdjacent(ctx, e)
                    return
                }
                val la = ctx.getSystemService(LauncherApps::class.java)
                val um = ctx.getSystemService(UserManager::class.java)
                val user = um.getUserForSerialNumber(e.userSerial) ?: android.os.Process.myUserHandle()
                try { la.startMainActivity(e.component, user, null, null) } catch (ex: Exception) {
                    Toast.makeText(ctx, "Cannot open ${e.label}", Toast.LENGTH_SHORT).show()
                }
            }
            is AppEntry.Linux -> launchLinux(ctx, e)
            is AppEntry.Action -> runAction(ctx, e.action)
        }
    }

    /** Opens an Android app next to the one on screen (split screen), where the device allows it. */
    fun launchAdjacent(ctx: Context, e: AppEntry.Android) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { launch(ctx, e) }
    }

    private fun launchLinux(ctx: Context, e: AppEntry.Linux) {
        val cmd = DesktopEntry.shellCommand(e.exec)
        val rootFs = App.instance.rootFs
        if (e.terminal) {
            if (App.instance.prefs.cliInFoot && rootFs.has("/usr/bin/foot")) {
                Session.launch(ctx, e.label, "exec foot --app-id=${e.id.removeSuffix(".desktop")} -- bash -lc ${org.swatonly.launcher.linux.Proot.quote(cmd)}")
            } else {
                TerminalActivity.start(ctx, e.label, listOf("/bin/bash", "-lc", cmd))
            }
            return
        }
        Toast.makeText(ctx, "Starting ${e.label}…", Toast.LENGTH_SHORT).show()
        Session.launch(ctx, e.label, "exec $cmd")
    }

    fun runAction(ctx: Context, action: String) {
        when (action) {
            "terminal" -> {
                if (!App.instance.rootFs.isExtracted) {
                    Toast.makeText(ctx, "Install Manjaro first (Settings > Linux)", Toast.LENGTH_LONG).show()
                    InstallActivity.start(ctx, "base")
                } else TerminalActivity.start(ctx, "Terminal", listOf("/bin/bash", "-l"))
            }
            "sway" -> Session.startSway(ctx)
            "install" -> InstallActivity.start(ctx, "sway")
            "install-omarchy" -> InstallActivity.start(ctx, "omarchy")
            "menu" -> ctx.startActivity(Intent(ctx, org.swatonly.launcher.ui.LauncherActivity::class.java)
                .putExtra(org.swatonly.launcher.ui.LauncherActivity.EXTRA_MENU, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "update" -> TerminalActivity.start(ctx, "System update", listOf("/bin/bash", "-lc", "pacman -Syu; echo; read -rp 'Press enter to close'"))
            "packages" -> TerminalActivity.start(ctx, "Packages", listOf("/bin/bash", "-lc",
                "command -v pacseek >/dev/null && exec pacseek; read -rp 'Packages to install: ' p; pacman -S --needed \$p; read -rp 'Press enter to close'"))
            "settings" -> ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Desktop entry for a Wayland app_id, for window titles and icons. */
    fun linuxEntryForAppId(appId: String): AppEntry.Linux? {
        if (appId.isEmpty()) return null
        val linux = apps.filterIsInstance<AppEntry.Linux>()
        return linux.firstOrNull { it.id.removeSuffix(".desktop").equals(appId, true) }
            ?: linux.firstOrNull { it.startupWmClass.equals(appId, true) }
            ?: linux.firstOrNull { it.id.removeSuffix(".desktop").substringAfterLast('.').equals(appId.substringAfterLast('.'), true) }
    }
}

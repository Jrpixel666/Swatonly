package org.swatonly.launcher.theme

import android.content.Context
import org.swatonly.launcher.App
import java.io.File

/**
 * Themes come from the Manjaro install (/usr/share/sway/themes inside the
 * rootfs) when it exists, and from the manjaro-sway release bundled in the APK
 * otherwise. Anything installed later (e.g. a theme package) shows up too.
 */
class ThemeRepository(private val context: Context) {

    private val listeners = mutableListOf<() -> Unit>()
    private var cached: SwayTheme? = null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun rootfsThemeDir(): File = File(App.instance.rootFs.dir, "usr/share/sway/themes")

    /** Omarchy's themes in the install, then the user's own (~/.config/omarchy/themes). */
    private fun omarchyThemeDirs(): List<File> = listOf(
        File(App.instance.rootFs.dir, "usr/share/omarchy/themes"),
        File(App.instance.rootFs.dir, "root/.config/omarchy/themes"),
    )

    fun names(): List<String> {
        val set = sortedSetOf<String>()
        context.assets.list(ASSET_DIR)?.let { set.addAll(it) }
        rootfsThemeDir().listFiles()?.filter { File(it, "theme.conf").isFile }?.forEach { set.add(it.name) }
        return set.toList() + omarchyNames()
    }

    fun omarchyNames(): List<String> {
        val set = sortedSetOf<String>()
        context.assets.list(OMARCHY_ASSET_DIR)?.let { set.addAll(it) }
        for (d in omarchyThemeDirs()) d.listFiles()?.filter { File(it, "colors.toml").isFile }?.forEach { set.add(it.name) }
        return set.map { OmarchyTheme.PREFIX + it }
    }

    private fun omarchyToml(name: String): Pair<String, String>? {
        val short = name.removePrefix(OmarchyTheme.PREFIX)
        for (d in omarchyThemeDirs().reversed()) {
            val f = File(d, "$short/colors.toml")
            if (f.isFile) return f.readText() to "rootfs"
        }
        return asset("$OMARCHY_ASSET_DIR/$short/colors.toml")?.let { it to "bundled" }
    }

    fun load(name: String): SwayTheme? {
        if (OmarchyTheme.isOmarchy(name)) {
            val (toml, src) = omarchyToml(name) ?: return null
            return OmarchyTheme.toSwayTheme(name, toml, src)
        }
        val dir = File(rootfsThemeDir(), name)
        if (File(dir, "theme.conf").isFile) {
            return SwayTheme.parse(
                name,
                File(dir, "theme.conf").readText(),
                File(dir, "foot-theme.ini").takeIf { it.isFile }?.readText(),
                File(dir, "packages").takeIf { it.isFile }?.readText(),
                "rootfs",
            )
        }
        return try {
            val conf = asset("$ASSET_DIR/$name/theme.conf") ?: return null
            SwayTheme.parse(name, conf, asset("$ASSET_DIR/$name/foot-theme.ini"), asset("$ASSET_DIR/$name/packages"), "bundled")
        } catch (_: Exception) { null }
    }

    /** Raw file of a theme, for copying into ~/.config the way manjaro-sway-theme does. */
    fun rawFile(name: String, file: String): String? {
        if (OmarchyTheme.isOmarchy(name)) {
            val p = OmarchyTheme.parseToml(omarchyToml(name)?.first ?: return null)
            return when (file) {
                "theme.conf" -> OmarchyTheme.themeConf(name.removePrefix(OmarchyTheme.PREFIX), p)
                "foot-theme.ini" -> OmarchyTheme.footIni(p)
                else -> null
            }
        }
        val f = File(rootfsThemeDir(), "$name/$file")
        if (f.isFile) return f.readText()
        return asset("$ASSET_DIR/$name/$file")
    }

    private fun asset(path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

    fun current(): SwayTheme {
        cached?.let { return it }
        val prefs = App.instance.prefs
        val name = if (prefs.activeVariant == "night") prefs.nightTheme else prefs.dayTheme
        val t = load(name) ?: load("matcha-green") ?: SwayTheme.parse("matcha-green", "", null, null, "bundled")
        cached = t
        return t
    }

    fun invalidate() {
        cached = null
        listeners.toList().forEach { it() }
    }

    companion object {
        const val ASSET_DIR = "manjaro-sway/themes"
        const val OMARCHY_ASSET_DIR = "omarchy/themes"
    }
}

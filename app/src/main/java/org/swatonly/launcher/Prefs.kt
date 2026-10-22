package org.swatonly.launcher

import android.content.Context
import android.content.SharedPreferences

/** Typed access to every launcher setting. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("swatonly", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun int(key: String, def: Int) = sp.getInt(key, def)

    // --- setup ---
    var askSetupEveryStart: Boolean
        get() = bool("ask_setup_every_start", true)
        set(v) = sp.edit().putBoolean("ask_setup_every_start", v).apply()

    /** "launcher" or "sway" — what the user picked last time. */
    var lastSetupChoice: String
        get() = str("last_setup_choice", "")
        set(v) = sp.edit().putString("last_setup_choice", v).apply()

    // --- theme ---
    var dayTheme: String
        get() = str("day_theme", "matcha-green")
        set(v) = sp.edit().putString("day_theme", v).apply()
    var nightTheme: String
        get() = str("night_theme", "catppuccin-mocha")
        set(v) = sp.edit().putString("night_theme", v).apply()
    /** "day", "night" */
    var activeVariant: String
        get() = str("active_variant", "day")
        set(v) = sp.edit().putString("active_variant", v).apply()
    var syncThemeToLinux: Boolean
        get() = bool("sync_theme_linux", true)
        set(v) = sp.edit().putBoolean("sync_theme_linux", v).apply()

    // --- launcher look ---
    /** "generated", "gruvbox-dark", "gruvbox-light", "solid" */
    var wallpaper: String
        get() = str("wallpaper", "generated")
        set(v) = sp.edit().putString("wallpaper", v).apply()
    var barIconSizeDp: Int
        get() = int("bar_icon_size", 28)
        set(v) = sp.edit().putInt("bar_icon_size", v).apply()
    var barOpacity: Int
        get() = int("bar_opacity", 100)
        set(v) = sp.edit().putInt("bar_opacity", v).apply()
    var clock24h: Boolean
        get() = bool("clock_24h", true)
        set(v) = sp.edit().putBoolean("clock_24h", v).apply()
    var showBattery: Boolean
        get() = bool("show_battery", true)
        set(v) = sp.edit().putBoolean("show_battery", v).apply()
    var showWindowsOnBar: Boolean
        get() = bool("show_windows_on_bar", true)
        set(v) = sp.edit().putBoolean("show_windows_on_bar", v).apply()
    var showInfoWidget: Boolean
        get() = bool("show_info_widget", true)
        set(v) = sp.edit().putBoolean("show_info_widget", v).apply()
    var drawerGrid: Boolean
        get() = bool("drawer_grid", false)
        set(v) = sp.edit().putBoolean("drawer_grid", v).apply()
    var showAndroidApps: Boolean
        get() = bool("show_android_apps", true)
        set(v) = sp.edit().putBoolean("show_android_apps", v).apply()
    var showLinuxApps: Boolean
        get() = bool("show_linux_apps", true)
        set(v) = sp.edit().putBoolean("show_linux_apps", v).apply()
    var showCliApps: Boolean
        get() = bool("show_cli_apps", true)
        set(v) = sp.edit().putBoolean("show_cli_apps", v).apply()

    /** Apps pinned to the bottom bar, as AppEntry keys, in order. */
    var pinned: List<String>
        get() = str("pinned", "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("pinned", v.joinToString("\n")).apply()

    // --- linux / proot ---
    var rootfsUrl: String
        get() = str("rootfs_url", "")
        set(v) = sp.edit().putString("rootfs_url", v).apply()
    var skipFirmwareAndModules: Boolean
        get() = bool("skip_firmware_modules", true)
        set(v) = sp.edit().putBoolean("skip_firmware_modules", v).apply()
    var bindStorage: Boolean
        get() = bool("bind_storage", true)
        set(v) = sp.edit().putBoolean("bind_storage", v).apply()
    var extraBinds: String
        get() = str("extra_binds", "")
        set(v) = sp.edit().putString("extra_binds", v).apply()
    var ashmemMemfd: Boolean
        get() = bool("ashmem_memfd", false)
        set(v) = sp.edit().putBoolean("ashmem_memfd", v).apply()

    // --- windows ---
    var windowPerTask: Boolean
        get() = bool("window_per_task", true)
        set(v) = sp.edit().putBoolean("window_per_task", v).apply()
    var outputScale: Int
        get() = int("output_scale", 2)
        set(v) = sp.edit().putInt("output_scale", v).apply()
    /** "pointer" (touch emulates a mouse) or "touch" (wl_touch) */
    var touchMode: String
        get() = str("touch_mode", "pointer")
        set(v) = sp.edit().putString("touch_mode", v).apply()
    var cliInFoot: Boolean
        get() = bool("cli_in_foot", false)
        set(v) = sp.edit().putBoolean("cli_in_foot", v).apply()
    var autostartSway: Boolean
        get() = bool("autostart_sway", true)
        set(v) = sp.edit().putBoolean("autostart_sway", v).apply()
    var showExtraKeys: Boolean
        get() = bool("show_extra_keys", true)
        set(v) = sp.edit().putBoolean("show_extra_keys", v).apply()

    // --- sway tweaks (written into the rootfs) ---
    fun tweak(key: String, def: String): String = str("tweak_$key", def)
    fun setTweak(key: String, value: String?) {
        if (value == null) sp.edit().remove("tweak_$key").apply()
        else sp.edit().putString("tweak_$key", value).apply()
    }
    fun tweakKeys(): List<String> = sp.all.keys.filter { it.startsWith("tweak_") }.map { it.removePrefix("tweak_") }
}

package org.swatonly.launcher

/**
 * Which spin of the launcher this APK is (the "spin" product flavour):
 * the Manjaro Sway spin offers launcher only / launcher + Manjaro Sway,
 * the Omarchy spin adds Omarchy on top of Manjaro Sway.
 */
object Spin {
    val omarchy: Boolean = BuildConfig.SPIN == "omarchy"

    /** setup.sh mode of the full install this spin offers. */
    val desktopMode: String = if (omarchy) "omarchy" else "sway"

    /** What "Launcher + …" installs, for labels. */
    val desktopName: String = if (omarchy) "Omarchy" else "Manjaro Sway"

    /** True once the full install of this spin is in the rootfs. */
    fun desktopInstalled(): Boolean {
        val rootFs = App.instance.rootFs
        return if (omarchy) rootFs.omarchyInstalled else rootFs.swayInstalled
    }
}

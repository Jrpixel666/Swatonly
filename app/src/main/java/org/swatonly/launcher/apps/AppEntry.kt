package org.swatonly.launcher.apps

import android.content.ComponentName

/** Something the launcher can start: an Android activity or a Linux desktop entry. */
sealed class AppEntry {
    abstract val key: String
    abstract val label: String
    abstract val categories: List<String>
    open val description: String = ""

    data class Android(
        val component: ComponentName,
        override val label: String,
        override val categories: List<String>,
        val userSerial: Long = 0,
    ) : AppEntry() {
        override val key = "android:${component.flattenToShortString()}"
    }

    data class Linux(
        /** desktop file id, e.g. "foot.desktop" */
        val id: String,
        override val label: String,
        val exec: String,
        val icon: String?,
        val terminal: Boolean,
        override val categories: List<String>,
        override val description: String,
        val startupWmClass: String?,
        val keywords: List<String>,
    ) : AppEntry() {
        override val key = "linux:$id"
    }

    /** Built-in launcher actions (terminal, sway, settings...). */
    data class Action(
        val action: String,
        override val label: String,
        override val description: String,
    ) : AppEntry() {
        override val key = "action:$action"
        override val categories = listOf(MenuCategory.SYSTEM)
    }
}

/**
 * Menu categories, after the freedesktop main categories the Manjaro desktop
 * menu (rofi drun, pcmanfm-qt) groups by, plus the edition's CLI/TUI tools.
 */
object MenuCategory {
    const val ALL = "All"
    const val PINNED = "Pinned"
    const val ANDROID = "Android"
    const val CLI = "CLI / TUI"
    const val SYSTEM = "System"

    /** freedesktop main category -> menu label, in menu order */
    val MAIN = linkedMapOf(
        "Utility" to "Accessories",
        "Development" to "Development",
        "Education" to "Education",
        "Game" to "Games",
        "Graphics" to "Graphics",
        "Network" to "Internet",
        "AudioVideo" to "Multimedia",
        "Audio" to "Multimedia",
        "Video" to "Multimedia",
        "Office" to "Office",
        "Science" to "Science",
        "Settings" to "Settings",
        "System" to "System",
    )

    val ORDER = listOf(ALL, PINNED, ANDROID, CLI, "Accessories", "Development", "Education", "Games", "Graphics",
        "Internet", "Multimedia", "Office", "Science", "Settings", SYSTEM, "Other")

    fun fromFreedesktop(cats: List<String>): List<String> {
        val out = cats.mapNotNull { MAIN[it] }.distinct()
        return out.ifEmpty { listOf("Other") }
    }
}

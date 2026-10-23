package org.swatonly.launcher.linux

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The Manjaro rootfs that proot runs, plus the host-side directories shared
 * with it. All paths here are host paths unless named "guest".
 */
class RootFs(private val context: Context) {

    /** The guest "/" */
    val dir: File get() = File(context.filesDir, "manjaro")

    /** Host side of the guest XDG_RUNTIME_DIR (/run/user/0): the Wayland socket lives here. */
    val runDir: File get() = File(context.filesDir, "run").apply { mkdirs() }

    /** proot's own temporary directory (PROOT_TMP_DIR). */
    val prootTmp: File get() = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    val logDir: File get() = File(context.filesDir, "logs").apply { mkdirs() }

    val isExtracted: Boolean
        get() = File(dir, "usr/bin/bash").exists() || File(dir, "usr/bin/sh").exists()

    /** "sway", "omarchy" or "base" once setup.sh finished, null otherwise. */
    val installedMode: String?
        get() = File(dir, "etc/swatonly/installed").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    /** The Manjaro Sway desktop: installed by both "sway" and "omarchy" setups. */
    val swayInstalled: Boolean get() = installedMode.let { it == "sway" || it == "omarchy" } && File(dir, "usr/bin/sway").exists()

    val omarchyInstalled: Boolean get() = installedMode == "omarchy" && File(dir, "usr/share/omarchy/bin").isDirectory

    val hyprlandInstalled: Boolean get() = File(dir, "usr/bin/Hyprland").exists()

    /** Which window manager the running session uses ("sway", "hyprland"), from the session's marker. */
    val runningWm: String? get() = File(runDir, "swatonly-wm").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    fun has(guestPath: String): Boolean = resolve(guestPath)?.exists() == true

    /**
     * Maps a guest path to the host file, following symlinks the way the guest
     * sees them (absolute targets point into the rootfs, not the host).
     */
    fun resolve(guestPath: String, depth: Int = 0): File? {
        if (depth > 16) return null
        val parts = guestPath.split('/').filter { it.isNotEmpty() && it != "." }
        var cur = ""
        for ((i, part) in parts.withIndex()) {
            if (part == "..") { cur = cur.substringBeforeLast('/', ""); continue }
            val candidate = "$cur/$part"
            val host = File(dir, candidate.removePrefix("/"))
            val target = readLink(host)
            cur = if (target != null) {
                val abs = if (target.startsWith("/")) target else "$cur/$target"
                val rest = parts.drop(i + 1).joinToString("/")
                return resolve(if (rest.isEmpty()) abs else "$abs/$rest", depth + 1)
            } else candidate
        }
        return File(dir, cur.removePrefix("/"))
    }

    private fun readLink(f: File): String? = try {
        android.system.Os.readlink(f.path)
    } catch (_: Exception) { null }

    fun guestFile(guestPath: String): File = File(dir, guestPath.removePrefix("/"))

    /** Default rootfs for this device's CPU. */
    data class Source(val label: String, val githubRepo: String, val assetMatch: Regex, val fallbackUrl: String?)

    fun defaultSource(): Source {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> Source(
                "Manjaro ARM (aarch64)",
                "manjaro-arm/rootfs",
                Regex("""Manjaro-ARM-aarch64.*\.tar\.(gz|xz)$"""),
                "https://github.com/manjaro-arm/rootfs/releases/latest/download/Manjaro-ARM-aarch64-latest.tar.gz",
            )
            abi.startsWith("x86_64") -> Source(
                "Arch Linux (x86_64; Manjaro publishes no x86_64 rootfs)",
                "termux/proot-distro",
                Regex("""archlinux-x86_64.*\.tar\.(gz|xz)$"""),
                null,
            )
            else -> Source(
                "Arch Linux ARM (armv7; Manjaro ARM dropped 32-bit)",
                "termux/proot-distro",
                Regex("""archlinux-arm-.*\.tar\.(gz|xz)$"""),
                null,
            )
        }
    }

    fun sizeOnDiskMb(): Long {
        var total = 0L
        dir.walkTopDown().onEnter { !isSymlink(it) }.forEach { if (it.isFile && !isSymlink(it)) total += it.length() }
        return total / (1024 * 1024)
    }

    private fun isSymlink(f: File): Boolean = try {
        android.system.Os.lstat(f.path).st_mode and 0xF000 == 0xA000
    } catch (_: Exception) { false }

    /** Deletes the rootfs without following symlinks out of it. */
    fun delete() {
        deleteTree(dir)
    }

    private fun deleteTree(f: File) {
        if (!isSymlink(f) && f.isDirectory) {
            f.setWritable(true)
            f.listFiles()?.forEach { deleteTree(it) }
        }
        f.delete()
    }
}

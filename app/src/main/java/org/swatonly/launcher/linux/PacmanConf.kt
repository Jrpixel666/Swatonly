package org.swatonly.launcher.linux

import java.io.File

/**
 * Adjusts the rootfs' /etc/pacman.conf for proot:
 *  - NoExtract firmware and kernel modules: nothing under proot can load them,
 *    so pacman never unpacks them (this is what keeps linux-firmware & co. from
 *    eating hundreds of MB).
 *  - CheckSpace off: proot's fake mount table makes the check fail.
 *  - pacman >= 7: DisableSandbox and no DownloadUser, neither works without
 *    real root.
 */
object PacmanConf {
    private const val MARK = "# swatonly:"
    const val NO_EXTRACT = "NoExtract = usr/lib/firmware/* usr/lib/modules/* lib/firmware/* lib/modules/* boot/*"

    fun patch(rootFs: RootFs, skipFirmwareAndModules: Boolean) {
        val f = File(rootFs.dir, "etc/pacman.conf")
        if (!f.isFile) return
        val pacman7 = pacmanMajor(rootFs) >= 7
        // undo a previous patch first, so toggling the setting is clean
        val original = f.readLines().mapNotNull { line ->
            when {
                line.startsWith("#") && line.endsWith(" $MARK disabled") -> line.removePrefix("#").removeSuffix(" $MARK disabled")
                line.contains(MARK) -> null
                else -> line
            }
        }
        val out = ArrayList<String>()
        var inOptions = false
        for (line in original) {
            val t = line.trim()
            if (t.startsWith("[")) {
                inOptions = t == "[options]"
                out += line
                if (inOptions) {
                    if (skipFirmwareAndModules) {
                        out += "$MARK firmware and kernel modules are useless under proot, never unpack them"
                        out += "$NO_EXTRACT $MARK"
                    }
                    if (pacman7) out += "DisableSandbox $MARK proot cannot provide landlock/seccomp"
                }
                continue
            }
            if (inOptions && (t.startsWith("CheckSpace") || (pacman7 && (t.startsWith("DownloadUser") || t == "DisableSandbox")))) {
                out += "#$line $MARK disabled"
                continue
            }
            out += line
        }
        f.writeText(out.joinToString("\n", postfix = "\n"))
    }

    private fun pacmanMajor(rootFs: RootFs): Int {
        val local = File(rootFs.dir, "var/lib/pacman/local")
        val dir = local.listFiles()?.firstOrNull { it.name.matches(Regex("""pacman-\d.*""")) } ?: return 0
        return dir.name.removePrefix("pacman-").substringBefore('.').toIntOrNull() ?: 0
    }
}

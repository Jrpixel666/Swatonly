package org.swatonly.launcher.linux

import android.content.Context
import android.os.Environment
import org.swatonly.launcher.App
import java.io.File

/**
 * Builds proot command lines. Manjaro runs as a fake root (-0) without any
 * real privileges: proot (Termux's build, shipped as libproot.so) translates
 * paths and syscalls in user space.
 */
object Proot {
    const val GUEST_RUNTIME_DIR = "/run/user/0"
    const val WAYLAND_DISPLAY = "wayland-0"

    private fun nativeDir(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir)

    fun binary(ctx: Context) = File(nativeDir(ctx), "libproot.so")

    fun available(ctx: Context) = binary(ctx).exists()

    /** Environment for the proot process itself (host side). */
    fun hostEnv(ctx: Context): Map<String, String> {
        val rootFs = App.instance.rootFs
        val env = HashMap<String, String>()
        env["PROOT_TMP_DIR"] = rootFs.prootTmp.path
        env["PROOT_LOADER"] = File(nativeDir(ctx), "libproot-loader.so").path
        File(nativeDir(ctx), "libproot-loader32.so").takeIf { it.exists() }?.let { env["PROOT_LOADER_32"] = it.path }
        env["LD_LIBRARY_PATH"] = nativeDir(ctx).path
        env["HOME"] = ctx.filesDir.path
        return env
    }

    /** Environment inside the guest. */
    fun guestEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["SHELL"] = "/bin/bash"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "C.UTF-8"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin"
        env["TMPDIR"] = "/tmp"
        env["XDG_RUNTIME_DIR"] = GUEST_RUNTIME_DIR
        env["WAYLAND_DISPLAY"] = WAYLAND_DISPLAY
        env["XDG_SESSION_TYPE"] = "wayland"
        env["GDK_BACKEND"] = "wayland"
        env["QT_QPA_PLATFORM"] = "wayland;xcb"
        env["SDL_VIDEODRIVER"] = "wayland"
        env["MOZ_ENABLE_WAYLAND"] = "1"
        env["ELECTRON_OZONE_PLATFORM_HINT"] = "wayland"
        env["LIBGL_ALWAYS_SOFTWARE"] = "1"
        env["GSK_RENDERER"] = "cairo"
        env["NO_AT_BRIDGE"] = "1"
        env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=$GUEST_RUNTIME_DIR/bus"
        env.putAll(extra)
        return env
    }

    /**
     * Full argv: proot options, then `/usr/bin/env -i <guest env> <command>`
     * so nothing from the Android environment leaks into Manjaro.
     */
    fun command(ctx: Context, guestArgs: List<String>, extraEnv: Map<String, String> = emptyMap(), cwd: String = "/root"): List<String> {
        val prefs = App.instance.prefs
        val rootFs = App.instance.rootFs
        val r = rootFs.dir.path
        File(rootFs.dir, "tmp").mkdirs()
        File(rootFs.dir, "root").mkdirs()

        val cmd = ArrayList<String>()
        cmd += binary(ctx).path
        cmd += "--kill-on-exit"
        cmd += "--link2symlink"
        cmd += "--sysvipc"
        if (prefs.ashmemMemfd) cmd += "--ashmem-memfd"
        cmd += "-0"
        cmd += listOf("-r", r)
        cmd += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
        cmd += listOf("-b", "/dev/urandom:/dev/random")
        cmd += listOf("-b", "/proc/self/fd:/dev/fd")
        cmd += listOf("-b", "/proc/self/fd/0:/dev/stdin", "-b", "/proc/self/fd/1:/dev/stdout", "-b", "/proc/self/fd/2:/dev/stderr")
        cmd += listOf("-b", "$r/tmp:/dev/shm")
        cmd += listOf("-b", "${rootFs.runDir.path}:$GUEST_RUNTIME_DIR")
        for ((host, guest) in FakeProc.binds(rootFs)) cmd += listOf("-b", "$host:$guest")
        if (prefs.bindStorage) {
            val storage = Environment.getExternalStorageDirectory()
            if (storage.canRead()) cmd += listOf("-b", "${storage.path}:/sdcard")
        }
        for (line in prefs.extraBinds.lines()) {
            val b = line.trim()
            if (b.isNotEmpty() && !b.startsWith("#")) cmd += listOf("-b", b)
        }
        cmd += listOf("-w", cwd)
        cmd += "/usr/bin/env"
        cmd += "-i"
        for ((k, v) in guestEnv(extraEnv)) cmd += "$k=$v"
        cmd += guestArgs
        return cmd
    }

    /** Starts a guest command in the background, logging to logs/<name>.log. */
    fun spawn(ctx: Context, name: String, guestArgs: List<String>, extraEnv: Map<String, String> = emptyMap()): Process {
        val log = File(App.instance.rootFs.logDir, "$name.log")
        if (log.length() > 512 * 1024) log.delete()
        val pb = ProcessBuilder(command(ctx, guestArgs, extraEnv))
        pb.environment().clear()
        pb.environment().putAll(hostEnv(ctx))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.directory(ctx.filesDir)
        return pb.start()
    }

    /** Runs a guest command and waits, returning (exit code, output). */
    fun run(ctx: Context, guestArgs: List<String>, extraEnv: Map<String, String> = emptyMap(), timeoutSec: Long = 60): Pair<Int, String> {
        val pb = ProcessBuilder(command(ctx, guestArgs, extraEnv))
        pb.environment().clear()
        pb.environment().putAll(hostEnv(ctx))
        pb.redirectErrorStream(true)
        pb.directory(ctx.filesDir)
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        reader.start()
        val done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) p.destroy()
        reader.join(1000)
        return (if (done) p.exitValue() else -1) to out.toString()
    }

    /** Shell-quotes one argument for `bash -c`. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

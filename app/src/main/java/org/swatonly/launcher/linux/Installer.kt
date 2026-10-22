package org.swatonly.launcher.linux

import android.content.Context
import android.system.Os
import org.json.JSONObject
import org.swatonly.launcher.App
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and unpacks the rootfs, then stages everything setup.sh needs.
 * The package installation itself runs in a terminal (so pacman's output and
 * prompts stay visible): see [setupCommand].
 */
class Installer(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onStep(text: String)
        fun onProgress(done: Long, total: Long)
        fun onLog(line: String)
    }

    @Volatile
    var cancelled = false

    private val app = App.instance
    private val rootFs = app.rootFs
    private val prefs = app.prefs

    /** Runs everything up to (not including) setup.sh. Blocking, call off the main thread. */
    fun prepare(mode: String, freshRootfs: Boolean) {
        if (!Proot.available(ctx)) throw IOException("proot is missing from this APK (lib/${android.os.Build.SUPPORTED_ABIS[0]}/libproot.so)")

        if (freshRootfs || !rootFs.isExtracted) {
            val url = resolveUrl()
            listener.onLog("rootfs: $url")
            val archive = File(ctx.cacheDir, "rootfs.tar")
            listener.onStep("Downloading Manjaro rootfs")
            download(url, archive)
            check()
            listener.onStep("Unpacking rootfs (firmware and kernel modules are skipped)")
            if (rootFs.dir.exists()) rootFs.delete()
            extract(archive)
            archive.delete()
        }
        check()

        listener.onStep("Configuring the rootfs for proot")
        configure()
        listener.onStep("Staging manjaro-sway ${org.swatonly.launcher.BuildConfig.MANJARO_SWAY_TAG}")
        stagePayload()
        listener.onLog("ready to run setup.sh $mode")
    }

    private fun check() { if (cancelled) throw IOException("cancelled") }

    private fun resolveUrl(): String {
        prefs.rootfsUrl.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val src = rootFs.defaultSource()
        listener.onLog("source: ${src.label}")
        try {
            val json = httpGet("https://api.github.com/repos/${src.githubRepo}/releases/latest")
            val assets = JSONObject(json).getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (src.assetMatch.containsMatchIn(a.getString("name"))) return a.getString("browser_download_url")
            }
        } catch (e: Exception) {
            listener.onLog("release lookup failed: ${e.message}")
        }
        return src.fallbackUrl ?: throw IOException("no rootfs found for this CPU; set a rootfs URL in Settings > Linux")
    }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(8) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "swatonly-launcher")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                c.disconnect()
                u = URL(u, loc)
            } else return c
        }
        throw IOException("too many redirects")
    }

    private fun download(url: String, dest: File) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} downloading $url")
            val total = c.contentLengthLong
            var done = 0L
            var last = 0L
            c.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        check()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) { listener.onProgress(done, total); last = done }
                    }
                }
            }
            listener.onProgress(done, total)
        } finally { c.disconnect() }
    }

    private fun extract(archive: File) {
        val skipFw = prefs.skipFirmwareAndModules
        val skipped = listOf("usr/lib/firmware/", "usr/lib/modules/", "lib/firmware/", "lib/modules/", "boot/")
        var count = 0
        val total = archive.length()
        val counting = CountingInputStream(archive.inputStream())
        TarExtractor(
            rootFs.dir,
            filter = { path -> !(skipFw && skipped.any { path.startsWith(it) && path.length > it.length }) },
            onEntry = {
                count++
                if (count % 200 == 0) {
                    listener.onProgress(counting.count, total)
                    check()
                }
            },
        ).extract(counting)
        listener.onLog("unpacked $count entries")
    }

    private fun configure() {
        val d = rootFs.dir
        File(d, "etc").mkdirs()
        // resolv.conf is often a dangling symlink to systemd-resolved
        val resolv = File(d, "etc/resolv.conf")
        resolv.delete()
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(d, "etc/hosts").let { if (!it.isFile || it.length() == 0L) it.writeText("127.0.0.1 localhost\n::1 localhost\n") }
        File(d, "etc/hostname").writeText("manjaro-sway\n")
        // machine-id is needed by dbus
        File(d, "etc/machine-id").let { if (!it.isFile || it.length() < 32L) it.writeText(java.util.UUID.randomUUID().toString().replace("-", "") + "\n") }
        for (p in listOf("tmp", "root", "run/user", "var/tmp", "dev/shm")) File(d, p).mkdirs()
        try { Os.chmod(File(d, "tmp").path, "1777".toInt(8)) } catch (_: Exception) {}
        PacmanConf.patch(rootFs, prefs.skipFirmwareAndModules)
        FakeProc.binds(rootFs)
    }

    /** Copies the bundled manjaro-sway release and the proot helpers to /opt/swatonly. */
    fun stagePayload() = Companion.stagePayload(ctx)

    /** Guest argv for the terminal that runs setup.sh. */
    fun setupCommand(mode: String) = listOf("/bin/bash", "/opt/swatonly/setup.sh", mode)

    private class CountingInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }

    companion object {
        private val SCRIPTS = listOf("setup.sh", "systemctl", "swatonly-run", "sway-session.sh", "sway-run")
        private val FILES = listOf("profile.sh", "sway-proot.conf")

        fun stagePayload(ctx: Context) {
            val rootFs = App.instance.rootFs
            val opt = File(rootFs.dir, "opt/swatonly")
            opt.mkdirs()
            for (name in SCRIPTS + FILES) {
                val out = File(opt, name)
                ctx.assets.open("proot/$name").use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                try { Os.chmod(out.path, if (name in SCRIPTS) "755".toInt(8) else "644".toInt(8)) } catch (_: Exception) {}
            }
            // the manjaro-sway-settings payload, unpacked with its modes
            val ms = File(opt, "manjaro-sway")
            val payload = File(ms, "payload")
            if (payload.exists()) deleteTree(payload)
            payload.mkdirs()
            // aapt strips ".gz" from asset names (and stores them uncompressed), so accept every spelling
            val name = listOf("payload.tgz", "payload.tar", "payload.tar.gz").firstOrNull { n ->
                try { ctx.assets.open("manjaro-sway/$n").close(); true } catch (_: IOException) { false }
            } ?: throw IOException("manjaro-sway payload missing from the APK")
            ctx.assets.open("manjaro-sway/$name").use { TarExtractor(payload).extract(it) }
            for (f in listOf("TAG", "Packages-Desktop", "LICENSE")) {
                try {
                    ctx.assets.open("manjaro-sway/$f").use { input -> FileOutputStream(File(ms, f)).use { input.copyTo(it) } }
                } catch (_: IOException) {}
            }
            // keep the guest's helpers current after an APK update
            if (rootFs.installedMode != null) {
                File(rootFs.dir, "usr/local/bin").mkdirs()
                File(opt, "systemctl").copyTo(File(rootFs.dir, "usr/local/bin/systemctl"), overwrite = true)
                File(opt, "swatonly-run").copyTo(File(rootFs.dir, "usr/local/bin/swatonly-run"), overwrite = true)
                for (n in listOf("systemctl", "swatonly-run")) {
                    try { Os.chmod(File(rootFs.dir, "usr/local/bin/$n").path, "755".toInt(8)) } catch (_: Exception) {}
                }
                File(rootFs.dir, "etc/profile.d").mkdirs()
                File(opt, "profile.sh").copyTo(File(rootFs.dir, "etc/profile.d/swatonly.sh"), overwrite = true)
            }
        }

        private fun deleteTree(f: File) {
            val isLink = try { Os.lstat(f.path).st_mode and 0xF000 == 0xA000 } catch (_: Exception) { false }
            if (!isLink && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        }
    }
}

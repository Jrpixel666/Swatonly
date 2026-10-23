package org.swatonly.launcher.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.LruCache
import android.widget.ImageView
import com.caverock.androidsvg.SVG
import org.swatonly.launcher.App
import org.swatonly.launcher.R
import org.swatonly.launcher.theme.SwayTheme
import java.io.File
import java.util.concurrent.Executors

/**
 * Icons for Android apps (LauncherApps) and Linux apps (the rootfs icon
 * themes: the manjaro-sway theme's icon theme first, then Papirus and
 * hicolor; SVGs are rendered with AndroidSVG).
 */
object IconLoader {
    private val cache = LruCache<String, Bitmap>(300)
    private val misses = HashSet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, e: AppEntry, sizePx: Int, into: ImageView) {
        val key = "${e.key}@$sizePx"
        into.tag = key
        cache.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(placeholder(ctx, e, sizePx))
        val app = ctx.applicationContext
        executor.execute {
            val bmp = try { loadBitmap(app, e, sizePx) } catch (_: Throwable) { null }
            if (bmp != null) {
                cache.put(key, bmp)
                main.post { if (into.tag == key) into.setImageBitmap(bmp) }
            }
        }
    }

    fun loadSync(ctx: Context, e: AppEntry, sizePx: Int): Bitmap {
        val key = "${e.key}@$sizePx"
        cache.get(key)?.let { return it }
        val bmp = try { loadBitmap(ctx, e, sizePx) } catch (_: Throwable) { null } ?: letterBitmap(e.label, sizePx, e)
        cache.put(key, bmp)
        return bmp
    }

    fun clear() { cache.evictAll(); misses.clear() }

    private fun loadBitmap(ctx: Context, e: AppEntry, size: Int): Bitmap? = when (e) {
        is AppEntry.Android -> {
            val la = ctx.getSystemService(LauncherApps::class.java)
            val um = ctx.getSystemService(UserManager::class.java)
            val user = um.getUserForSerialNumber(e.userSerial) ?: android.os.Process.myUserHandle()
            val info = la.getActivityList(e.component.packageName, user).firstOrNull { it.componentName == e.component }
            info?.getBadgedIcon(0)?.let { toBitmap(it, size) }
        }
        is AppEntry.Linux -> e.icon?.let { linuxIcon(it, size) }
        is AppEntry.Action -> actionIcon(ctx, e.action, size)
    }

    private fun actionIcon(ctx: Context, action: String, size: Int): Bitmap? {
        val res = when (action) {
            "terminal", "update", "packages" -> R.drawable.ic_terminal
            "settings" -> R.drawable.ic_settings
            "menu", "install-omarchy" -> R.drawable.ic_monero
            else -> R.drawable.ic_arch
        }
        val d = ctx.getDrawable(res) ?: return null
        val theme = App.instance.themes.current()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surface }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, p)
        val pad = size / 5
        d.setTint(when (action) {
            "sway", "install" -> ctx.getColor(R.color.arch_green)
            "menu", "install-omarchy" -> ctx.getColor(R.color.monero_green)
            else -> theme.accent
        })
        d.setBounds(pad, pad, size - pad, size - pad)
        d.draw(c)
        return bmp
    }

    /** Resolves an icon name the way the freedesktop icon spec does (simplified). */
    fun linuxIcon(name: String, size: Int): Bitmap? {
        if (name in misses) return null
        val rootFs = App.instance.rootFs
        val file: File? = if (name.startsWith("/")) rootFs.resolve(name) else findThemedIcon(name)
        val bmp = file?.let { decode(it, size) }
        if (bmp == null) misses += name
        return bmp
    }

    private fun findThemedIcon(name: String): File? {
        val rootFs = App.instance.rootFs
        val themeIcons = App.instance.themes.current().iconTheme
        val themes = listOf(themeIcons, "Papirus-Dark-Maia", "Papirus-Dark", "Papirus", "breeze-dark", "breeze", "Adwaita", "hicolor").distinct()
        val sizes = listOf("64x64", "48x48", "scalable", "128x128", "256x256", "32x32")
        val sections = listOf("apps")
        for (t in themes) {
            val base = rootFs.resolve("/usr/share/icons/$t") ?: continue
            if (!base.isDirectory) continue
            for (s in sizes) {
                for (sec in sections) {
                    for (ext in listOf("png", "svg")) {
                        val f = File(base, "$s/$sec/$name.$ext")
                        if (f.exists()) return f
                        // some themes use <section>/<size>
                        val g = File(base, "$sec/$s/$name.$ext")
                        if (g.exists()) return g
                    }
                }
            }
        }
        for (ext in listOf("png", "svg", "xpm")) {
            rootFs.resolve("/usr/share/pixmaps/$name.$ext")?.takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private fun decode(f: File, size: Int): Bitmap? {
        if (!f.isFile) return null
        return if (f.name.endsWith(".svg")) {
            val svg = f.inputStream().use { SVG.getFromInputStream(it) }
            svg.documentWidth = size.toFloat()
            svg.documentHeight = size.toFloat()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bmp))
            bmp
        } else {
            BitmapFactory.decodeFile(f.path)?.let { Bitmap.createScaledBitmap(it, size, size, true) }
        }
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    private fun placeholder(ctx: Context, e: AppEntry, size: Int): Drawable =
        BitmapDrawable(ctx.resources, letterBitmap(e.label, size, e))

    /** Rounded tile with the first letter, in the current sway theme's colours. */
    fun letterBitmap(label: String, size: Int, e: AppEntry? = null): Bitmap {
        val theme = App.instance.themes.current()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SwayTheme.blend(theme.background, theme.accent, 0.35f) }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, bg)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.text
            textSize = size * 0.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val glyph = if ((e != null && AppRepository.isCli(e)) || label == ">_") ">_" else label.trim().take(1).uppercase()
        if (glyph.length > 1) tp.textSize = size * 0.36f
        val y = size / 2f - (tp.descent() + tp.ascent()) / 2f
        c.drawText(glyph, size / 2f, y, tp)
        return bmp
    }
}

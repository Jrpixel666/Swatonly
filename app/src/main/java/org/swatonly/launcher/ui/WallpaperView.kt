package org.swatonly.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import org.swatonly.launcher.App
import org.swatonly.launcher.theme.SwayTheme

/**
 * The manjaro-sway desktop background: generate-bg.sh's template
 * (manjarosway-scalable.svg) filled with the theme's accent, text and
 * background colours, or one of the manjaro-sway-wallpapers.
 */
class WallpaperView(context: Context) : View(context) {

    private var cache: Bitmap? = null
    private var cacheKey = ""

    fun invalidateWallpaper() {
        cacheKey = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val theme = App.instance.themes.current()
        val mode = App.instance.prefs.wallpaper
        if (mode == "system") return
        canvas.drawColor(theme.background)
        if (mode == "solid" || width == 0 || height == 0) return
        val key = "$mode:${theme.name}:$width:$height"
        if (key != cacheKey) {
            cache?.recycle()
            cache = try { render(mode, theme) } catch (_: Throwable) { null }
            cacheKey = key
        }
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun render(mode: String, theme: SwayTheme): Bitmap {
        val w = width.toFloat()
        val h = height.toFloat()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(theme.background)
        val opts = RenderOptions.create().viewPort(0f, 0f, w, h)
        val svg: SVG
        if (mode == "generated") {
            val template = context.assets.open("manjaro-sway/templates/manjarosway-scalable.svg").bufferedReader().use { it.readText() }
            val filled = template
                .replace("\${CROWN}", SwayTheme.hex(theme.accent))
                .replace("\${ROOT}", SwayTheme.hex(theme.text))
                .replace("\${BACKGROUND}", SwayTheme.hex(theme.background))
            svg = SVG.getFromString(filled)
            if (h > w) {
                // portrait: centre the crown/root logo instead of slicing the 16:9 artwork
                val vw = 1700f
                val vh = vw * h / w
                opts.viewBox(LOGO_CX - vw / 2, LOGO_CY - vh / 2, vw, vh)
            }
            opts.preserveAspectRatio(PreserveAspectRatio.FULLSCREEN)
        } else {
            val file = if (mode == "gruvbox-light") "gruvbox-light-manjarosway-scalable.svg" else "gruvbox-dark-manjarosway-scalable.svg"
            svg = context.assets.open("manjaro-sway/wallpapers/$file").use { SVG.getFromInputStream(it) }
            opts.preserveAspectRatio(PreserveAspectRatio.FULLSCREEN)
        }
        svg.renderToCanvas(c, opts)
        return bmp
    }

    companion object {
        // centre of the logo in manjarosway-scalable.svg (viewBox 3840x2160)
        private const val LOGO_CX = 2700f
        private const val LOGO_CY = 1130f
    }
}

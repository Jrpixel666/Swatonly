package org.swatonly.launcher.wayland

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.SurfaceHolder

/**
 * A Linux toplevel window. It is shown by its own [WindowActivity], i.e. its
 * Android activity; all of them share the "linux" task. Recents, split screen and freeform work on
 * Linux windows like on Android apps.
 */
class Window(val server: WaylandServer, val toplevel: XdgToplevelRes) {
    val id = server.nextWindowId()
    val client: Client get() = toplevel.client
    val surface: SurfaceRes get() = toplevel.xdg.surface

    /** The Android side, set while a WindowActivity shows this window. */
    @Volatile
    var host: Host? = null
        private set

    interface Host {
        val holder: SurfaceHolder
        fun onMetadataChanged()
        fun onWindowClosed()
        fun onMinimize()
    }

    private val popups = ArrayList<XdgPopupRes>()
    private var viewW = 0
    private var viewH = 0
    private var surfaceValid = false
    var focused = false
        private set
    var mapped = false
        private set
    var gone = false
        private set
    private var configuredW = -1
    private var configuredH = -1

    val title: String get() = toplevel.title.ifEmpty { toplevel.appId.ifEmpty { "Linux window" } }
    val appId: String get() = toplevel.appId
    val fullscreen: Boolean get() = toplevel.fullscreen
    /** The nested desktop itself: sway (wlroots) or Hyprland (aquamarine). */
    val isSway: Boolean get() = appId == "wlroots" || appId.startsWith("sway") || appId == "aquamarine" || appId.startsWith("hyprland", ignoreCase = true)

    // ---- size & configure -------------------------------------------------

    fun logicalSize(): Pair<Int, Int> {
        val s = server.scale
        if (viewW > 0 && viewH > 0) return (viewW / s) to (viewH / s)
        val (w, h) = server.displaySizePx()
        return (w / s) to (h * 9 / 10 / s)
    }

    fun geometry(): Rect = toplevel.xdg.geometry()

    /** From the activity's SurfaceView, on the main thread. */
    fun attach(h: Host) = synchronized(server.lock) {
        host = h
        server.windowsChanged()
    }

    fun detach(h: Host) = synchronized(server.lock) {
        if (host === h) { host = null; surfaceValid = false }
        server.windowsChanged()
    }

    fun surfaceChanged(w: Int, h: Int) = synchronized(server.lock) {
        viewW = w; viewH = h
        surfaceValid = true
        val (lw, lh) = logicalSize()
        if (!gone && (lw != configuredW || lh != configuredH)) {
            configuredW = lw; configuredH = lh
            toplevel.sendConfigure()
        }
        render()
    }

    fun surfaceDestroyed() = synchronized(server.lock) { surfaceValid = false }

    fun setFocused(f: Boolean) = synchronized(server.lock) {
        if (focused == f || gone) return@synchronized
        focused = f
        toplevel.sendConfigure()
        if (f) server.setKeyboardFocus(this) else server.clearKeyboardFocus(this)
    }

    /** User closed the task (swiped away / back): ask the client to close. */
    fun requestClose() = synchronized(server.lock) { if (!gone) toplevel.close() }

    // ---- lifecycle from the protocol (lock held) --------------------------

    fun map() {
        if (mapped) return
        mapped = true
        server.windowMapped(this)
    }

    fun unmap() {
        mapped = false
        server.windowUnmapped(this)
        host?.onWindowClosed()
    }

    fun destroyed() {
        if (gone) return
        gone = true
        mapped = false
        popups.clear()
        server.windowGone(this)
        host?.onWindowClosed()
    }

    fun metadataChanged() {
        host?.onMetadataChanged()
        server.windowsChanged()
    }

    fun minimize() { host?.onMinimize() }

    fun addPopup(p: XdgPopupRes) {
        if (p !in popups) popups += p
        requestRender()
    }

    fun removePopup(p: XdgPopupRes) {
        if (popups.remove(p)) requestRender()
    }

    fun topPopups(): List<XdgPopupRes> = popups.toList()

    // ---- rendering (lock held) --------------------------------------------

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // wl_shm ARGB8888 is BGRA in memory, Android bitmaps are RGBA: swap R and B
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 1f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    private val dst = RectF()
    private var lastRender = 0L

    fun requestRender() {
        render()
    }

    private fun render() {
        val h = host ?: return
        if (!surfaceValid || !mapped) return
        val canvas: Canvas = try { h.holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            val s = server.scale.toFloat()
            canvas.save()
            canvas.scale(s, s)
            val g = geometry()
            canvas.translate(-g.left.toFloat(), -g.top.toFloat())
            drawTree(canvas, surface, 0, 0)
            for (p in popups) {
                if (p.xdg.surface.mapped) drawTree(canvas, p.xdg.surface, p.originX(), p.originY())
            }
            canvas.restore()
        } finally {
            try { h.holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
        lastRender = SystemClock.uptimeMillis()
    }

    private fun drawTree(c: Canvas, s: SurfaceRes, x: Int, y: Int) {
        for (sub in s.children) if (sub.below) drawTree(c, sub.surface, x + sub.x, y + sub.y)
        s.bitmap?.let { bmp ->
            dst.set(x.toFloat(), y.toFloat(), (x + s.width).toFloat(), (y + s.height).toFloat())
            c.drawBitmap(bmp, null, dst, paint)
        }
        for (sub in s.children) if (!sub.below) drawTree(c, sub.surface, x + sub.x, y + sub.y)
    }

    // ---- input ------------------------------------------------------------

    /** View pixels -> root-surface logical coordinates. */
    fun toLogical(px: Float, py: Float): Pair<Double, Double> {
        val s = server.scale.toDouble()
        val g = geometry()
        return (px / s + g.left) to (py / s + g.top)
    }

    data class Hit(val surface: SurfaceRes, val x: Double, val y: Double, val popup: XdgPopupRes?)

    fun hitTest(lx: Double, ly: Double): Hit? {
        for (p in popups.asReversed()) {
            val ps = p.xdg.surface
            if (!ps.mapped) continue
            hitTree(ps, p.originX().toDouble(), p.originY().toDouble(), lx, ly)?.let { return Hit(it.first, it.second, it.third, p) }
        }
        return hitTree(surface, 0.0, 0.0, lx, ly)?.let { Hit(it.first, it.second, it.third, null) }
    }

    private fun hitTree(s: SurfaceRes, ox: Double, oy: Double, x: Double, y: Double): Triple<SurfaceRes, Double, Double>? {
        for (sub in s.children.asReversed()) {
            if (sub.below) continue
            hitTree(sub.surface, ox + sub.x, oy + sub.y, x, y)?.let { return it }
        }
        if (s.mapped && x >= ox && y >= oy && x < ox + s.width && y < oy + s.height) return Triple(s, x - ox, y - oy)
        for (sub in s.children.asReversed()) {
            if (!sub.below) continue
            hitTree(sub.surface, ox + sub.x, oy + sub.y, x, y)?.let { return it }
        }
        return null
    }

    /** Position of a surface's origin in root coordinates (for touch-local coords). */
    fun originOf(target: SurfaceRes): Pair<Double, Double>? {
        for (p in popups) originIn(p.xdg.surface, p.originX().toDouble(), p.originY().toDouble(), target)?.let { return it }
        return originIn(surface, 0.0, 0.0, target)
    }

    private fun originIn(s: SurfaceRes, ox: Double, oy: Double, target: SurfaceRes): Pair<Double, Double>? {
        if (s === target) return ox to oy
        for (sub in s.children) originIn(sub.surface, ox + sub.x, oy + sub.y, target)?.let { return it }
        return null
    }
}

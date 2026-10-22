package org.swatonly.launcher.wayland

import android.graphics.Rect

// xdg_wm_base, xdg_positioner, xdg_surface, xdg_toplevel, xdg_popup,
// zxdg_decoration_manager_v1, zxdg_toplevel_decoration_v1

class XdgWmBaseRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "xdg_wm_base"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> client.register(XdgPositionerRes(client, m.newId(), version))
            2 -> {
                val newId = m.newId()
                val surface = client.require<SurfaceRes>(m.obj())
                if (surface.role != null) error(XDG_WM_BASE_ERROR_ROLE, "surface already has a role")
                val xs = XdgSurfaceRes(client, newId, version, surface)
                client.register(xs)
                surface.role = xs
            }
            3 -> m.uint() // pong
        }
    }

    companion object { const val XDG_WM_BASE_ERROR_ROLE = 0 }
}

/** Positioner state is copied when a popup is created or repositioned. */
data class PositionerState(
    var width: Int = 1, var height: Int = 1,
    var anchorRect: Rect = Rect(0, 0, 1, 1),
    var anchor: Int = 0, var gravity: Int = 0,
    var constraint: Int = 0,
    var offsetX: Int = 0, var offsetY: Int = 0,
)

class XdgPositionerRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "xdg_positioner"
    val state = PositionerState()
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> { state.width = m.int(); state.height = m.int() }
            2 -> { val x = m.int(); val y = m.int(); val w = m.int(); val h = m.int(); state.anchorRect = Rect(x, y, x + w, y + h) }
            3 -> state.anchor = m.uint()
            4 -> state.gravity = m.uint()
            5 -> state.constraint = m.uint()
            6 -> { state.offsetX = m.int(); state.offsetY = m.int() }
            7 -> {} // set_reactive
            8 -> { m.int(); m.int() } // set_parent_size
            9 -> m.uint() // set_parent_configure
        }
    }
}

class XdgSurfaceRes(client: Client, id: Int, version: Int, val surface: SurfaceRes) : Resource(client, id, version), SurfaceRole {
    override val iface = "xdg_surface"

    var toplevel: XdgToplevelRes? = null
    var popup: XdgPopupRes? = null
    private var geometry: Rect? = null
    var configured = false
        private set
    var mapped = false
        private set

    fun window(): Window? = toplevel?.window ?: popup?.window()

    /** Window geometry in surface-local logical coordinates. */
    fun geometry(): Rect = geometry ?: Rect(0, 0, surface.width, surface.height)

    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> {
                val tl = XdgToplevelRes(client, m.newId(), version, this)
                client.register(tl)
                toplevel = tl
            }
            2 -> {
                val newId = m.newId()
                val parent = client.optional<XdgSurfaceRes>(m.obj())
                val positioner = client.require<XdgPositionerRes>(m.obj())
                val p = XdgPopupRes(client, newId, version, this, parent, positioner.state.copy())
                client.register(p)
                popup = p
            }
            3 -> {
                val x = m.int(); val y = m.int(); val w = m.int(); val h = m.int()
                if (w > 0 && h > 0) geometry = Rect(x, y, x + w, y + h)
                window()?.requestRender()
            }
            4 -> m.uint() // ack_configure
        }
    }

    fun sendConfigure() {
        val serial = client.server.nextSerial()
        event(0) { uint(serial) }
    }

    override fun committed() {
        if (!configured) {
            configured = true
            toplevel?.sendConfigure()
            popup?.sendConfigure()
            if (surface.bitmap == null) return
        }
        val hasBuffer = surface.bitmap != null
        if (hasBuffer && !mapped) {
            mapped = true
            toplevel?.window?.map()
            popup?.map()
        } else if (!hasBuffer && mapped) {
            mapped = false
            toplevel?.window?.unmap()
            popup?.unmap()
            configured = false // a re-mapped surface starts over with an initial commit
        }
    }

    override fun surfaceDestroyed() {
        toplevel?.let { client.destroy(it) }
        popup?.let { client.destroy(it) }
    }

    override fun destroyed() {
        surface.role = null
    }
}

class XdgToplevelRes(client: Client, id: Int, version: Int, val xdg: XdgSurfaceRes) : Resource(client, id, version) {
    override val iface = "xdg_toplevel"
    val window = Window(client.server, this)
    var title = ""
        private set
    var appId = ""
        private set
    var fullscreen = false
        private set
    var parentToplevel: XdgToplevelRes? = null
        private set

    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> parentToplevel = client.optional<XdgToplevelRes>(m.obj())
            2 -> { title = m.string() ?: ""; window.metadataChanged() }
            3 -> { appId = m.string() ?: ""; window.metadataChanged() }
            4 -> { m.obj(); m.uint(); m.int(); m.int() } // show_window_menu
            5 -> { m.obj(); m.uint() } // move: Android owns window placement
            6 -> { m.obj(); m.uint(); m.uint() } // resize
            7, 8 -> { m.int(); m.int() } // max/min size
            9, 10 -> sendConfigure() // (un)set_maximized: windows are always maximized in their task
            11 -> { m.obj(); fullscreen = true; window.metadataChanged(); sendConfigure() }
            12 -> { fullscreen = false; window.metadataChanged(); sendConfigure() }
            13 -> window.minimize()
        }
    }

    fun sendConfigure() {
        val (w, h) = window.logicalSize()
        val states = ArrayList<Int>()
        if (fullscreen) states += STATE_FULLSCREEN else states += STATE_MAXIMIZED
        if (window.focused) states += STATE_ACTIVATED
        if (version >= 2 && !fullscreen) states.addAll(listOf(STATE_TILED_LEFT, STATE_TILED_RIGHT, STATE_TILED_TOP, STATE_TILED_BOTTOM))
        event(0) { int(w); int(h); intArray(states.toIntArray()) }
        xdg.sendConfigure()
    }

    fun close() = event(1)

    override fun destroyed() {
        window.destroyed()
    }

    companion object {
        const val STATE_MAXIMIZED = 1
        const val STATE_FULLSCREEN = 2
        const val STATE_ACTIVATED = 4
        const val STATE_TILED_LEFT = 5
        const val STATE_TILED_RIGHT = 6
        const val STATE_TILED_TOP = 7
        const val STATE_TILED_BOTTOM = 8
    }
}

class XdgPopupRes(
    client: Client, id: Int, version: Int,
    val xdg: XdgSurfaceRes, val parent: XdgSurfaceRes?, private var positioner: PositionerState,
) : Resource(client, id, version) {
    override val iface = "xdg_popup"

    /** Geometry relative to the parent's window geometry. */
    var x = 0; var y = 0; var w = 1; var h = 1
    var grabbed = false
        private set

    fun window(): Window? = parent?.window()

    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> { m.obj(); m.uint(); grabbed = true }
            2 -> {
                positioner = client.require<XdgPositionerRes>(m.obj()).state.copy()
                val token = m.uint()
                if (version >= 3) {
                    compute()
                    event(2) { uint(token) }
                    event(0) { int(x); int(y); int(w); int(h) }
                    xdg.sendConfigure()
                }
            }
        }
    }

    /** Absolute (root surface coordinates) position of the popup surface origin. */
    fun originX(): Int {
        val p = parent ?: return 0
        return parentOriginX(p) + p.geometry().left + x - xdg.geometry().left
    }

    fun originY(): Int {
        val p = parent ?: return 0
        return parentOriginY(p) + p.geometry().top + y - xdg.geometry().top
    }

    private fun parentOriginX(p: XdgSurfaceRes): Int = p.popup?.originX() ?: 0
    private fun parentOriginY(p: XdgSurfaceRes): Int = p.popup?.originY() ?: 0

    private fun place(anchor: Int, gravity: Int, offX: Int, offY: Int): Pair<Int, Int> {
        val a = positioner.anchorRect
        val ax = when (anchor) { 3, 5, 6 -> a.left; 4, 7, 8 -> a.right; else -> a.centerX() }
        val ay = when (anchor) { 1, 5, 7 -> a.top; 2, 6, 8 -> a.bottom; else -> a.centerY() }
        val px = when (gravity) { 3, 5, 6 -> ax - w; 4, 7, 8 -> ax; else -> ax - w / 2 } + offX
        val py = when (gravity) { 1, 5, 7 -> ay - h; 2, 6, 8 -> ay; else -> ay - h / 2 } + offY
        return px to py
    }

    private fun flipV(e: Int) = when (e) { 1 -> 2; 2 -> 1; 5 -> 6; 6 -> 5; 7 -> 8; 8 -> 7; else -> e }
    private fun flipH(e: Int) = when (e) { 3 -> 4; 4 -> 3; 5 -> 7; 7 -> 5; 6 -> 8; 8 -> 6; else -> e }

    private fun compute() {
        val ps = positioner
        w = ps.width.coerceAtLeast(1); h = ps.height.coerceAtLeast(1)
        val (px, py) = place(ps.anchor, ps.gravity, ps.offsetX, ps.offsetY)
        x = px; y = py

        // keep it inside the window: flip if allowed, then slide
        val win = window() ?: return
        val (ww, wh) = win.logicalSize()
        val p = parent ?: return
        val baseX = parentOriginX(p) + p.geometry().left - win.geometry().left
        val baseY = parentOriginY(p) + p.geometry().top - win.geometry().top
        var absX = baseX + x
        var absY = baseY + y
        if (absX + w > ww && ps.constraint and 4 != 0) {
            val fx = baseX + place(flipH(ps.anchor), flipH(ps.gravity), -ps.offsetX, ps.offsetY).first
            if (fx >= 0) absX = fx
        }
        if (absY + h > wh && ps.constraint and 8 != 0) {
            val fy = baseY + place(flipV(ps.anchor), flipV(ps.gravity), ps.offsetX, -ps.offsetY).second
            if (fy >= 0) absY = fy
        }
        if (absX + w > ww) absX = ww - w
        if (absY + h > wh) absY = wh - h
        if (absX < 0) absX = 0
        if (absY < 0) absY = 0
        x = absX - baseX
        y = absY - baseY
    }

    fun sendConfigure() {
        compute()
        event(0) { int(x); int(y); int(w); int(h) }
        xdg.sendConfigure()
    }

    fun map() {
        window()?.addPopup(this)
    }

    fun unmap() {
        window()?.removePopup(this)
    }

    fun dismiss() {
        event(1)
    }

    override fun destroyed() {
        window()?.removePopup(this)
        xdg.popup = null
    }
}

class DecorationManagerRes(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "zxdg_decoration_manager_v1"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> {
                val d = ToplevelDecorationRes(client, m.newId())
                m.obj()
                client.register(d)
                d.configure()
            }
        }
    }
}

/** The Android task is the decoration: always ask for server-side mode (no CSD). */
class ToplevelDecorationRes(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "zxdg_toplevel_decoration_v1"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> { m.uint(); configure() }
            2 -> configure()
        }
    }
    fun configure() = event(0) { uint(2) }
}

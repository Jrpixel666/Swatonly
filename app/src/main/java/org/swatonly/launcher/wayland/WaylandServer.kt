package org.swatonly.launcher.wayland

import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.util.Log
import org.swatonly.launcher.App
import org.swatonly.launcher.R
import java.io.File
import java.io.IOException

/**
 * A small Wayland compositor. Linux programs under proot connect to
 * $XDG_RUNTIME_DIR/wayland-0 (bound to the app's files/run); every
 * xdg_toplevel they create opens as its own Android task.
 *
 * Supported: wl_compositor 4, wl_subcompositor, wl_shm (ARGB/XRGB8888),
 * wl_output 3, wl_seat 5 (pointer, keyboard, touch), xdg_wm_base 3,
 * zxdg_decoration_manager_v1 (always server side), wl_data_device_manager 3
 * (clipboard, bridged to Android's).
 */
class WaylandServer private constructor(private val ctx: Context) {

    val lock = Any()
    private val clients = ArrayList<Client>()
    private var server: LocalServerSocket? = null
    private var listenSocket: LocalSocket? = null
    @Volatile
    private var running = true

    private var serial = 1
    private var windowIds = 1
    private val windows = LinkedHashMap<Int, Window>()

    val scale: Int = App.instance.prefs.outputScale.coerceIn(1, 4)
    val touchMode: Boolean = App.instance.prefs.touchMode == "touch"
    val dpi: Float = ctx.resources.displayMetrics.xdpi.coerceAtLeast(100f)

    private val main = Handler(Looper.getMainLooper())
    private val frameThread = HandlerThread("wl-frame").apply { start() }
    private val frameHandler = Handler(frameThread.looper)
    private val frameCallbacks = ArrayList<CallbackRes>()
    private var frameScheduled = false

    data class Global(val name: Int, val iface: String, val version: Int, val bind: (Client, Int, Int) -> Resource, val onBound: (Resource) -> Unit = {})

    val globals = listOf(
        Global(1, "wl_compositor", 4, { c, id, v -> CompositorRes(c, id, v) }),
        Global(2, "wl_subcompositor", 1, { c, id, _ -> SubcompositorRes(c, id) }),
        Global(3, "wl_shm", 1, { c, id, v -> ShmRes(c, id, v) }, { (it as ShmRes).announce() }),
        Global(4, "wl_output", 3, { c, id, v -> OutputRes(c, id, v) }, { (it as OutputRes).let { o -> o.client.outputs += o; o.announce() } }),
        Global(5, "wl_seat", 5, { c, id, v -> SeatRes(c, id, v) }, { (it as SeatRes).announce() }),
        Global(6, "xdg_wm_base", 3, { c, id, v -> XdgWmBaseRes(c, id, v) }),
        Global(7, "zxdg_decoration_manager_v1", 1, { c, id, _ -> DecorationManagerRes(c, id) }),
        Global(8, "wl_data_device_manager", 3, { c, id, v -> DataDeviceManagerRes(c, id, v) }),
    )

    fun nextSerial(): Int = serial++
    fun nextWindowId(): Int = windowIds++
    fun time(): Int = (SystemClock.uptimeMillis() and 0xFFFFFFFFL).toInt()

    // ---- socket -----------------------------------------------------------

    private fun listen() {
        val runDir = App.instance.rootFs.runDir
        val path = File(runDir, org.swatonly.launcher.linux.Proot.WAYLAND_DISPLAY)
        path.delete()
        File(runDir, "${org.swatonly.launcher.linux.Proot.WAYLAND_DISPLAY}.lock").delete()
        val sock = LocalSocket(LocalSocket.SOCKET_STREAM)
        sock.bind(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
        try { Os.chmod(path.path, "777".toInt(8)) } catch (_: Exception) {}
        try { Os.chmod(runDir.path, "700".toInt(8)) } catch (_: Exception) {}
        listenSocket = sock
        val ss = LocalServerSocket(sock.fileDescriptor)
        server = ss
        writeKeymap()
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { if (running) Log.w(TAG, "accept failed", e); break }
                val c = Client(this, s)
                synchronized(lock) { clients += c }
                c.start()
            }
        }, "wl-accept").start()
        Log.i(TAG, "listening on $path")
    }

    fun clientClosed(c: Client) {
        clients.remove(c)
        if (keyboardFocus?.client === c) keyboardFocus = null
        if (pointerFocus?.client === c) pointerFocus = null
    }

    private fun shutdown() {
        running = false
        synchronized(lock) {
            clients.toList().forEach { it.close() }
            clients.clear()
        }
        try { server?.close() } catch (_: IOException) {}
        try { listenSocket?.close() } catch (_: IOException) {}
        frameThread.quitSafely()
        val ws = windows.values.toList()
        windows.clear()
        main.post { ws.forEach { it.host?.onWindowClosed() }; notifyListeners() }
    }

    // ---- keymap -------------------------------------------------------------

    private var keymap: File? = null

    private fun writeKeymap() {
        val out = File(App.instance.rootFs.runDir, "keymap.xkb")
        val fromRootfs = App.instance.rootFs.resolve("/opt/swatonly/keymap.xkb")?.takeIf { it.isFile && it.length() > 1000 }
        val text = fromRootfs?.readText() ?: ctx.assets.open("keymap/us.xkb").bufferedReader().use { it.readText() }
        out.writeBytes(text.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
        keymap = out
    }

    fun keymapFile(): File? = keymap

    // ---- frame callbacks ----------------------------------------------------

    fun queueFrameCallbacks(cbs: List<CallbackRes>) {
        frameCallbacks += cbs
        if (!frameScheduled) {
            frameScheduled = true
            frameHandler.postDelayed({ flushFrames() }, 16)
        }
    }

    private fun flushFrames() {
        synchronized(lock) {
            frameScheduled = false
            val t = time()
            val list = frameCallbacks.toList()
            frameCallbacks.clear()
            for (cb in list) if (cb.alive && !cb.client.closed) cb.done(t)
        }
    }

    // ---- windows ------------------------------------------------------------

    fun windowCount(): Int = synchronized(lock) { windows.values.count { it.mapped } }

    fun windowList(): List<Window> = synchronized(lock) { windows.values.filter { it.mapped } }

    fun window(id: Int): Window? = synchronized(lock) { windows[id] }

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) = main.post { listeners += l }
    fun removeListener(l: () -> Unit) = main.post { listeners -= l }
    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun windowsChanged() { main.post { notifyListeners() } }

    fun windowMapped(w: Window) {
        windows[w.id] = w
        main.post {
            show(w)
            notifyListeners()
        }
    }

    fun windowUnmapped(w: Window) { windowsChanged() }

    fun windowGone(w: Window) {
        windows.remove(w.id)
        if (keyboardFocus?.rootWindow() === w) keyboardFocus = null
        pointerFocus?.let { if (it.rootWindow() === w || it.rootWindow() == null) pointerFocus = null }
        main.post {
            ctx.getSystemService(android.app.NotificationManager::class.java).cancel(NOTIFY_BASE + w.id)
            notifyListeners()
        }
    }

    /** Opens the window's activity, as its own task when that setting is on. */
    fun show(w: Window) {
        val app = App.instance
        val i = WindowActivity.intent(ctx, w.id, app.prefs.windowPerTask)
        try { ctx.startActivity(i) } catch (e: Exception) { Log.w(TAG, "cannot start window activity", e) }
        if (app.resumedActivities == 0) {
            // background activity starts may be blocked: offer the window as a notification too
            val pi = PendingIntent.getActivity(ctx, w.id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, App.CHANNEL_WINDOWS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(w.title)
                .setContentText("New Linux window — tap to open")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(android.app.NotificationManager::class.java).notify(NOTIFY_BASE + w.id, n)
        }
    }

    fun focusWindowByAppId(appId: String, from: Context): Boolean {
        val w = synchronized(lock) {
            windows.values.firstOrNull { it.mapped && (it.appId == appId || (appId == "sway" && it.isSway)) }
        } ?: return false
        from.startActivity(WindowActivity.intent(from, w.id, App.instance.prefs.windowPerTask))
        return true
    }

    fun displaySizePx(): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    // ---- keyboard -----------------------------------------------------------

    private var keyboardFocus: SurfaceRes? = null
    private val pressedKeys = LinkedHashSet<Int>()
    private var modsDepressed = 0
    private var modsLocked = 0

    fun setKeyboardFocus(w: Window) {
        val target = w.surface
        if (keyboardFocus === target) return
        keyboardFocus?.let { old -> if (old.alive) old.client.keyboards.forEach { k -> k.event(2) { uint(nextSerial()); obj(old.id) } } }
        keyboardFocus = target
        sendKeyboardEnter(target)
        target.client.dataDevices.forEach { it.offerText(androidClipboard) }
    }

    fun clearKeyboardFocus(w: Window) {
        val old = keyboardFocus ?: return
        if (old !== w.surface) return
        if (old.alive) old.client.keyboards.forEach { k -> k.event(2) { uint(nextSerial()); obj(old.id) } }
        keyboardFocus = null
    }

    private fun sendKeyboardEnter(target: SurfaceRes, only: KeyboardRes? = null) {
        val keys = pressedKeys.toIntArray()
        for (k in target.client.keyboards) {
            if (only != null && k !== only) continue
            k.event(1) { uint(nextSerial()); obj(target.id); intArray(keys) }
            k.event(4) { uint(nextSerial()); uint(modsDepressed); uint(0); uint(modsLocked); uint(0) }
        }
    }

    fun keyboardBound(k: KeyboardRes) {
        val f = keyboardFocus ?: return
        if (f.client === k.client) sendKeyboardEnter(f, k)
    }

    /** evdev key code, pressed or released. */
    fun key(code: Int, pressed: Boolean) = synchronized(lock) {
        if (pressed) pressedKeys += code else pressedKeys -= code
        val mod = Keys.modifierMask(code)
        var modsChanged = false
        if (mod != 0) {
            val before = modsDepressed
            modsDepressed = if (pressed) modsDepressed or mod else modsDepressed and mod.inv()
            // keep a modifier held while its twin key is still down
            for (k in pressedKeys) modsDepressed = modsDepressed or Keys.modifierMask(k)
            modsChanged = before != modsDepressed
        }
        if (pressed && code == Keys.KEY_CAPSLOCK) { modsLocked = modsLocked xor Keys.MOD_LOCK; modsChanged = true }
        val f = keyboardFocus ?: return@synchronized
        if (!f.alive) return@synchronized
        val t = time()
        for (k in f.client.keyboards) {
            k.event(3) { uint(nextSerial()); uint(t); uint(code); uint(if (pressed) 1 else 0) }
            if (modsChanged) k.event(4) { uint(nextSerial()); uint(modsDepressed); uint(0); uint(modsLocked); uint(0) }
        }
    }

    fun releaseAllKeys() {
        for (k in pressedKeys.toList()) key(k, false)
    }

    // ---- pointer ------------------------------------------------------------

    private var pointerFocus: SurfaceRes? = null
    private var buttonsDown = 0

    /** Pointer motion over [w] at view pixel coords. */
    fun pointerMotion(w: Window, px: Float, py: Float) = synchronized(lock) {
        val (lx, ly) = w.toLogical(px, py)
        val hit = if (buttonsDown > 0 && pointerFocus != null) {
            // implicit grab: keep sending to the pressed surface
            val o = w.originOf(pointerFocus!!)
            if (o != null) Window.Hit(pointerFocus!!, lx - o.first, ly - o.second, null) else w.hitTest(lx, ly)
        } else w.hitTest(lx, ly)
        val target = hit?.surface
        if (target !== pointerFocus) {
            pointerFocus?.let { old -> if (old.alive) old.client.pointers.forEach { p -> p.event(1) { uint(nextSerial()); obj(old.id) }; p.frame() } }
            pointerFocus = target
            if (target != null && hit != null) target.client.pointers.forEach { p ->
                p.event(0) { uint(nextSerial()); obj(target.id); fixed(hit.x); fixed(hit.y) }
                p.frame()
            }
        } else if (target != null && hit != null) {
            val t = time()
            target.client.pointers.forEach { p -> p.event(2) { uint(t); fixed(hit.x); fixed(hit.y) }; p.frame() }
        }
    }

    /** Linux button code (BTN_LEFT = 0x110...). */
    fun pointerButton(w: Window, px: Float, py: Float, button: Int, pressed: Boolean) = synchronized(lock) {
        if (pressed) {
            val (lx, ly) = w.toLogical(px, py)
            val hit = w.hitTest(lx, ly)
            // a press outside every grabbing popup dismisses them
            val popups = w.topPopups()
            if (popups.any { it.grabbed } && hit?.popup == null) {
                popups.asReversed().forEach { it.dismiss() }
            }
            pointerMotion(w, px, py)
            buttonsDown++
        } else {
            buttonsDown = (buttonsDown - 1).coerceAtLeast(0)
        }
        val f = pointerFocus ?: return@synchronized
        if (!f.alive) return@synchronized
        val t = time()
        val s = nextSerial()
        f.client.pointers.forEach { p -> p.event(3) { uint(s); uint(t); uint(button); uint(if (pressed) 1 else 0) }; p.frame() }
        if (!pressed && buttonsDown == 0) pointerMotion(w, px, py)
    }

    /** Scroll: axis 0 = vertical, 1 = horizontal; value in surface units. */
    fun pointerAxis(w: Window, px: Float, py: Float, axis: Int, value: Double, discrete: Int, finger: Boolean) = synchronized(lock) {
        pointerMotion(w, px, py)
        val f = pointerFocus ?: return@synchronized
        val t = time()
        f.client.pointers.forEach { p ->
            if (p.version >= 5) p.event(6) { uint(if (finger) 1 else 0) }
            if (p.version >= 5 && discrete != 0) p.event(8) { uint(axis); int(discrete) }
            p.event(4) { uint(t); uint(axis); fixed(value) }
            p.frame()
        }
    }

    fun pointerLeave(w: Window) = synchronized(lock) {
        val f = pointerFocus ?: return@synchronized
        if (f.rootWindow() !== w && w.topPopups().none { it.xdg.surface === f }) return@synchronized
        if (f.alive) f.client.pointers.forEach { p -> p.event(1) { uint(nextSerial()); obj(f.id) }; p.frame() }
        pointerFocus = null
        buttonsDown = 0
    }

    // ---- touch (wl_touch, when touch mode is "touch") ------------------------

    private val touchTargets = HashMap<Int, Pair<SurfaceRes, Pair<Double, Double>>>()

    fun touchDown(w: Window, pid: Int, px: Float, py: Float) = synchronized(lock) {
        val (lx, ly) = w.toLogical(px, py)
        val hit = w.hitTest(lx, ly) ?: return@synchronized
        val origin = (lx - hit.x) to (ly - hit.y)
        touchTargets[pid] = hit.surface to origin
        val t = time()
        val s = nextSerial()
        hit.surface.client.touches.forEach { tr ->
            tr.event(0) { uint(s); uint(t); obj(hit.surface.id); int(pid); fixed(hit.x); fixed(hit.y) }
            tr.event(3)
        }
    }

    fun touchMotion(w: Window, pid: Int, px: Float, py: Float) = synchronized(lock) {
        val (surface, origin) = touchTargets[pid] ?: return@synchronized
        val (lx, ly) = w.toLogical(px, py)
        val t = time()
        surface.client.touches.forEach { tr ->
            tr.event(2) { uint(t); int(pid); fixed(lx - origin.first); fixed(ly - origin.second) }
            tr.event(3)
        }
    }

    fun touchUp(pid: Int) = synchronized(lock) {
        val (surface, _) = touchTargets.remove(pid) ?: return@synchronized
        val t = time()
        val s = nextSerial()
        surface.client.touches.forEach { tr -> tr.event(1) { uint(s); uint(t); int(pid) }; tr.event(3) }
    }

    // ---- clipboard ------------------------------------------------------------

    @Volatile
    var androidClipboard: String? = null
        private set
    var selection: DataSourceRes? = null

    /** From the main thread when a window gains focus (Android only lets focused apps read it). */
    fun updateAndroidClipboard(text: String?) = synchronized(lock) {
        if (text == androidClipboard) return@synchronized
        androidClipboard = text
        keyboardFocus?.client?.dataDevices?.forEach { it.offerText(text) }
    }

    fun setSelection(src: DataSourceRes?) {
        if (selection !== src) selection?.let { if (it.alive) it.cancel() }
        selection = src
        if (src == null) return
        val mime = DataDeviceRes.TEXT_MIMES.firstOrNull { it in src.mimeTypes } ?: return
        val pipe = src.receive(mime)
        Thread {
            val text = try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
            } catch (_: Exception) { null } ?: return@Thread
            synchronized(lock) { androidClipboard = text }
            main.post {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Linux", text))
            }
        }.start()
    }

    companion object {
        const val TAG = "SwatonlyWl"
        private const val NOTIFY_BASE = 1000

        @Volatile
        var instance: WaylandServer? = null
            private set

        @Synchronized
        fun start(ctx: Context): WaylandServer {
            instance?.let { return it }
            val s = WaylandServer(ctx.applicationContext)
            try {
                s.listen()
            } catch (e: Exception) {
                Log.e(TAG, "cannot start the compositor", e)
            }
            instance = s
            return s
        }

        @Synchronized
        fun stop() {
            instance?.shutdown()
            instance = null
        }
    }
}

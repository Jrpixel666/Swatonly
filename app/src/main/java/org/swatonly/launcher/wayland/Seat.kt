package org.swatonly.launcher.wayland

import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.FileOutputStream

// wl_output, wl_seat, wl_pointer, wl_keyboard, wl_touch,
// wl_data_device_manager, wl_data_source, wl_data_device, wl_data_offer

class OutputRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_output"
    override fun request(op: Int, m: WlMessage) {
        if (op == 0) destroy()
    }

    fun announce() {
        val s = client.server
        val (wPx, hPx) = s.displaySizePx()
        val mmW = (wPx / s.dpi * 25.4).toInt()
        val mmH = (hPx / s.dpi * 25.4).toInt()
        event(0) { int(0); int(0); int(mmW); int(mmH); int(0); string(Build.MANUFACTURER); string(Build.MODEL); int(0) }
        event(1) { uint(3); int(wPx); int(hPx); int(60000) }
        if (version >= 2) event(3) { int(s.scale) }
        if (version >= 4) {
            event(4) { string("ANDROID-1") }
            event(5) { string("${Build.MANUFACTURER} ${Build.MODEL}") }
        }
        if (version >= 2) event(2)
    }

    override fun destroyed() { client.outputs.remove(this) }
}

class SeatRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_seat"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> PointerRes(client, m.newId(), version).also { client.register(it); client.pointers += it }
            1 -> KeyboardRes(client, m.newId(), version).also { client.register(it); client.keyboards += it; it.setup() }
            2 -> TouchRes(client, m.newId(), version).also { client.register(it); client.touches += it }
            3 -> destroy()
        }
    }

    fun announce() {
        val caps = if (client.server.touchMode) 7 else 3 // pointer | keyboard (| touch)
        event(0) { uint(caps) }
        if (version >= 2) event(1) { string("seat0") }
    }
}

class PointerRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_pointer"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> { // set_cursor: the cursor surface just gets a do-nothing role
                m.uint()
                val s = client.optional<SurfaceRes>(m.obj())
                m.int(); m.int()
                if (s != null && s.role == null) s.role = CursorRole
            }
            1 -> destroy()
        }
    }

    fun frame() { if (version >= 5) event(5) }

    override fun destroyed() { client.pointers.remove(this) }

    object CursorRole : SurfaceRole {
        override fun committed() {}
        override fun surfaceDestroyed() {}
    }
}

class KeyboardRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_keyboard"
    override fun request(op: Int, m: WlMessage) {
        if (op == 0) destroy()
    }

    fun setup() {
        val km = client.server.keymapFile() ?: return
        val pfd = ParcelFileDescriptor.open(km, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            event(0) { uint(1); fd(pfd.fileDescriptor); uint(km.length().toInt()) }
        } finally {
            pfd.close()
        }
        if (version >= 4) event(5) { int(25); int(500) }
        client.server.keyboardBound(this)
    }

    override fun destroyed() { client.keyboards.remove(this) }
}

class TouchRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_touch"
    override fun request(op: Int, m: WlMessage) {
        if (op == 0) destroy()
    }
    override fun destroyed() { client.touches.remove(this) }
}

class DataDeviceManagerRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_data_device_manager"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> client.register(DataSourceRes(client, m.newId(), version))
            1 -> {
                val dd = DataDeviceRes(client, m.newId(), version)
                m.obj()
                client.register(dd)
                client.dataDevices += dd
            }
        }
    }
}

class DataSourceRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_data_source"
    val mimeTypes = ArrayList<String>()
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> m.string()?.let { mimeTypes += it }
            1 -> destroy()
            2 -> m.uint()
        }
    }

    /** Asks the client to write the data for [mime] into a pipe; returns the read end. */
    fun receive(mime: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            event(1) { string(mime); fd(pipe[1].fileDescriptor) }
        } finally {
            pipe[1].close()
        }
        return pipe[0]
    }

    fun cancel() = event(2)

    override fun destroyed() {
        if (client.server.selection === this) client.server.selection = null
    }
}

class DataDeviceRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_data_device"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> { m.obj(); m.obj(); m.obj(); m.uint() } // start_drag: drag and drop is not supported
            1 -> {
                val src = client.optional<DataSourceRes>(m.obj())
                m.uint()
                client.server.setSelection(src)
            }
            2 -> destroy()
        }
    }

    /** Offers [text] (the Android clipboard) as the current selection. */
    fun offerText(text: String?) {
        if (text == null) { event(5) { obj(0) }; return }
        val offer = DataOfferRes(client, client.allocServerId(), version, text)
        client.register(offer)
        event(0) { newId(offer.id) }
        for (mime in TEXT_MIMES) offer.event(0) { string(mime) }
        event(5) { obj(offer.id) }
    }

    override fun destroyed() { client.dataDevices.remove(this) }

    companion object {
        val TEXT_MIMES = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")
    }
}

class DataOfferRes(client: Client, id: Int, version: Int, private val text: String) : Resource(client, id, version) {
    override val iface = "wl_data_offer"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> { m.uint(); m.string() } // accept
            1 -> {
                m.string()
                val fd = m.fd()
                writeAndClose(fd, text)
            }
            2 -> destroy()
            3 -> {}
            4 -> { m.uint(); m.uint() }
        }
    }

    private fun writeAndClose(fd: FileDescriptor, data: String) {
        Thread {
            try { FileOutputStream(fd).use { it.write(data.toByteArray(Charsets.UTF_8)) } } catch (_: Exception) {}
            Client.closeFd(fd)
        }.start()
    }
}

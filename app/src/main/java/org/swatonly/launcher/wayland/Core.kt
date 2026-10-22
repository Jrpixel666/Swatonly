package org.swatonly.launcher.wayland

import android.graphics.Bitmap
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// wl_display, wl_registry, wl_callback, wl_compositor, wl_surface, wl_region,
// wl_subcompositor, wl_subsurface, wl_shm, wl_shm_pool, wl_buffer

class DisplayRes(client: Client) : Resource(client, 1, 1) {
    override val iface = "wl_display"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> { // sync
                val cb = CallbackRes(client, m.newId())
                client.register(cb)
                cb.done(client.server.nextSerial())
            }
            1 -> { // get_registry
                val reg = RegistryRes(client, m.newId())
                client.register(reg)
                client.server.globals.forEach { g -> reg.event(0) { uint(g.name); string(g.iface); uint(g.version) } }
            }
        }
    }
}

class RegistryRes(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_registry"
    override fun request(op: Int, m: WlMessage) {
        if (op != 0) return
        val name = m.uint()
        val iface = m.string() ?: ""
        val version = m.uint()
        val newId = m.newId()
        val g = client.server.globals.firstOrNull { it.name == name }
            ?: error(Client.WL_DISPLAY_ERROR_INVALID_OBJECT, "no global $name")
        if (g.iface != iface) error(Client.WL_DISPLAY_ERROR_INVALID_OBJECT, "global $name is ${g.iface}, not $iface")
        val v = minOf(version, g.version)
        val r = g.bind(client, newId, v)
        client.register(r)
        g.onBound(r)
    }
}

class CallbackRes(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_callback"
    override fun request(op: Int, m: WlMessage) {}
    fun done(data: Int) {
        event(0) { uint(data) }
        destroy()
    }
}

class CompositorRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_compositor"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> client.register(SurfaceRes(client, m.newId(), version))
            1 -> client.register(RegionRes(client, m.newId()))
        }
    }
}

class RegionRes(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_region"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1, 2 -> { m.int(); m.int(); m.int(); m.int() } // add/subtract: input regions are not used
        }
    }
}

/** What a wl_surface is used for. */
interface SurfaceRole {
    fun committed()
    fun surfaceDestroyed()
}

class SurfaceRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_surface"

    private var pendingBuffer: BufferRes? = null
    private var pendingBufferSet = false
    private var pendingScale = 1
    private val pendingFrames = ArrayList<CallbackRes>()
    private var pendingOffsetX = 0
    private var pendingOffsetY = 0

    /** Contents in buffer pixels; drawn at width x height logical units. */
    var bitmap: Bitmap? = null
        private set
    var opaque = false
        private set
    var scale = 1
        private set

    var role: SurfaceRole? = null
    /** Set when this surface is a subsurface. */
    var asSubsurface: SubsurfaceRes? = null
    val children = ArrayList<SubsurfaceRes>()
    private var entered = false

    val width: Int get() = (bitmap?.width ?: 0) / scale
    val height: Int get() = (bitmap?.height ?: 0) / scale
    val mapped: Boolean get() = bitmap != null

    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> { // attach
                pendingBuffer = client.optional<BufferRes>(m.obj())
                pendingOffsetX = m.int(); pendingOffsetY = m.int()
                pendingBufferSet = true
            }
            2, 9 -> { m.int(); m.int(); m.int(); m.int() } // damage / damage_buffer: whole buffer is copied
            3 -> { // frame
                val cb = CallbackRes(client, m.newId())
                client.register(cb)
                pendingFrames += cb
            }
            4, 5 -> m.obj() // opaque / input region
            6 -> commit()
            7 -> m.int() // buffer transform: only normal is supported
            8 -> pendingScale = m.int().coerceIn(1, 8)
            10 -> { pendingOffsetX = m.int(); pendingOffsetY = m.int() }
        }
    }

    private fun commit() {
        if (pendingBufferSet) {
            val b = pendingBuffer
            if (b != null && b.alive) {
                copyFrom(b)
                b.event(0) // release: contents were copied
            } else {
                bitmap = null
            }
            pendingBufferSet = false
            pendingBuffer = null
        }
        scale = pendingScale
        if (pendingFrames.isNotEmpty()) {
            client.server.queueFrameCallbacks(pendingFrames)
            pendingFrames.clear()
        }
        if (!entered && bitmap != null) {
            entered = true
            client.outputs.forEach { o -> event(0) { obj(o.id) } }
        }
        role?.committed()
        rootWindow()?.requestRender()
    }

    private val copyTmp = ThreadLocal<ByteBuffer>()

    private fun copyFrom(b: BufferRes) {
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0) { bitmap = null; return }
        var bmp = bitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val src = b.pool.view() ?: return
        val rowBytes = w * 4
        if (b.offset < 0 || b.stride < rowBytes || b.offset.toLong() + b.stride.toLong() * (h - 1) + rowBytes > src.capacity()) {
            throw ProtocolError(b.id, 0, "buffer outside its pool")
        }
        if (b.stride == rowBytes) {
            src.position(b.offset)
            src.limit(b.offset + rowBytes * h)
            bmp.copyPixelsFromBuffer(src)
        } else {
            var tmp = copyTmp.get()
            if (tmp == null || tmp.capacity() < rowBytes * h) {
                tmp = ByteBuffer.allocateDirect(rowBytes * h)
                copyTmp.set(tmp)
            }
            tmp!!.clear()
            for (row in 0 until h) {
                val start = b.offset + row * b.stride
                src.limit(start + rowBytes)
                src.position(start)
                tmp.put(src)
                src.limit(src.capacity())
            }
            tmp.flip()
            bmp.copyPixelsFromBuffer(tmp)
        }
        opaque = b.format == SHM_FORMAT_XRGB8888
        bmp.setHasAlpha(!opaque)
        bitmap = bmp
    }

    /** The window this surface is drawn in (walks up subsurfaces). */
    fun rootWindow(): Window? {
        var s: SurfaceRes = this
        while (true) s = s.asSubsurface?.parent ?: break
        val role = s.role
        return when (role) {
            is XdgSurfaceRes -> role.window()
            else -> null
        }
    }

    override fun destroyed() {
        role?.surfaceDestroyed()
        role = null
        asSubsurface?.let { it.parent.children.remove(it) }
        for (c in children.toList()) c.parentGone()
        children.clear()
        pendingFrames.forEach { client.destroy(it) }
        pendingFrames.clear()
        bitmap = null
        rootWindow()?.requestRender()
    }

    companion object {
        const val SHM_FORMAT_ARGB8888 = 0
        const val SHM_FORMAT_XRGB8888 = 1
    }
}

class SubcompositorRes(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_subcompositor"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> {
                val newId = m.newId()
                val surface = client.require<SurfaceRes>(m.obj())
                val parent = client.require<SurfaceRes>(m.obj())
                if (surface.role != null || surface.asSubsurface != null) error(0, "surface already has a role")
                val sub = SubsurfaceRes(client, newId, surface, parent)
                client.register(sub)
                surface.asSubsurface = sub
                parent.children += sub
            }
        }
    }
}

class SubsurfaceRes(client: Client, id: Int, val surface: SurfaceRes, val parent: SurfaceRes) : Resource(client, id, 1) {
    override val iface = "wl_subsurface"
    var x = 0
    var y = 0
    /** Drawn below the parent (place_below parent). */
    var below = false

    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> destroy()
            1 -> { x = m.int(); y = m.int() }
            2 -> restack(client.require<SurfaceRes>(m.obj()), above = true)
            3 -> restack(client.require<SurfaceRes>(m.obj()), above = false)
            4, 5 -> {} // sync/desync: state is applied on the subsurface's own commit
        }
    }

    private fun restack(sibling: SurfaceRes, above: Boolean) {
        val list = parent.children
        list.remove(this)
        if (sibling === parent) {
            below = !above
            if (above) list.add(0, this) else list.add(this)
            return
        }
        val idx = list.indexOfFirst { it.surface === sibling }
        val sib = list.getOrNull(idx)
        below = sib?.below ?: false
        if (idx < 0) list.add(this) else list.add(if (above) idx + 1 else idx, this)
    }

    fun parentGone() {
        surface.asSubsurface = null
    }

    override fun destroyed() {
        parent.children.remove(this)
        surface.asSubsurface = null
        parent.rootWindow()?.requestRender()
    }
}

class ShmRes(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_shm"
    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> {
                val newId = m.newId()
                val fd = m.fd()
                val size = m.int()
                client.register(ShmPoolRes(client, newId, fd, size))
            }
            1 -> destroy()
        }
    }
    fun announce() {
        event(0) { uint(SurfaceRes.SHM_FORMAT_ARGB8888) }
        event(0) { uint(SurfaceRes.SHM_FORMAT_XRGB8888) }
    }
}

class ShmPoolRes(client: Client, id: Int, private val fd: FileDescriptor, private var size: Int) : Resource(client, id, 1) {
    override val iface = "wl_shm_pool"
    private var channel: FileChannel? = FileInputStream(fd).channel
    private var map: MappedByteBuffer? = mapNow()

    private fun mapNow(): MappedByteBuffer? = try {
        channel?.map(FileChannel.MapMode.READ_ONLY, 0, size.toLong())
    } catch (e: Exception) {
        throw ProtocolError(id, 2, "cannot map pool of $size bytes: ${e.message}")
    }

    /** A fresh view of the mapping (independent position/limit). */
    fun view(): ByteBuffer? = map?.duplicate()

    override fun request(op: Int, m: WlMessage) {
        when (op) {
            0 -> {
                val newId = m.newId()
                val offset = m.int(); val w = m.int(); val h = m.int(); val stride = m.int(); val format = m.uint()
                if (format != SurfaceRes.SHM_FORMAT_ARGB8888 && format != SurfaceRes.SHM_FORMAT_XRGB8888) error(0, "unsupported format $format")
                client.register(BufferRes(client, newId, this, offset, w, h, stride, format))
            }
            1 -> destroy()
            2 -> {
                val newSize = m.int()
                if (newSize > size) { size = newSize; map = mapNow() }
            }
        }
    }

    override fun destroyed() {
        // buffers keep the mapping alive; the descriptor is no longer needed
        try { channel?.close() } catch (_: Exception) {}
        channel = null
        Client.closeFd(fd) // the stream does not own it, so closing the channel leaves it open
    }
}

class BufferRes(
    client: Client, id: Int, val pool: ShmPoolRes,
    val offset: Int, val width: Int, val height: Int, val stride: Int, val format: Int,
) : Resource(client, id, 1) {
    override val iface = "wl_buffer"
    override fun request(op: Int, m: WlMessage) {
        if (op == 0) destroy()
    }
}

package org.swatonly.launcher.wayland

import android.net.LocalSocket
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One Wayland client connection (a Linux program running under proot). */
class Client(val server: WaylandServer, private val socket: LocalSocket) {

    private val objects = HashMap<Int, Resource>()
    private val fdQueue = ArrayDeque<FileDescriptor>()
    private val out = socket.outputStream
    private val writeLock = Any()
    private var nextServerId = SERVER_ID_START
    @Volatile
    var closed = false
        private set

    val display = DisplayRes(this)

    // per-client seat devices; events for a surface go to its client's devices
    val pointers = ArrayList<PointerRes>()
    val keyboards = ArrayList<KeyboardRes>()
    val touches = ArrayList<TouchRes>()
    val outputs = ArrayList<OutputRes>()
    val dataDevices = ArrayList<DataDeviceRes>()

    init { objects[1] = display }

    fun start() {
        Thread({ readLoop() }, "wl-client").start()
    }

    fun register(r: Resource) {
        if (objects.containsKey(r.id)) throw ProtocolError(1, WL_DISPLAY_ERROR_INVALID_OBJECT, "id ${r.id} already in use")
        objects[r.id] = r
    }

    fun get(id: Int): Resource? = objects[id]

    inline fun <reified T : Resource> require(id: Int): T {
        val r = get(id)
        return r as? T ?: throw ProtocolError(1, WL_DISPLAY_ERROR_INVALID_OBJECT, "object $id is not a ${T::class.java.simpleName}")
    }

    inline fun <reified T : Resource> optional(id: Int): T? = if (id == 0) null else require<T>(id)

    fun allocServerId(): Int = nextServerId++

    fun destroy(r: Resource) {
        if (!r.alive) return
        r.alive = false
        objects.remove(r.id)
        try { r.destroyed() } catch (e: Exception) { Log.w(TAG, "destroy ${r.iface}", e) }
        // server-allocated ids are not acknowledged with delete_id
        if (Integer.compareUnsigned(r.id, SERVER_ID_START) < 0) display.event(1) { uint(r.id) }
    }

    fun send(e: WlEvent) {
        if (closed) return
        val bytes = e.bytes()
        try {
            synchronized(writeLock) {
                if (e.fds.isNotEmpty()) socket.setFileDescriptorsForSend(e.fds.toTypedArray())
                out.write(bytes)
                if (e.fds.isNotEmpty()) socket.setFileDescriptorsForSend(null)
            }
        } catch (ex: IOException) {
            close()
        }
    }

    private fun readLoop() {
        var buf = ByteArray(1 shl 16)
        var len = 0
        val input = socket.inputStream
        try {
            while (!closed) {
                if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                val n = input.read(buf, len, buf.size - len)
                if (n < 0) break
                socket.ancillaryFileDescriptors?.let { fds -> fds.forEach { fdQueue.addLast(it) } }
                len += n
                var off = 0
                synchronized(server.lock) {
                    while (len - off >= 8) {
                        val id = le32(buf, off)
                        val sizeOp = le32(buf, off + 4)
                        val size = sizeOp ushr 16
                        val op = sizeOp and 0xFFFF
                        if (size < 8 || size and 3 != 0) throw ProtocolError(1, WL_DISPLAY_ERROR_INVALID_METHOD, "bad message size $size")
                        if (len - off < size) break
                        val args = ByteBuffer.wrap(buf, off + 8, size - 8).slice().order(ByteOrder.LITTLE_ENDIAN)
                        dispatch(WlMessage(id, op, args, fdQueue))
                        off += size
                    }
                }
                if (off > 0) {
                    System.arraycopy(buf, off, buf, 0, len - off)
                    len -= off
                }
            }
        } catch (e: ProtocolError) {
            Log.w(TAG, "protocol error: ${e.message}")
            display.event(0) { obj(e.objectId); uint(e.code); string(e.message ?: "error") }
        } catch (e: IOException) {
            // client went away
        } catch (e: Exception) {
            Log.e(TAG, "client crashed the compositor dispatch", e)
            display.event(0) { obj(1); uint(WL_DISPLAY_ERROR_IMPLEMENTATION); string(e.toString()) }
        }
        close()
    }

    private fun dispatch(m: WlMessage) {
        val r = objects[m.objectId] ?: return // destroyed objects may still get requests in flight
        try {
            r.request(m.opcode, m)
        } catch (e: ProtocolError) {
            throw e
        } catch (e: java.nio.BufferUnderflowException) {
            throw ProtocolError(m.objectId, WL_DISPLAY_ERROR_INVALID_METHOD, "short request ${r.iface}#${m.opcode}")
        }
    }

    fun close() {
        synchronized(server.lock) {
            if (closed) return
            closed = true
            for (r in objects.values.toList().reversed()) {
                r.alive = false
                try { r.destroyed() } catch (e: Exception) { Log.w(TAG, "cleanup ${r.iface}", e) }
            }
            objects.clear()
            while (fdQueue.isNotEmpty()) closeFd(fdQueue.removeFirst())
            server.clientClosed(this)
        }
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        const val TAG = "SwatonlyWl"
        val SERVER_ID_START = 0xFF000000.toInt()
        const val WL_DISPLAY_ERROR_INVALID_OBJECT = 0
        const val WL_DISPLAY_ERROR_INVALID_METHOD = 1
        const val WL_DISPLAY_ERROR_NO_MEMORY = 2
        const val WL_DISPLAY_ERROR_IMPLEMENTATION = 3

        fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

        fun closeFd(fd: FileDescriptor) {
            try { Os.close(fd) } catch (_: Exception) {}
        }
    }
}

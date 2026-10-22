package org.swatonly.launcher.wayland

import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolError(val objectId: Int, val code: Int, message: String) : Exception(message)

/** A request read off the socket: header already consumed, args read in order. */
class WlMessage(
    val objectId: Int,
    val opcode: Int,
    private val buf: ByteBuffer,
    private val fds: ArrayDeque<FileDescriptor>,
) {
    fun int(): Int = buf.int
    fun uint(): Int = buf.int
    fun fixed(): Double = buf.int / 256.0
    fun obj(): Int = buf.int
    fun newId(): Int = buf.int

    fun string(): String? {
        val len = buf.int
        if (len == 0) return null
        val bytes = ByteArray(len - 1)
        buf.get(bytes)
        buf.get() // NUL
        skipPad(len)
        return String(bytes, Charsets.UTF_8)
    }

    fun array(): ByteArray {
        val len = buf.int
        val bytes = ByteArray(len)
        buf.get(bytes)
        skipPad(len)
        return bytes
    }

    fun fd(): FileDescriptor = fds.removeFirstOrNull()
        ?: throw ProtocolError(1, 1, "request $opcode on $objectId expects a file descriptor")

    private fun skipPad(len: Int) {
        val pad = (4 - (len and 3)) and 3
        buf.position(buf.position() + pad)
    }
}

/** An event being built for one client. */
class WlEvent(private val objectId: Int, private val opcode: Int) {
    private var data = ByteArray(64)
    private var size = 8
    val fds = ArrayList<FileDescriptor>(0)

    private fun ensure(n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
    }

    private fun put32(v: Int) {
        ensure(4)
        data[size] = v.toByte()
        data[size + 1] = (v shr 8).toByte()
        data[size + 2] = (v shr 16).toByte()
        data[size + 3] = (v shr 24).toByte()
        size += 4
    }

    fun int(v: Int) = apply { put32(v) }
    fun uint(v: Int) = apply { put32(v) }
    fun fixed(v: Double) = apply { put32((v * 256.0).toInt()) }
    fun obj(id: Int) = apply { put32(id) }
    fun newId(id: Int) = apply { put32(id) }
    fun fd(fd: FileDescriptor) = apply { fds += fd }

    fun string(s: String?) = apply {
        if (s == null) { put32(0); return@apply }
        val b = s.toByteArray(Charsets.UTF_8)
        put32(b.size + 1)
        ensure(b.size + 4)
        System.arraycopy(b, 0, data, size, b.size)
        size += b.size
        data[size++] = 0
        while (size and 3 != 0) data[size++] = 0
    }

    fun array(b: ByteArray) = apply {
        put32(b.size)
        ensure(b.size + 4)
        System.arraycopy(b, 0, data, size, b.size)
        size += b.size
        while (size and 3 != 0) data[size++] = 0
    }

    fun intArray(values: IntArray) = apply {
        val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bb.putInt(it) }
        array(bb.array())
    }

    fun bytes(): ByteArray {
        val h = ByteBuffer.wrap(data, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(objectId)
        h.putInt((size shl 16) or (opcode and 0xFFFF))
        return data.copyOf(size)
    }
}

/** Base for every protocol object a client has bound or created. */
abstract class Resource(val client: Client, val id: Int, val version: Int) {
    abstract val iface: String
    @Volatile
    var alive = true
        internal set

    abstract fun request(op: Int, m: WlMessage)

    /** Called once when the object goes away (destroy request or client exit). */
    open fun destroyed() {}

    fun event(op: Int, build: WlEvent.() -> Unit = {}) {
        if (!alive) return
        val e = WlEvent(id, op)
        e.build()
        client.send(e)
    }

    /** Destructor request: forget the object and tell the client its id is free. */
    fun destroy() = client.destroy(this)

    fun error(code: Int, msg: String): Nothing = throw ProtocolError(id, code, msg)
}

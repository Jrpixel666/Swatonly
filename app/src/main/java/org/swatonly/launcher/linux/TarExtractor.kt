package org.swatonly.launcher.linux

import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal tar reader (ustar, GNU long names, pax headers) for unpacking a
 * rootfs straight into the app's data directory. Hard links are copied:
 * Android does not let apps create them.
 */
class TarExtractor(
    private val dest: File,
    /** Return false to skip an entry (path is relative, without leading "./"). */
    private val filter: (String) -> Boolean = { true },
    private val onEntry: (String) -> Unit = {},
) {
    private var stripPrefix: String? = null
    private val deferredHardLinks = mutableListOf<Pair<String, String>>()
    var entries = 0
        private set

    fun extract(raw: InputStream) {
        val input = decompress(BufferedInputStream(raw, 1 shl 16))
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        var globalPax: Map<String, String> = emptyMap()
        dest.mkdirs()

        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break // end-of-archive marker

            val type = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            val mode = parseOctal(header, 100, 8).toInt()

            when (type) {
                'L' -> { longName = readString(input, size); continue }
                'K' -> { longLink = readString(input, size); continue }
                'x' -> { pax = parsePax(readString(input, size)); continue }
                'g' -> { globalPax = parsePax(readString(input, size)); continue }
            }

            var name = pax["path"] ?: globalPax["path"] ?: longName ?: run {
                val n = cString(header, 0, 100)
                val prefix = cString(header, 345, 155)
                if (prefix.isNotEmpty()) "$prefix/$n" else n
            }
            val link = pax["linkpath"] ?: longLink ?: cString(header, 157, 100)
            val realSize = pax["size"]?.toLongOrNull() ?: size
            longName = null; longLink = null; pax = emptyMap()

            name = normalize(name)
            if (entries == 0 && stripPrefix == null) detectPrefix(name, type)
            stripPrefix?.let { p -> name = if (name.startsWith(p)) name.removePrefix(p) else name }
            name = name.trimStart('/')

            entries++
            if (name.isEmpty() || name.contains("../") || !filter(name)) {
                skip(input, realSize)
                continue
            }
            onEntry(name)
            val out = File(dest, name)

            when (type) {
                '5' -> {
                    out.mkdirs()
                    chmod(out, mode or 0b111_000_000)
                }
                '2' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    try { Os.symlink(link, out.path) } catch (_: Exception) {}
                }
                '1' -> {
                    var target = normalize(link)
                    stripPrefix?.let { p -> if (target.startsWith(p)) target = target.removePrefix(p) }
                    deferredHardLinks += target.trimStart('/') to name
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (out.exists()) out.delete()
                    FileOutputStream(out).use { copy(input, it, realSize) }
                    skipPadding(input, realSize)
                    chmod(out, mode or 0b110_000_000)
                    continue
                }
                else -> { /* devices, fifos: not needed under proot */ }
            }
            skip(input, realSize)
        }

        for ((target, name) in deferredHardLinks) {
            val src = File(dest, target)
            val out = File(dest, name)
            out.parentFile?.mkdirs()
            if (src.isFile) {
                out.delete()
                src.copyTo(out, overwrite = true)
                if (src.canExecute()) out.setExecutable(true, false)
            }
        }
    }

    /** Archives like proot-distro's wrap everything in one top directory. */
    private fun detectPrefix(name: String, type: Char) {
        val first = name.trimStart('/').substringBefore('/')
        val rootDirs = setOf("bin", "boot", "dev", "etc", "home", "lib", "lib64", "mnt", "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var")
        stripPrefix = if (type == '5' && first.isNotEmpty() && first !in rootDirs && name.trimStart('/').trimEnd('/') == first) "$first/" else ""
    }

    private fun normalize(n: String): String {
        var s = n
        while (s.startsWith("./")) s = s.substring(2)
        if (s == ".") s = ""
        return s
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode and 0b111_111_111) } catch (_: Exception) {}
    }

    private val buf = ByteArray(1 shl 16)

    private fun copy(input: InputStream, out: FileOutputStream, size: Long) {
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("truncated archive")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size + padding(size)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("truncated archive")
            left -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        var left = padding(size)
        while (left > 0) {
            val n = input.read(buf, 0, left.toInt())
            if (n < 0) throw IOException("truncated archive")
            left -= n
        }
    }

    private fun padding(size: Long): Long = (512 - size % 512) % 512

    private fun readString(input: InputStream, size: Long): String {
        val b = ByteArray(size.toInt())
        if (!readFully(input, b)) throw IOException("truncated archive")
        skipPadding(input, size)
        return String(b, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun parsePax(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val bytes = s.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            var sp = i
            while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val len = String(bytes, i, sp - i).toIntOrNull() ?: break
            val record = String(bytes, sp + 1, len - (sp - i) - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun readFully(input: InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { // GNU base-256 for big files
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) { if (v != 0L) break else continue }
            if (c < '0'.code || c > '7'.code) break
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    companion object {
        fun decompress(input: BufferedInputStream): InputStream {
            input.mark(8)
            val m = ByteArray(6)
            val n = input.read(m)
            input.reset()
            return when {
                n >= 2 && m[0] == 0x1f.toByte() && m[1] == 0x8b.toByte() -> GZIPInputStream(input, 1 shl 16)
                n >= 6 && m[0] == 0xFD.toByte() && m[1] == '7'.code.toByte() && m[2] == 'z'.code.toByte() -> XZInputStream(input)
                else -> input // plain tar
            }
        }
    }
}

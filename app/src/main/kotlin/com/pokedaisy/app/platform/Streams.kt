package com.pokedaisy.app.platform

/*
 * Pure Kotlin stand-ins for the java.io byte streams the data layer used. They write and read exactly the bytes the
 * JDK classes do (big-endian numbers, writeUTF's modified UTF-8), so files written before the move (SnapshotCache's
 * `.companion` copies) still read, and new ones read in an older build.
 */

/** A growing byte array (java.io.ByteArrayOutputStream's subset). */
class ByteArrayOutputStream(initial: Int = 32) {
    private var buf = ByteArray(maxOf(1, initial))
    private var count = 0

    private fun ensure(extra: Int) {
        val need = count + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
    }

    fun write(b: Int) { ensure(1); buf[count++] = b.toByte() }
    fun write(b: ByteArray) = write(b, 0, b.size)
    fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        b.copyInto(buf, count, off, off + len)
        count += len
    }
    fun size(): Int = count
    fun reset() { count = 0 }
    fun toByteArray(): ByteArray = buf.copyOf(count)
}

/** UTF data too long for writeUTF's 16-bit length, or malformed when read. */
class UTFDataFormatException(message: String) : IOException(message)

/** End of data reached in the middle of a value. */
class EOFException : IOException()

/** java.io.DataOutputStream over a [ByteArrayOutputStream]. */
class DataOutputStream(private val out: ByteArrayOutputStream) : AutoCloseable {
    fun write(b: Int) = out.write(b)
    fun write(b: ByteArray) = out.write(b)
    fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    fun writeBoolean(v: Boolean) = out.write(if (v) 1 else 0)
    fun writeByte(v: Int) = out.write(v)
    fun writeShort(v: Int) { out.write(v ushr 8); out.write(v) }
    fun writeChar(v: Int) = writeShort(v)
    fun writeInt(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    fun writeLong(v: Long) { writeInt((v ushr 32).toInt()); writeInt(v.toInt()) }
    /** Each char as one byte (its low 8 bits), as DataOutputStream.writeBytes. */
    fun writeBytes(s: String) { for (c in s) out.write(c.code) }

    /** Modified UTF-8 with a 16-bit length, as DataOutputStream.writeUTF. */
    fun writeUTF(s: String) {
        var len = 0
        for (c in s) len += when (c.code) { in 0x0001..0x007F -> 1; 0, in 0x0080..0x07FF -> 2; else -> 3 }
        if (len > 65535) throw UTFDataFormatException("encoded string too long: $len bytes")
        writeShort(len)
        for (c in s) {
            val ch = c.code
            when (ch) {
                in 0x0001..0x007F -> out.write(ch)
                0, in 0x0080..0x07FF -> { out.write(0xC0 or (ch shr 6)); out.write(0x80 or (ch and 0x3F)) }
                else -> { out.write(0xE0 or (ch shr 12)); out.write(0x80 or ((ch shr 6) and 0x3F)); out.write(0x80 or (ch and 0x3F)) }
            }
        }
    }

    fun flush() {}
    override fun close() {}
}

/** java.io.DataInputStream over a byte array. */
class DataInputStream(private val b: ByteArray) : AutoCloseable {
    private var pos = 0

    private fun next(): Int {
        if (pos >= b.size) throw EOFException()
        return b[pos++].toInt() and 0xFF
    }

    fun read(): Int = if (pos < b.size) b[pos++].toInt() and 0xFF else -1
    fun readFully(dst: ByteArray) {
        if (pos + dst.size > b.size) throw EOFException()
        b.copyInto(dst, 0, pos, pos + dst.size)
        pos += dst.size
    }
    fun readBoolean(): Boolean = next() != 0
    fun readByte(): Byte = next().toByte()
    fun readUnsignedByte(): Int = next()
    fun readShort(): Short = ((next() shl 8) or next()).toShort()
    fun readUnsignedShort(): Int = (next() shl 8) or next()
    fun readChar(): Char = readUnsignedShort().toChar()
    fun readInt(): Int = (next() shl 24) or (next() shl 16) or (next() shl 8) or next()
    fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xFFFFFFFFL)
    fun readBytes(n: Int): ByteArray = ByteArray(n).also { readFully(it) }

    /** Modified UTF-8 with a 16-bit length, as DataInputStream.readUTF. */
    fun readUTF(): String {
        val len = readUnsignedShort()
        if (pos + len > b.size) throw EOFException()
        val end = pos + len
        val sb = StringBuilder(len)
        while (pos < end) {
            val c = b[pos].toInt() and 0xFF
            when (c shr 4) {
                in 0..7 -> { sb.append(c.toChar()); pos++ }
                12, 13 -> {
                    if (pos + 2 > end) throw UTFDataFormatException("malformed input: partial character at end")
                    val c2 = b[pos + 1].toInt()
                    if (c2 and 0xC0 != 0x80) throw UTFDataFormatException("malformed input around byte ${pos + 1}")
                    sb.append((((c and 0x1F) shl 6) or (c2 and 0x3F)).toChar()); pos += 2
                }
                14 -> {
                    if (pos + 3 > end) throw UTFDataFormatException("malformed input: partial character at end")
                    val c2 = b[pos + 1].toInt()
                    val c3 = b[pos + 2].toInt()
                    if (c2 and 0xC0 != 0x80 || c3 and 0xC0 != 0x80) throw UTFDataFormatException("malformed input around byte ${pos + 2}")
                    sb.append((((c and 0x0F) shl 12) or ((c2 and 0x3F) shl 6) or (c3 and 0x3F)).toChar()); pos += 3
                }
                else -> throw UTFDataFormatException("malformed input around byte $pos")
            }
        }
        return sb.toString()
    }

    override fun close() {}
}

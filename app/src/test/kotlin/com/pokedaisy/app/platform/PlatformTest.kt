package com.pokedaisy.app.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pure stand-ins in Streams.kt / Text.kt give exactly what the JDK calls they replace give. */
class PlatformTest {
    private val samples = listOf(
        "", "PIKA", "é", "Poké Ball", "\u0000", "日本語", "한국어", "😀 emoji", "♀♂", String(CharArray(300) { (it * 37).toChar() }),
    )

    @Test fun dataOutputStreamWritesTheJdkBytes() {
        val jdkBytes = java.io.ByteArrayOutputStream()
        val ours = ByteArrayOutputStream(1)
        java.io.DataOutputStream(jdkBytes).use { j ->
            DataOutputStream(ours).use { o ->
                for (v in listOf(0, 1, -1, 0x7F, 0x80, 0xFF, 0x1234, -0x8000, Int.MAX_VALUE, Int.MIN_VALUE)) {
                    j.writeByte(v); o.writeByte(v)
                    j.writeShort(v); o.writeShort(v)
                    j.writeChar(v); o.writeChar(v)
                    j.writeInt(v); o.writeInt(v)
                    j.writeLong(v * 0x1_0000_0001L); o.writeLong(v * 0x1_0000_0001L)
                }
                j.writeBoolean(true); o.writeBoolean(true)
                j.writeBoolean(false); o.writeBoolean(false)
                for (s in samples) {
                    j.writeUTF(s); o.writeUTF(s)
                    j.writeBytes(s); o.writeBytes(s)
                }
                j.write(byteArrayOf(1, 2, 3, 4), 1, 2); o.write(byteArrayOf(1, 2, 3, 4), 1, 2)
            }
        }
        assertArrayEquals(jdkBytes.toByteArray(), ours.toByteArray())
        assertEquals(jdkBytes.size(), ours.size())
    }

    @Test fun writeUtfRefusesWhatTheJdkRefuses() {
        val long = "é".repeat(40_000) // 80,000 bytes of modified UTF-8
        assertThrows(java.io.UTFDataFormatException::class.java) { java.io.DataOutputStream(java.io.ByteArrayOutputStream()).writeUTF(long) }
        assertThrows(UTFDataFormatException::class.java) { DataOutputStream(ByteArrayOutputStream()).writeUTF(long) }
    }

    @Test fun dataInputStreamReadsWhatTheJdkWrote() {
        val bytes = java.io.ByteArrayOutputStream().also { b ->
            java.io.DataOutputStream(b).use { j ->
                j.writeInt(-2); j.writeLong(Long.MIN_VALUE + 5); j.writeShort(0xFFFE); j.writeBoolean(true); j.writeByte(0x90)
                for (s in samples) j.writeUTF(s)
                j.writeInt(42)
            }
        }.toByteArray()
        DataInputStream(bytes).use { d ->
            assertEquals(-2, d.readInt())
            assertEquals(Long.MIN_VALUE + 5, d.readLong())
            assertEquals(0xFFFE, d.readUnsignedShort())
            assertEquals(true, d.readBoolean())
            assertEquals(0x90, d.readUnsignedByte())
            for (s in samples) assertEquals(s, d.readUTF())
            assertEquals(42, d.readInt())
            assertEquals(-1, d.read())
            assertThrows(EOFException::class.java) { d.readByte() }
        }
        DataInputStream(bytes).use { d ->
            val j = java.io.DataInputStream(bytes.inputStream())
            assertEquals(j.readShort(), d.readShort())
            assertEquals(j.readChar(), d.readChar())
            assertEquals(j.readByte(), d.readByte())
        }
    }

    @Test fun charsetsMatchTheJdk() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(String(all, Charsets.US_ASCII), asciiString(all))
        assertEquals(String(all, 10, 200, Charsets.US_ASCII), asciiString(all, 10, 200))
        assertEquals(String(all, Charsets.ISO_8859_1), latin1String(all))
        assertEquals(String(all, 5, 3, Charsets.ISO_8859_1), latin1String(all, 5, 3))
        for (s in samples) {
            assertArrayEquals(s, s.toByteArray(Charsets.US_ASCII), s.asciiBytes())
            assertArrayEquals(s, s.toByteArray(Charsets.UTF_16BE), s.utf16beBytes())
        }
    }

    @Test fun arraycopyMatchesSystemArraycopy() {
        for ((srcPos, destPos, len) in listOf(Triple(0, 0, 10), Triple(2, 5, 4), Triple(5, 2, 4), Triple(0, 1, 9), Triple(1, 0, 9))) {
            val a = ByteArray(10) { it.toByte() }
            val b = a.copyOf()
            System.arraycopy(a, srcPos, a, destPos, len)
            arraycopy(b, srcPos, b, destPos, len)
            assertArrayEquals(a, b)
            val ia = IntArray(10) { it * 3 }
            val ib = ia.copyOf()
            System.arraycopy(ia, srcPos, ia, destPos, len)
            arraycopy(ib, srcPos, ib, destPos, len)
            assertArrayEquals(ia, ib)
        }
    }

    @Test fun binarySearchMatchesArrays() {
        val a = intArrayOf(-5, 0, 3, 3, 9, 12, 100)
        for (key in -10..110) assertEquals("key $key", java.util.Arrays.binarySearch(a, key), a.binarySearchOf(key))
        assertEquals(java.util.Arrays.binarySearch(IntArray(0), 4), IntArray(0).binarySearchOf(4))
    }

    @Test fun hexMatchesTheJdk() {
        for (v in listOf(0, 1, 255, 0x7FFFFFFF, -1, Int.MIN_VALUE, 0x08000000)) assertEquals(Integer.toHexString(v), v.toHexString())
        for (v in listOf(0L, 1L, -1L, Long.MIN_VALUE, 0x123456789AL)) assertEquals(java.lang.Long.toHexString(v), v.toHexString())
    }

    @Test fun decodeIntMatchesIntegerDecode() {
        for (s in listOf("0", "10", "-10", "+7", "0x1F", "0X1f", "-0x10", "#ff", "010", "-010", "0x7FFFFFFF", "-0x80000000", "00"))
            assertEquals(s, Integer.decode(s), decodeInt(s))
        for (s in listOf("", "x", "0x", "--1", "0x-1", "0xG", "08", "0x80000000"))
            assertThrows(s, NumberFormatException::class.java) { decodeInt(s) }.also { assertThrows(NumberFormatException::class.java) { Integer.decode(s) } }
    }

    @Test fun bitSetMatchesJdk() {
        val ours = BitSet(200)
        val jdk = java.util.BitSet(200)
        for (i in listOf(0, 1, 63, 64, 65, 127, 128, 199)) { ours.set(i); jdk.set(i) }
        for (i in listOf(1, 64)) { ours.clear(i); jdk.clear(i) }
        for (i in 0 until 200) assertEquals("bit $i", jdk[i], ours[i])
    }

    @Test fun platformCallsAreTheJdkOnes() {
        val data = ByteArray(5000) { (it * 7 + it / 13).toByte() }
        assertArrayEquals(java.security.MessageDigest.getInstance("SHA-1").digest(data), sha1(data))
        val inflated = java.util.zip.InflaterInputStream(zlibDeflate(data).inputStream()).readBytes()
        assertArrayEquals(data, inflated)
        val big = ByteArray(100_000) { (it * 31 xor it / 7).toByte() } // several of zlibDeflate's 16 KiB pieces
        val whole = java.util.zip.Deflater().run { setInput(big); finish(); val b = ByteArray(200_000); val n = deflate(b); end(); b.copyOf(n) }
        assertArrayEquals(whole, zlibDeflate(big))
        val buf = byteBufferWrap(data, 10, 20)
        assertEquals(10, buf.position())
        assertEquals(30, buf.limit())
        assertEquals(BIG_ENDIAN, buf.order())
        assertEquals("cafe\u0301", normalizeNfd("café"))
    }
}

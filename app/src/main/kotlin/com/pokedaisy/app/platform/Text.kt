package com.pokedaisy.app.platform

/*
 * Small pure helpers for JDK calls with no common-Kotlin equivalent, each giving what the JDK call gave.
 */

/** new String(b, off, len, US_ASCII): bytes past 0x7F read as U+FFFD. */
fun asciiString(b: ByteArray, off: Int = 0, len: Int = b.size - off): String =
    CharArray(len) { val c = b[off + it].toInt() and 0xFF; if (c < 0x80) c.toChar() else '�' }.concatToString()

/** new String(b, off, len, ISO_8859_1): each byte one char. */
fun latin1String(b: ByteArray, off: Int = 0, len: Int = b.size - off): String =
    CharArray(len) { (b[off + it].toInt() and 0xFF).toChar() }.concatToString()

/** s.getBytes(US_ASCII): anything past 0x7F becomes one '?' (a surrogate pair, one character, too). */
fun String.asciiBytes(): ByteArray {
    val out = ByteArrayOutputStream(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i++
        out.write(if (c.code < 0x80) c.code else '?'.code)
        i++
    }
    return out.toByteArray()
}

/** s.getBytes(UTF_16BE). */
fun String.utf16beBytes(): ByteArray = ByteArray(length * 2).also { out ->
    for (i in indices) { val c = this[i].code; out[2 * i] = (c shr 8).toByte(); out[2 * i + 1] = c.toByte() }
}

/** System.arraycopy for the array types the data layer copies (overlapping ranges are fine). */
fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun arraycopy(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

/** Arrays.binarySearch(a, key): the index, or -(insertion point) - 1. */
fun IntArray.binarySearchOf(key: Int): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = this[mid]
        when {
            v < key -> lo = mid + 1
            v > key -> hi = mid - 1
            else -> return mid
        }
    }
    return -(lo + 1)
}

/** Integer.toHexString: the bits as unsigned. */
fun Int.toHexString(): String = toUInt().toString(16)

/** Long.toHexString: the bits as unsigned. */
fun Long.toHexString(): String = toULong().toString(16)

/** Integer.decode: an optional sign, then 0x / 0X / # hex, a leading 0 octal, else decimal. */
fun decodeInt(s: String): Int {
    if (s.isEmpty()) throw NumberFormatException("Zero length string")
    var i = 0
    val neg = s[0] == '-'
    if (s[0] == '-' || s[0] == '+') i++
    val radix = when {
        s.startsWith("0x", i) || s.startsWith("0X", i) -> { i += 2; 16 }
        s.startsWith("#", i) -> { i += 1; 16 }
        s.startsWith("0", i) && s.length > i + 1 -> { i += 1; 8 }
        else -> 10
    }
    val body = s.substring(i)
    if (body.startsWith("-") || body.startsWith("+")) throw NumberFormatException("Sign character in wrong position: $s")
    return (if (neg) "-$body" else body).toInt(radix)
}

/** A fixed-size set of bits (java.util.BitSet's get / set). */
class BitSet(nbits: Int) {
    private val words = LongArray((nbits + 63) ushr 6)
    operator fun get(i: Int): Boolean = (words[i ushr 6] ushr (i and 63)) and 1L != 0L
    fun set(i: Int) { words[i ushr 6] = words[i ushr 6] or (1L shl (i and 63)) }
    fun clear(i: Int) { words[i ushr 6] = words[i ushr 6] and (1L shl (i and 63)).inv() }
}

@file:Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.pokedaisy.app.platform

/** A byte buffer read at absolute positions or in order (java.nio.ByteBuffer on Android). */
expect abstract class ByteBuffer

/** Byte order of a [ByteBuffer] (java.nio.ByteOrder on Android). */
expect class ByteOrder

expect val LITTLE_ENDIAN: ByteOrder
expect val BIG_ENDIAN: ByteOrder

expect fun byteBufferWrap(array: ByteArray): ByteBuffer
expect fun byteBufferWrap(array: ByteArray, offset: Int, length: Int): ByteBuffer
expect fun byteBufferAllocate(capacity: Int): ByteBuffer

expect fun ByteBuffer.order(order: ByteOrder): ByteBuffer
expect fun ByteBuffer.duplicate(): ByteBuffer
expect fun ByteBuffer.capacity(): Int
expect fun ByteBuffer.limit(): Int
expect fun ByteBuffer.remaining(): Int
expect fun ByteBuffer.position(): Int
expect fun ByteBuffer.position(newPosition: Int): ByteBuffer
expect fun ByteBuffer.array(): ByteArray
expect fun ByteBuffer.get(): Byte
expect fun ByteBuffer.get(index: Int): Byte
expect fun ByteBuffer.get(dst: ByteArray): ByteBuffer
expect fun ByteBuffer.get(dst: ByteArray, offset: Int, length: Int): ByteBuffer
expect fun ByteBuffer.getShort(index: Int): Short
expect fun ByteBuffer.getInt(index: Int): Int
expect fun ByteBuffer.getLong(index: Int): Long
expect val ByteBuffer.short: Short
expect val ByteBuffer.int: Int
expect val ByteBuffer.long: Long
expect fun ByteBuffer.put(b: Byte): ByteBuffer
expect fun ByteBuffer.put(src: ByteArray): ByteBuffer
expect fun ByteBuffer.putShort(v: Short): ByteBuffer
expect fun ByteBuffer.putInt(v: Int): ByteBuffer
expect fun ByteBuffer.putLong(v: Long): ByteBuffer

/** CRC-32 as zip computes it (java.util.zip.CRC32 on Android). */
expect open class CRC32()

expect fun CRC32.reset()

expect fun CRC32.update(b: Int)
expect fun CRC32.update(b: ByteArray)
expect fun CRC32.update(b: ByteArray, off: Int, len: Int)
expect val CRC32.value: Long

/** An incremental SHA-1 (MessageDigest "SHA-1" on Android). */
expect class Sha1() {
    fun update(b: ByteArray, off: Int, len: Int)
    fun digest(): ByteArray
}

fun Sha1.update(b: ByteArray) = update(b, 0, b.size)

/** SHA-1 of [b] in one go. */
fun sha1(b: ByteArray): ByteArray = Sha1().apply { update(b) }.digest()

/** [data] as a zlib stream (java.util.zip.Deflater at its default level on Android: the PNGs RomArt writes). */
expect fun zlibDeflate(data: ByteArray): ByteArray

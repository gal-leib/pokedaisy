@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER", "NOTHING_TO_INLINE", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "ACTUAL_WITHOUT_EXPECT")

package com.pokedaisy.app.platform

import kotlin.io.appendText as ktAppendText
import kotlin.io.copyTo as ktCopyTo
import kotlin.io.readBytes as ktReadBytes
import kotlin.io.readLines as ktReadLines
import kotlin.io.readText as ktReadText
import kotlin.io.resolve as ktResolve
import kotlin.io.writeBytes as ktWriteBytes
import kotlin.io.writeText as ktWriteText
import kotlin.collections.getOrPut as ktGetOrPut

/*
 * Android (and any JVM: ui-preview compiles this file too): every actual is the JDK / Android class or call the data
 * layer used before it moved into shared/, so nothing changes on Android. Inside an extension the class's own member
 * wins over the extension, so `this.name` below is java.io.File's getName(), never a recursive call.
 */

// ---- Files

actual typealias File = java.io.File

actual val File.name: String get() = this.name
actual val File.path: String get() = this.path
actual val File.absolutePath: String get() = this.absolutePath
actual val File.parentFile: File? get() = this.parentFile
actual val File.parent: String? get() = this.parent
actual val File.isFile: Boolean get() = this.isFile
actual val File.isDirectory: Boolean get() = this.isDirectory
actual fun File.exists(): Boolean = this.exists()
actual fun File.length(): Long = this.length()
actual fun File.lastModified(): Long = this.lastModified()
actual fun File.delete(): Boolean = this.delete()
actual fun File.mkdirs(): Boolean = this.mkdirs()
actual fun File.renameTo(dest: File): Boolean = this.renameTo(dest)
actual fun File.listFiles(): Array<File>? = this.listFiles()
actual fun File.list(): Array<String>? = this.list()
actual fun File.readBytes(): ByteArray = this.ktReadBytes()
actual fun File.writeBytes(array: ByteArray) = this.ktWriteBytes(array)
actual fun File.readText(): String = this.ktReadText()
actual fun File.writeText(text: String) = this.ktWriteText(text)
actual fun File.appendText(text: String) = this.ktAppendText(text)
actual fun File.readLines(): List<String> = this.ktReadLines()
actual fun File.copyTo(target: File, overwrite: Boolean): File = this.ktCopyTo(target, overwrite)
actual fun File.resolve(relative: String): File = this.ktResolve(relative)
actual fun File.inputStream(): InputStream = java.io.FileInputStream(this)

actual typealias InputStream = java.io.InputStream

actual fun InputStream.read(b: ByteArray, off: Int, len: Int): Int = this.read(b, off, len)

actual typealias RandomAccessFile = java.io.RandomAccessFile

actual fun RandomAccessFile.seek(pos: Long) = this.seek(pos)
actual fun RandomAccessFile.length(): Long = this.length()
actual fun RandomAccessFile.read(b: ByteArray, off: Int, len: Int): Int = this.read(b, off, len)
actual fun RandomAccessFile.read(b: ByteArray): Int = this.read(b)
actual fun RandomAccessFile.readFully(b: ByteArray) = this.readFully(b)
actual fun RandomAccessFile.readFully(b: ByteArray, off: Int, len: Int) = this.readFully(b, off, len)

actual fun mapReadOnly(file: RandomAccessFile): ByteBuffer =
    file.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length()).order(java.nio.ByteOrder.LITTLE_ENDIAN)

actual typealias IOException = java.io.IOException

// ---- Bytes

actual typealias ByteBuffer = java.nio.ByteBuffer
actual typealias ByteOrder = java.nio.ByteOrder

actual val LITTLE_ENDIAN: ByteOrder get() = java.nio.ByteOrder.LITTLE_ENDIAN
actual val BIG_ENDIAN: ByteOrder get() = java.nio.ByteOrder.BIG_ENDIAN

actual fun byteBufferWrap(array: ByteArray): ByteBuffer = java.nio.ByteBuffer.wrap(array)
actual fun byteBufferWrap(array: ByteArray, offset: Int, length: Int): ByteBuffer = java.nio.ByteBuffer.wrap(array, offset, length)
actual fun byteBufferAllocate(capacity: Int): ByteBuffer = java.nio.ByteBuffer.allocate(capacity)

actual fun ByteBuffer.order(order: ByteOrder): ByteBuffer = this.order(order)
actual fun ByteBuffer.duplicate(): ByteBuffer = this.duplicate()
actual fun ByteBuffer.capacity(): Int = this.capacity()
actual fun ByteBuffer.limit(): Int = this.limit()
actual fun ByteBuffer.remaining(): Int = this.remaining()
actual fun ByteBuffer.position(): Int = this.position()
actual fun ByteBuffer.position(newPosition: Int): ByteBuffer = this.position(newPosition)
actual fun ByteBuffer.array(): ByteArray = this.array()
actual fun ByteBuffer.get(): Byte = this.get()
actual fun ByteBuffer.get(index: Int): Byte = this.get(index)
actual fun ByteBuffer.get(dst: ByteArray): ByteBuffer = this.get(dst)
actual fun ByteBuffer.get(dst: ByteArray, offset: Int, length: Int): ByteBuffer = this.get(dst, offset, length)
actual fun ByteBuffer.getShort(index: Int): Short = this.getShort(index)
actual fun ByteBuffer.getInt(index: Int): Int = this.getInt(index)
actual fun ByteBuffer.getLong(index: Int): Long = this.getLong(index)
actual val ByteBuffer.short: Short get() = this.short
actual val ByteBuffer.int: Int get() = this.int
actual val ByteBuffer.long: Long get() = this.long
actual fun ByteBuffer.put(b: Byte): ByteBuffer = this.put(b)
actual fun ByteBuffer.put(src: ByteArray): ByteBuffer = this.put(src)
actual fun ByteBuffer.putShort(v: Short): ByteBuffer = this.putShort(v)
actual fun ByteBuffer.putInt(v: Int): ByteBuffer = this.putInt(v)
actual fun ByteBuffer.putLong(v: Long): ByteBuffer = this.putLong(v)

actual typealias CRC32 = java.util.zip.CRC32

actual fun CRC32.reset() = this.reset()
actual fun CRC32.update(b: Int) = this.update(b)
actual fun CRC32.update(b: ByteArray) = this.update(b)
actual fun CRC32.update(b: ByteArray, off: Int, len: Int) = this.update(b, off, len)
actual val CRC32.value: Long get() = this.value

actual class Sha1 actual constructor() {
    private val md = java.security.MessageDigest.getInstance("SHA-1")
    actual fun update(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
    actual fun digest(): ByteArray = md.digest()
}

actual fun zlibDeflate(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val def = java.util.zip.Deflater()
    try {
        def.setInput(data)
        def.finish()
        val chunk = ByteArray(64 * 1024)
        while (!def.finished()) out.write(chunk, 0, def.deflate(chunk))
    } finally {
        def.end()
    }
    return out.toByteArray()
}

// ---- Concurrency

actual typealias ConcurrentHashMap<K, V> = java.util.concurrent.ConcurrentHashMap<K, V>

actual fun <K, V> ConcurrentHashMap<K, V>.putIfAbsent(key: K, value: V): V? = this.putIfAbsent(key, value)

actual inline fun <K, V> ConcurrentHashMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V =
    (this as java.util.concurrent.ConcurrentMap<K, V>).ktGetOrPut(key, defaultValue)

actual typealias ConcurrentLinkedQueue<E> = java.util.concurrent.ConcurrentLinkedQueue<E>

actual fun <E> ConcurrentLinkedQueue<E>.offer(e: E): Boolean = this.offer(e)
actual fun <E> ConcurrentLinkedQueue<E>.poll(): E? = this.poll()

actual fun <T> concurrentSet(): MutableSet<T> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<T, Boolean>())

actual fun <K, V> identityHashMap(): MutableMap<K, V> = java.util.IdentityHashMap()

actual typealias PriorityQueue<E> = java.util.PriorityQueue<E>

actual fun <E> PriorityQueue<E>.poll(): E? = this.poll()

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)

actual typealias Runnable = java.lang.Runnable
actual typealias Executor = java.util.concurrent.Executor

actual fun singleThreadExecutor(name: String): Executor =
    java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }

actual fun startDaemonThread(name: String, block: () -> Unit) {
    Thread(block, name).apply { isDaemon = true; start() }
}

// ---- System

actual fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)

actual fun nanoTime(): Long = System.nanoTime()
actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun localHour(): Int = java.time.LocalTime.now().hour
actual fun localMinute(): Int = java.time.LocalTime.now().minute
actual fun localMonth(): Int = java.time.LocalDate.now().monthValue

actual object Log {
    actual fun d(tag: String, msg: String): Int = android.util.Log.d(tag, msg)
    actual fun i(tag: String, msg: String): Int = android.util.Log.i(tag, msg)
    actual fun w(tag: String, msg: String): Int = android.util.Log.w(tag, msg)
    actual fun w(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.w(tag, msg, tr)
    actual fun e(tag: String, msg: String): Int = android.util.Log.e(tag, msg)
    actual fun e(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.e(tag, msg, tr)
}

actual typealias Locale = java.util.Locale

actual val Locale.language: String get() = this.language
actual val Locale.country: String get() = this.country
actual fun defaultLocale(): Locale = java.util.Locale.getDefault()

actual typealias Random = java.util.Random

actual fun Random.nextInt(bound: Int): Int = this.nextInt(bound)
actual fun Random.nextDouble(): Double = this.nextDouble()

actual fun normalizeNfd(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)

// ---- Pictures

actual typealias Bitmap = android.graphics.Bitmap

actual val Bitmap.width: Int get() = this.width
actual val Bitmap.height: Int get() = this.height

actual fun createBitmap(argb: IntArray, width: Int, height: Int): Bitmap =
    android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888)

actual fun decodeBitmapFile(path: String): Bitmap? = android.graphics.BitmapFactory.decodeFile(path)

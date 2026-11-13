package com.pokedaisy.app.platform

/*
 * The platform APIs the companion's data code (companion/data, companion/i18n) uses, gathered in one package so that
 * code can later be compiled for iOS as well (docs/IPAD_MIGRATION.md, PRs 4-6). Here, on Android, every name is the
 * very JDK / Android class or call the code uses today - a typealias or a one-line forward - so moving a file onto
 * this package changes nothing it does. When the shared module arrives these become `actual`s of `expect`
 * declarations, and iOS gets its own implementations.
 *
 * Streams.kt and Text.kt hold the pure Kotlin stand-ins (no JDK at all), checked against the JDK in PlatformTest.
 */

// ---- Files

typealias File = java.io.File
typealias InputStream = java.io.InputStream
typealias RandomAccessFile = java.io.RandomAccessFile
typealias IOException = java.io.IOException

/** The whole of [file] as a little-endian [ByteBuffer], memory-mapped. */
fun mapReadOnly(file: RandomAccessFile): ByteBuffer =
    file.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length()).order(java.nio.ByteOrder.LITTLE_ENDIAN)

// ---- Bytes

typealias ByteBuffer = java.nio.ByteBuffer
typealias ByteOrder = java.nio.ByteOrder

val LITTLE_ENDIAN: ByteOrder get() = java.nio.ByteOrder.LITTLE_ENDIAN
val BIG_ENDIAN: ByteOrder get() = java.nio.ByteOrder.BIG_ENDIAN

fun byteBufferWrap(array: ByteArray): ByteBuffer = java.nio.ByteBuffer.wrap(array)
fun byteBufferWrap(array: ByteArray, offset: Int, length: Int): ByteBuffer = java.nio.ByteBuffer.wrap(array, offset, length)
fun byteBufferAllocate(capacity: Int): ByteBuffer = java.nio.ByteBuffer.allocate(capacity)

/** CRC-32 as zip computes it. */
typealias CRC32 = java.util.zip.CRC32

/** An incremental SHA-1. */
class Sha1 {
    private val md = java.security.MessageDigest.getInstance("SHA-1")
    fun update(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
    fun update(b: ByteArray) = md.update(b, 0, b.size)
    fun digest(): ByteArray = md.digest()
}

/** SHA-1 of [b] in one go. */
fun sha1(b: ByteArray): ByteArray = Sha1().apply { update(b) }.digest()

/** [data] as a zlib stream, java.util.zip.Deflater at its default level (the PNGs RomArt writes). Fed 16 KiB at a
 * time: each deflate() holds a JNI critical lock (stalling GC while the game runs) for as long as its input lasts. */
fun zlibDeflate(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val def = java.util.zip.Deflater()
    try {
        val chunk = ByteArray(8192)
        var at = 0
        while (!def.finished()) {
            if (def.needsInput()) {
                if (at < data.size) {
                    val n = minOf(16 shl 10, data.size - at)
                    def.setInput(data, at, n)
                    at += n
                } else {
                    def.finish()
                }
            }
            out.write(chunk, 0, def.deflate(chunk))
        }
    } finally {
        def.end()
    }
    return out.toByteArray()
}

// ---- Concurrency

typealias ConcurrentHashMap<K, V> = java.util.concurrent.ConcurrentHashMap<K, V>
typealias ConcurrentLinkedQueue<E> = java.util.concurrent.ConcurrentLinkedQueue<E>
typealias PriorityQueue<E> = java.util.PriorityQueue<E>
typealias Runnable = java.lang.Runnable
typealias Executor = java.util.concurrent.Executor

/** A set safe to share between threads (a ConcurrentHashMap's key set). */
fun <T> concurrentSet(): MutableSet<T> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<T, Boolean>())

/** A map that compares keys by identity. */
fun <K, V> identityHashMap(): MutableMap<K, V> = java.util.IdentityHashMap()

/** Runs [block] holding [lock]'s monitor (Kotlin's own synchronized). */
inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)

/** One background thread running tasks in order, a daemon named [name]. */
fun singleThreadExecutor(name: String): Executor =
    java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }

/** Starts [block] on a new daemon thread named [name]. */
fun startDaemonThread(name: String, block: () -> Unit) {
    Thread(block, name).apply { isDaemon = true; start() }
}

// ---- System

/** printf-style formatting in the default locale (java.lang.String.format, as the stdlib's own `format`). */
fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)

fun nanoTime(): Long = System.nanoTime()
fun currentTimeMillis(): Long = System.currentTimeMillis()

/** The device clock's hour (0-23), minute (0-59) and month (1-12) in its own time zone. */
fun localHour(): Int = java.time.LocalTime.now().hour
fun localMinute(): Int = java.time.LocalTime.now().minute
fun localMonth(): Int = java.time.LocalDate.now().monthValue

/** The platform log (android.util.Log). */
object Log {
    fun d(tag: String, msg: String): Int = android.util.Log.d(tag, msg)
    fun i(tag: String, msg: String): Int = android.util.Log.i(tag, msg)
    fun w(tag: String, msg: String): Int = android.util.Log.w(tag, msg)
    fun w(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.w(tag, msg, tr)
    fun e(tag: String, msg: String): Int = android.util.Log.e(tag, msg)
    fun e(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.e(tag, msg, tr)
}

typealias Locale = java.util.Locale

/** The device's locale. */
fun defaultLocale(): Locale = java.util.Locale.getDefault()

/** java.util.Random's generator: a seed gives the same numbers on every platform. */
typealias Random = java.util.Random

/** [s] in Unicode normalization form D (accents split off their letters). */
fun normalizeNfd(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)

// ---- Pictures

/** A decoded picture the UI draws. */
typealias Bitmap = android.graphics.Bitmap

/** An ARGB_8888 picture of [width] x [height] from [argb], row by row. */
fun createBitmap(argb: IntArray, width: Int, height: Int): Bitmap =
    android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888)

/** The picture in the image file at [path] (PNG), null when it can't be read. */
fun decodeBitmapFile(path: String): Bitmap? = android.graphics.BitmapFactory.decodeFile(path)

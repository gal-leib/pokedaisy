@file:Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.pokedaisy.app.platform

/*
 * The few platform APIs the shared data layer uses, as expect declarations. On Android each one is the very
 * JDK class or call the code used before it moved here (`actual typealias File = java.io.File`, ...), so the app
 * passes its own java.io.File / ByteBuffer / Random into these functions unchanged and nothing behaves differently.
 * Members are expect *extensions* over a member-less expect class: on the JVM the class's own member wins inside
 * the actual, so an actual is a one-line forward. iOS gets its own implementations (iosMain).
 */

/** A path on the device's file system: java.io.File on Android. */
expect open class File(pathname: String) {
    constructor(parent: File?, child: String)
    constructor(parent: String?, child: String)
}

expect val File.name: String
expect val File.path: String
expect val File.absolutePath: String
expect val File.parentFile: File?
expect val File.parent: String?
expect val File.isFile: Boolean
expect val File.isDirectory: Boolean
expect fun File.exists(): Boolean
expect fun File.length(): Long
expect fun File.lastModified(): Long
expect fun File.delete(): Boolean
expect fun File.mkdirs(): Boolean
expect fun File.renameTo(dest: File): Boolean
expect fun File.listFiles(): Array<File>?
expect fun File.list(): Array<String>?
expect fun File.readBytes(): ByteArray
expect fun File.writeBytes(array: ByteArray)
expect fun File.readText(): String
expect fun File.writeText(text: String)
expect fun File.appendText(text: String)
expect fun File.readLines(): List<String>
expect fun File.copyTo(target: File, overwrite: Boolean = false): File
expect fun File.resolve(relative: String): File
expect fun File.inputStream(): InputStream

/** A byte stream being read (java.io.InputStream on Android). */
expect abstract class InputStream : AutoCloseable

/** Reads up to [len] bytes into [b] from [off]; -1 at the end. */
expect fun InputStream.read(b: ByteArray, off: Int, len: Int): Int

/** A file opened for random reads (java.io.RandomAccessFile on Android); [mode] is "r" here. */
expect open class RandomAccessFile(file: File, mode: String) : AutoCloseable

expect fun RandomAccessFile.seek(pos: Long)
expect fun RandomAccessFile.length(): Long
expect fun RandomAccessFile.read(b: ByteArray, off: Int, len: Int): Int
expect fun RandomAccessFile.read(b: ByteArray): Int
expect fun RandomAccessFile.readFully(b: ByteArray)
expect fun RandomAccessFile.readFully(b: ByteArray, off: Int, len: Int)

/** The whole of [file] as a little-endian [ByteBuffer], memory-mapped where the platform can. */
expect fun mapReadOnly(file: RandomAccessFile): ByteBuffer

/** Thrown by file and stream calls that fail (java.io.IOException on Android). */
expect open class IOException(message: String?) : Exception {
    constructor()
}

package com.pokedaisy.app

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import okio.Path.Companion.toOkioPath

internal fun androidFfMusicCache(filesDir: File, crc: String, gameBoy: Boolean = false): FfMusicCache =
    FfMusicCache(filesDir.toOkioPath(), crc, gameBoy,
        freeBytes = { filesDir.usableSpace },
        log = { Log.i("pokedaisy", it) })

internal fun FfMusicCache.prepareOnAndroid(waiting: () -> Boolean) =
    prepare(waiting,
        keepLegacyTime = { wav, flac -> flac.toFile().setLastModified(wav.toFile().lastModified()) },
        rest = Thread::sleep)

/** Writes the interleaved stereo PCM used by Android's short game sounds. */
internal fun writeWav(file: File, pcm: ShortArray, len: Int, sampleRate: Int, from: Int = 0) {
    val dataBytes = len * 2
    val tmp = File(file.parentFile, "${file.name}.tmp")
    RandomAccessFile(tmp, "rw").use { raf ->
        raf.setLength(0)
        raf.writeAsciiLE("RIFF")
        raf.writeIntLE(36 + dataBytes)
        raf.writeAsciiLE("WAVE")
        raf.writeAsciiLE("fmt ")
        raf.writeIntLE(16)          // fmt chunk size
        raf.writeShortLE(1)         // PCM
        raf.writeShortLE(2)         // stereo
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(sampleRate * 2 * 2)   // byte rate
        raf.writeShortLE(4)         // block align (channels * bytes/sample)
        raf.writeShortLE(16)        // bits per sample
        raf.writeAsciiLE("data")
        raf.writeIntLE(dataBytes)
        val bytes = ByteArray(dataBytes)
        for (i in 0 until len) {
            val s = pcm[from + i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
    }
    tmp.renameTo(file)   // atomic-ish swap
}

private fun RandomAccessFile.writeAsciiLE(s: String) = write(s.toByteArray(Charsets.US_ASCII))
private fun RandomAccessFile.writeIntLE(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
}
private fun RandomAccessFile.writeShortLE(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF)
}

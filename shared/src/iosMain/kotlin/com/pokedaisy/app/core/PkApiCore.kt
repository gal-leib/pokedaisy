@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import com.pokedaisy.app.companion.data.EmulatorCore
import com.pokedaisy.app.core.pkapi.pk_api_audio
import com.pokedaisy.app.core.pkapi.pk_api_deinit
import com.pokedaisy.app.core.pkapi.pk_api_finish_save
import com.pokedaisy.app.core.pkapi.pk_api_init
import com.pokedaisy.app.core.pkapi.pk_api_platform
import com.pokedaisy.app.core.pkapi.pk_api_read
import com.pokedaisy.app.core.pkapi.pk_api_rom_code
import com.pokedaisy.app.core.pkapi.pk_api_rom_read
import com.pokedaisy.app.core.pkapi.pk_api_rom_size
import com.pokedaisy.app.core.pkapi.pk_api_run_frame
import com.pokedaisy.app.core.pkapi.pk_api_set_audio_rate
import com.pokedaisy.app.core.pkapi.pk_api_set_keys
import com.pokedaisy.app.core.pkapi.pk_api_video
import com.pokedaisy.app.core.pkapi.pk_api_video_height
import com.pokedaisy.app.core.pkapi.pk_api_video_width
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * The iPad's emulator core: a GBA or Game Boy game on mGBA through the plain C API in app/src/main/cpp/pk_api.h (built
 * by shared/native/build_ios_core.sh, reached through cinterop), the same code Android's JNI bridge calls. [start] opens
 * a game and makes it the data code's [EmulatorCore.current]; [stop] closes it.
 *
 * Threads, as pk_api.h says: one thread (the game's) starts, runs and stops it; the reads may come from any thread.
 */
object PkApiCore : EmulatorCore {
    /** The keys as [setKeys] takes them (pk_api.h's PK_API_KEY_*; a Game Boy game ignores L / R). */
    const val KEY_A = 1 shl 0
    const val KEY_B = 1 shl 1
    const val KEY_SELECT = 1 shl 2
    const val KEY_START = 1 shl 3
    const val KEY_RIGHT = 1 shl 4
    const val KEY_LEFT = 1 shl 5
    const val KEY_UP = 1 shl 6
    const val KEY_DOWN = 1 shl 7
    const val KEY_R = 1 shl 8
    const val KEY_L = 1 shl 9

    /** The rate [audio]'s samples come at, unless [setAudioRate] moves it. */
    const val SAMPLE_RATE = 48000

    /** The longest read, as on Android (MgbaCore's pkReadBytes). */
    private const val MAX_READ = 1 shl 24

    /** Starts the game at [romPath] (a .gba, .gb or .gbc), closing any before it; [savePath] is its battery save. */
    fun start(romPath: String, savePath: String?): Boolean {
        val ok = pk_api_init(romPath, savePath)
        EmulatorCore.current = if (ok) this else EmulatorCore.None
        return ok
    }

    /** Closes the game, its save written. */
    fun stop() {
        if (EmulatorCore.current === this) EmulatorCore.current = EmulatorCore.None
        pk_api_deinit()
    }

    fun runFrame() = pk_api_run_frame()

    /** The keys held from the next frame on ([KEY_A] ...). */
    fun setKeys(keys: Int) = pk_api_set_keys(keys.toUInt())

    /** The last frame: [videoWidth] x [videoHeight] pixels of 4 bytes (R, G, B, unused), rows packed; null with no game. */
    fun video(): COpaquePointer? = pk_api_video()
    val videoWidth: Int get() = pk_api_video_width()
    val videoHeight: Int get() = pk_api_video_height()

    /** The last frame's sound into [dst]: interleaved stereo samples; returns how many (at most [dst]'s size). */
    fun audio(dst: ShortArray): Int = memScoped {
        val count = alloc<IntVar>()
        val samples = pk_api_audio(count.ptr) ?: return 0
        val n = minOf(count.value, dst.size)
        for (i in 0 until n) dst[i] = samples[i]
        n
    }

    fun setAudioRate(rate: Double) = pk_api_set_audio_rate(rate)

    /** Lets a save the game is writing finish (up to [maxFrames] frames): before [stop] or a state. */
    fun finishSave(maxFrames: Int): Int = pk_api_finish_save(maxFrames)

    override fun readBytes(addr: Long, len: Int): ByteArray? {
        if (len <= 0 || len > MAX_READ) return null
        val out = ByteArray(len)
        return if (readInto(addr, out)) out else null
    }

    override fun readInto(addr: Long, dst: ByteArray): Boolean {
        if (dst.isEmpty() || dst.size > MAX_READ) return false
        return dst.usePinned { pk_api_read(addr.toUInt(), it.addressOf(0).reinterpret(), dst.size) }
    }

    override fun romCode(): String? = memScoped {
        val code = allocArray<ByteVar>(5)
        if (pk_api_rom_code(code)) code.toKString() else null
    }

    override fun romSize(): Long = pk_api_rom_size()

    override fun platform(): Int = pk_api_platform()

    override fun romRead(off: Long, len: Int): ByteArray? {
        if (off < 0 || len <= 0 || len > MAX_READ) return null
        val out = ByteArray(len)
        val ok = out.usePinned { pk_api_rom_read(off, it.addressOf(0).reinterpret(), len) }
        return if (ok) out else null
    }
}

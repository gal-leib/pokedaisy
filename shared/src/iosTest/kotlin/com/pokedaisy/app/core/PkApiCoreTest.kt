@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import com.pokedaisy.app.companion.data.EmulatorCore
import com.pokedaisy.app.companion.data.InProcessReader
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PkApiCore] in the iPad simulator, on linux/make_diagnostic.py's ROMs (no commercial content), which
 * shared/build.gradle.kts writes and names in PK_DIAGNOSTIC_DIR: the data code reads the running game through it.
 */
class PkApiCoreTest {
    private val dir = assertNotNull(getenv("PK_DIAGNOSTIC_DIR")?.toKString(), "PK_DIAGNOSTIC_DIR not set").trimEnd('/')

    @AfterTest
    fun close() = PkApiCore.stop()

    @Test
    fun gameBoyRunsAndIsRead() {
        val save = "$dir/pk_api_ios.sav"
        unlink(save)
        assertTrue(PkApiCore.start("$dir/diagnostic.gb", save))
        assertSame(PkApiCore, EmulatorCore.current)
        assertEquals(EmulatorCore.PLATFORM_GB, PkApiCore.platform())
        assertEquals(160, PkApiCore.videoWidth)
        assertEquals(144, PkApiCore.videoHeight)
        assertEquals(32768L, PkApiCore.romSize())
        assertEquals("", PkApiCore.romCode())
        assertContentEquals("PD-TEST-GB".encodeToByteArray(), PkApiCore.romRead(0x134, 10))
        assertNull(PkApiCore.romRead(32768 - 4, 10))

        repeat(30) { PkApiCore.runFrame() }
        // The ROM adds one to WRAM 0xC000 each frame; read the way the companion's decoders read.
        val before = InProcessReader.readCoreMemory(0xC000, 1)[0]
        val samples = ShortArray(8192)
        var heard = false
        repeat(10) {
            PkApiCore.runFrame()
            val n = PkApiCore.audio(samples)
            assertTrue(n in 1000..4000, "$n samples a frame")
            if ((0 until n).any { samples[it] != 0.toShort() }) heard = true
        }
        val after = InProcessReader.readCoreMemory(0xC000, 1)[0]
        assertEquals(10, (after - before) and 0xFF)
        assertTrue(heard, "no sound from the tone")

        PkApiCore.finishSave(120)
        PkApiCore.stop()
        assertSame(EmulatorCore.None, EmulatorCore.current)
        assertEquals(-1, PkApiCore.platform())
        assertNull(PkApiCore.readBytes(0xC000, 1))
    }

    @Test
    fun gbaRunsAndIsRead() {
        assertTrue(PkApiCore.start("$dir/diagnostic.gba", null))
        assertEquals(EmulatorCore.PLATFORM_GBA, PkApiCore.platform())
        assertEquals("BPRE", PkApiCore.romCode())
        assertContentEquals("BPRE".encodeToByteArray(), PkApiCore.readBytes(0x080000AC, 4))
        assertNull(PkApiCore.romRead(0, 4))
        repeat(10) { PkApiCore.runFrame() }
        val px = assertNotNull(PkApiCore.video()).reinterpret<ByteVar>()
        assertEquals(240, PkApiCore.videoWidth)
        assertEquals(160, PkApiCore.videoHeight)
        // Mode 3 stripes: the first two pixels differ.
        assertNotEquals((0..2).map { px[it] }, (4..6).map { px[it] })
    }

    @Test
    fun audioRatesSupportSlowMotionAndFastForwardOnBothCores() {
        val samples = ShortArray(16384)
        for (rom in listOf("diagnostic.gba", "diagnostic.gb")) {
            assertTrue(PkApiCore.start("$dir/$rom", null))
            // Let the Game Boy boot and enable its LCD/audio before timing full frames.
            repeat(60) { PkApiCore.runFrame() }
            for (speed in listOf(0.25, 1.0, 2.0, 10.0)) {
                PkApiCore.setAudioRate(PkApiCore.SAMPLE_RATE / speed)
                repeat(4) { PkApiCore.runFrame() }
                // The core mixes sound in blocks, so compare a half second rather than one frame's boundary.
                var count = 0
                repeat(30) {
                    PkApiCore.runFrame()
                    count += PkApiCore.audio(samples)
                }
                val expected = PkApiCore.SAMPLE_RATE / speed / com.pokedaisy.app.game.FramePacer.FPS * 2 * 30
                assertTrue(kotlin.math.abs(count - expected) < expected * 0.01 + 128,
                    "$rom at ${speed}x: $count samples, expected $expected")
            }
            PkApiCore.stop()
        }
    }

    @Test
    fun noGameReadsNothing() {
        PkApiCore.stop()
        assertNull(PkApiCore.readBytes(0x02000000, 4))
        assertNull(PkApiCore.romCode())
        assertEquals(0L, PkApiCore.romSize())
        assertNull(PkApiCore.video())
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.WideCamera
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.MetalScreenEffects
import com.pokedaisy.app.companion.MetalSmoothing
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.game.GameRenderer
import com.pokedaisy.app.game.PictureEffects
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.getenv
import kotlin.test.*

class SmoothingTest {
    private fun renderer(): GameRenderer? = GameRenderer.create()?.apply { stretch = true }.also {
        if (it == null) println("SmoothingTest: no Metal device; GPU checks not run")
    }
    private fun program(): PictureEffects.Program {
        val dir = assertNotNull(getenv("PK_SHADER_DIR")?.toKString(), "PK_SHADER_DIR not set")
        return MetalSmoothing.xbrz(FileSystem.SYSTEM.read("$dir/xbrz-freescale.glsl".toPath()) { readUtf8() })
    }
    private fun upload(r: GameRenderer, bytes: ByteArray, w: Int, h: Int, expanded: Boolean = false) {
        bytes.usePinned { assertTrue(r.uploadFrame(it.addressOf(0), w, h,
            if (expanded) WideCamera.Window(0, 0, w, h, 0f, 0f, 8f) else null)) }
    }
    private fun diagonal(w: Int, h: Int) = ByteArray(w * h * 4) { i ->
        val x = i / 4 % w; val y = i / 4 / w
        if (i % 4 == 3) 0 else if (x == y || x == y + 1) (-1).toByte() else 0
    }

    @Test fun originalXbrzSmoothsDiagonalEdgesAndOffRestoresSharpPixels() = runBlocking {
        val r = renderer() ?: return@runBlocking
        upload(r, diagonal(6, 6), 6, 6)
        val sharp = renderGamePixels(r, 48, 48)
        assertTrue(sharp.indices.filter { it % 4 != 3 }.all { sharp[it] == 0.toByte() || sharp[it] == (-1).toByte() })
        assertTrue(r.configureEffects(PictureEffects(smoothing = program())))
        val smooth = renderGamePixels(r, 48, 48)
        assertTrue(smooth.indices.filter { it % 4 != 3 }.any { smooth[it] != 0.toByte() && smooth[it] != (-1).toByte() }, "xBRZ must interpolate diagonal edges")
        assertFalse(sharp.contentEquals(smooth))
        assertTrue(r.configureEffects(PictureEffects()))
        assertContentEquals(sharp, renderGamePixels(r, 48, 48), "OFF must drop all smoothing state")
        println("SmoothingTest: original xBRZ draws blended diagonals with Metal and OFF restores sharp pixels")
    }

    @Test fun smoothingCombinesWithEveryFilterColorsAndUnlit() = runBlocking {
        val r = renderer() ?: return@runBlocking
        upload(r, diagonal(6, 6), 6, 6)
        val program = program()
        for (filter in ScreenFilter.entries) {
            assertTrue(r.configureEffects(MetalScreenEffects.picture(filter, GridStrength.MEDIUM, true, true).copy(smoothing = program)), filter.name)
            val pixels = renderGamePixels(r, 53, 47)
            assertTrue(pixels.indices.filter { it % 4 != 3 }.any { pixels[it] != 0.toByte() }, filter.name)
            assertTrue(pixels.indices.filter { it % 4 == 3 }.all { pixels[it] == (-1).toByte() }, filter.name)
        }
    }

    @Test fun resizedExpandedCapacityHasTheSameSmoothedPixelsAsAnExactTexture() = runBlocking {
        val reused = renderer() ?: return@runBlocking
        val settings = MetalScreenEffects.picture(ScreenFilter.CRT, GridStrength.MEDIUM, true, true).copy(smoothing = program())
        assertTrue(reused.configureEffects(settings))
        for (w in listOf(2, 3, 2)) {
            val fresh = assertNotNull(renderer())
            assertTrue(fresh.configureEffects(settings))
            val pixels = diagonal(w, 3)
            upload(reused, pixels, w, 3, true); upload(fresh, pixels, w, 3)
            assertContentEquals(renderGamePixels(fresh, w * 8, 24), renderGamePixels(reused, w * 8, 24), "logical width $w")
        }
    }
}

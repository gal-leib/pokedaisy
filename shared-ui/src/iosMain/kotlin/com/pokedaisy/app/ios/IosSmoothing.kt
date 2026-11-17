package com.pokedaisy.app.ios

import com.pokedaisy.app.ScreenShaders
import com.pokedaisy.app.companion.MetalSmoothing
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.game.PictureEffects
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSLog

/** Reads original bundled assets once; common presets own sizing and Metal compiles off the draw thread. */
internal object IosSmoothing {
    private fun load(mode: Smoothing): List<PictureEffects.SmoothPass>? = runCatching {
        val preset = ScreenShaders.smoothingPasses(mode)?.singleOrNull() ?: return@runCatching null
        val path = NSBundle.mainBundle.pathForResource(preset.asset.substringBeforeLast('.'), "glsl", "shaders")
            ?: return@runCatching null
        val source = FileSystem.SYSTEM.read(path.toPath()) { readUtf8() }
        val program = when (mode) {
            Smoothing.XBRZ -> MetalSmoothing.xbrz(source)
            Smoothing.XBR -> MetalSmoothing.xbr(source)
            else -> return@runCatching null
        }
        listOf(PictureEffects.SmoothPass(program, preset.size))
    }.onFailure { NSLog("PokeDaisy: smoothing asset failed: %@", it.message) }.getOrNull()

    private val xbrz by lazy { load(Smoothing.XBRZ) }
    private val xbr by lazy { load(Smoothing.XBR) }
    val modes get() = listOf(Smoothing.OFF) + listOf(Smoothing.XBRZ, Smoothing.XBR).filter { program(it).isNotEmpty() }
    fun program(mode: Smoothing): List<PictureEffects.SmoothPass> = when (mode) {
        Smoothing.XBRZ -> xbrz.orEmpty()
        Smoothing.XBR -> xbr.orEmpty()
        else -> emptyList()
    }
}

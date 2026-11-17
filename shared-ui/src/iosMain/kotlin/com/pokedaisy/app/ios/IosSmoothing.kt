package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.MetalSmoothing
import com.pokedaisy.app.companion.Smoothing
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSLog

/** Reads the bundled, original shader once; GameRenderer compiles its program off the draw thread. */
internal object IosSmoothing {
    private val xbrz by lazy {
        runCatching {
            val path = NSBundle.mainBundle.pathForResource("xbrz-freescale", "glsl", "shaders") ?: return@runCatching null
            MetalSmoothing.xbrz(FileSystem.SYSTEM.read(path.toPath()) { readUtf8() })
        }.onFailure { NSLog("PokeDaisy: smoothing asset failed: %@", it.message) }.getOrNull()
    }
    val modes get() = if (xbrz != null) listOf(Smoothing.OFF, Smoothing.XBRZ) else listOf(Smoothing.OFF)
    fun program(mode: Smoothing) = if (mode == Smoothing.XBRZ) xbrz else null
}

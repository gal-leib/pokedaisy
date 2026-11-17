package com.pokedaisy.app.game

import kotlin.math.roundToInt

/** Renderer programs supplied by the shared presentation layer; no frame transport through Compose. */
data class PictureEffects(
    val colors: Program? = null,
    val unlit: Program? = null,
    val filter: Program? = null,
    val prescale: Boolean = false,
    val gridX: Float = 0f,
    val gridY: Float = 0f,
) {
    data class Program(val name: String, val fragment: String)
}

/** Android GL and Metal choose the same whole-pixel filter size and reflective-screen pass size. */
data class PicturePassSizes(val kx: Int, val ky: Int, val integerW: Int, val integerH: Int, val unlitW: Int, val unlitH: Int)

fun picturePassSizes(frameW: Int, frameH: Int, drawW: Float, drawH: Float, prescale: Boolean): PicturePassSizes {
    val kx = maxOf(1, (drawW / frameW).toInt())
    val ky = maxOf(1, (drawH / frameH).toInt())
    return PicturePassSizes(kx, ky, frameW * kx, frameH * ky,
        if (prescale) frameW * kx else maxOf(1, drawW.roundToInt()),
        if (prescale) frameH * ky else maxOf(1, drawH.roundToInt()))
}

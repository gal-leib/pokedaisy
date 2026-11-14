package com.pokedaisy.app.companion.data

/** pk_wide.h's field bits, shared by Android's JNI and the portable core. Unknown games return zero. */
object GameField {
    const val KNOWN = 1
    const val LOCKED = 2
    const val BATTLE = 4
    const val TEXT = 8
    const val BIKE = 16
    const val SURF = 64
    const val AVATAR = 128
    /** DS only; GBA uses FfMenuWatch. */
    const val MENU = 512
}

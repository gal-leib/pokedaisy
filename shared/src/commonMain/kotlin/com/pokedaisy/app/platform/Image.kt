@file:Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.pokedaisy.app.platform

/** A decoded picture the UI draws (android.graphics.Bitmap on Android, so the app keeps using it as before). */
expect class Bitmap

expect val Bitmap.width: Int
expect val Bitmap.height: Int

/** An ARGB_8888 picture of [width] x [height] from [argb], row by row. */
expect fun createBitmap(argb: IntArray, width: Int, height: Int): Bitmap

/** The picture in the image file at [path] (PNG), null when it can't be read. */
expect fun decodeBitmapFile(path: String): Bitmap?

package com.pokedaisy.app.companion.ui

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpandedPictureBoundsTest {
    @Test fun aSkinWindowOwnsThePictureEvenWithAnExpandedOrStretchedPreference() {
        val window = Rect(120f, 40f, 720f, 360f)
        assertEquals(window, fittedGamePicture(900f, 400f, 1.5f, viewport = window))
        assertEquals(window, fittedGamePicture(900f, 400f, 1.5f, fill = true, viewport = window))
        // No matching skin orientation keeps the established portrait geometry.
        assertEquals(Rect(0f, 0f, 300f, 200f), fittedGamePicture(300f, 900f, 1.5f, topAligned = true, viewport = null))
    }
    @Test fun expandedPictureFillsBothOrientationViewports() {
        assertEquals(Rect(0f, 0f, 900f, 400f), fittedGamePicture(900f, 400f, 1.5f, fill = true))
        assertEquals(Rect(0f, 0f, 400f, 900f), fittedGamePicture(400f, 900f, 1.5f, topAligned = true, fill = true))
    }
    @Test fun unavailableExpansionRetainsTheOriginalShapeAndAlignment() {
        assertEquals(Rect(150f, 0f, 750f, 400f), fittedGamePicture(900f, 400f, 1.5f))
        assertEquals(Rect(0f, 0f, 300f, 200f), fittedGamePicture(300f, 900f, 1.5f, topAligned = true))
    }
}

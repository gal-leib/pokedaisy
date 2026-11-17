package com.pokedaisy.app.companion.ui

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpandedPictureBoundsTest {
    @Test fun expandedPictureFillsBothOrientationViewports() {
        assertEquals(Rect(0f, 0f, 900f, 400f), fittedGamePicture(900f, 400f, 1.5f, fill = true))
        assertEquals(Rect(0f, 0f, 400f, 900f), fittedGamePicture(400f, 900f, 1.5f, topAligned = true, fill = true))
    }
    @Test fun unavailableExpansionRetainsTheOriginalShapeAndAlignment() {
        assertEquals(Rect(150f, 0f, 750f, 400f), fittedGamePicture(900f, 400f, 1.5f))
        assertEquals(Rect(0f, 0f, 300f, 200f), fittedGamePicture(300f, 900f, 1.5f, topAligned = true))
    }
}

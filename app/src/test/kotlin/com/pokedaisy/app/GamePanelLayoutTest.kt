package com.pokedaisy.app

import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchSize
import com.pokedaisy.app.companion.ui.gamePanelLayout
import org.junit.Assert.*
import org.junit.Test

class GamePanelLayoutTest {
    @Test fun panelsFitTabletsAndSmallMultitaskingWindows() {
        for ((w, h) in listOf(744f to 1134f, 1134f to 744f, 1366f to 1024f, 400f to 180f, 0f to 0f)) {
            for (size in TouchSize.entries) for (visible in listOf(true, false)) {
                val panel = gamePanelLayout(w, h, 1f, TouchControlsStyle(size = size), visible)
                val stage = panel.touchStage(1f)
                assertTrue(panel.columnWidth in 0f..w)
                assertTrue(panel.columnHeight in 0f..h)
                assertTrue(stage.area.top >= stage.game.bottom)
                assertTrue(stage.area.bottom <= h)
                if (!visible) assertEquals(0f, panel.controlsHeight, 0f)
                val companion = stage.companion!!
                assertTrue(companion.left >= 0f && companion.top >= 0f && companion.right <= w && companion.bottom <= h)
                assertTrue(if (panel.wide) companion.left >= stage.area.right else companion.top >= stage.area.bottom)
            }
        }
    }
}

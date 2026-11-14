package com.pokedaisy.app

import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchSize
import com.pokedaisy.app.companion.TouchPosition
import com.pokedaisy.app.companion.ui.gamePanelLayout
import org.junit.Assert.*
import org.junit.Test

class GamePanelLayoutTest {
    @Test fun panelsFitTabletsAndSmallMultitaskingWindows() {
        for ((w, h) in listOf(744f to 1134f, 1134f to 744f, 1366f to 1024f, 400f to 180f, 180f to 400f, 400f to 400f, 0f to 0f)) {
            for (size in TouchSize.entries) for (visible in listOf(true, false)) for (gb in listOf(true, false)) {
                val stage = gamePanelLayout(w, h, 1f, TouchControlsStyle(size = size), visible, gameBoy = gb)
                val companion = stage.companion!!
                for (r in listOf(stage.game, stage.area, companion)) {
                    assertTrue("$r in $w x $h", r.left >= 0f && r.top >= 0f && r.right <= w && r.bottom <= h)
                    assertTrue(r.width >= 0f && r.height >= 0f)
                }
                if (!visible) assertEquals(0f, stage.area.height, 0f)
                if (stage.game.height > 0) assertEquals(if (gb) 160f / 144 else 1.5f, stage.game.width / stage.game.height, 0.001f)
                assertFalse(stage.game.overlaps(companion))
                assertFalse(stage.area.overlaps(companion))
                if (stage.portrait) assertFalse(stage.area.overlaps(stage.game))
            }
        }
    }

    @Test fun uprightCompanionIsUnderGameAndControlsAreAtBottom() {
        val stage = gamePanelLayout(744f, 1100f, 1f, TouchControlsStyle())
        val companion = stage.companion!!
        assertEquals(stage.game.bottom, companion.top, 0.01f)
        assertEquals(companion.bottom, stage.area.top, 0.01f)
        assertEquals(1100f, stage.area.bottom, 0f)
        assertTrue(companion.height > 0 && stage.area.height > 0)
        val hidden = gamePanelLayout(744f, 1100f, 1f, TouchControlsStyle(), controlsVisible = false)
        assertTrue(hidden.companion!!.height >= companion.height)
        assertEquals(1100f, hidden.companion!!.bottom, 0f)
    }

    @Test fun alignmentDoesNotRearrangeTheScreen() {
        val stages = TouchPosition.entries.map { gamePanelLayout(744f, 1100f, 1f, TouchControlsStyle(position = it)) }
        assertEquals(1, stages.distinct().size)
    }

    @Test fun sidewaysControlsUseGameAreaWithoutAnExtraStrip() {
        val stage = gamePanelLayout(1100f, 744f, 1f, TouchControlsStyle())
        assertEquals(0f, stage.area.top, 0f)
        assertEquals(744f, stage.area.bottom, 0f)
        assertEquals(stage.area.right, stage.companion!!.left, 0f)
        assertEquals(stage.area.center, stage.game.center)
        assertEquals(stage.game, gamePanelLayout(1100f, 744f, 1f, TouchControlsStyle(), controlsVisible = false).game)
    }
}

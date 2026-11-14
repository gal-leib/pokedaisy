package com.pokedaisy.app

import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchPosition
import com.pokedaisy.app.companion.ui.TouchGeometry
import com.pokedaisy.app.companion.ui.gamePanelLayout
import org.junit.Assert.*
import org.junit.Test

class GamePanelLayoutTest {
    @Test fun panelsFitTabletsAndSmallMultitaskingWindows() {
        for ((w, h) in listOf(744f to 1134f, 1134f to 744f, 1366f to 1024f, 400f to 180f, 180f to 400f, 400f to 400f, 0f to 0f)) {
            for (visible in listOf(true, false)) for (gb in listOf(true, false)) for (under in listOf(true, false)) {
                val stage = gamePanelLayout(w, h, 1f, visible, gameBoy = gb, underGame = under)
                val companion = stage.companion!!
                for (r in listOf(stage.game, stage.area, companion)) {
                    assertTrue("$r in $w x $h", r.left >= 0f && r.top >= 0f && r.right <= w && r.bottom <= h)
                    assertTrue(r.width >= 0f && r.height >= 0f)
                }
                if (!visible) assertEquals(0f, stage.area.height, 0f)
                if (stage.game.height > 0) assertEquals(if (gb) 160f / 144 else 1.5f, stage.game.width / stage.game.height, 0.001f)
                assertFalse(stage.game.overlaps(companion))
                assertFalse(stage.area.overlaps(companion))
            }
        }
    }

    /** An iPad mini upright has no gap under the companion: the controls get the game, as on an Android tablet,
     * so POSITION and a custom layout can put them anywhere over it - not only along the bottom. */
    @Test fun uprightTabletControlsLieOverTheGame() {
        for (under in listOf(true, false)) {
            val stage = gamePanelLayout(744f, 1100f, 1f, underGame = under)
            val companion = stage.companion!!
            assertEquals(744f / 1.5f, stage.game.height, 0.5f)
            assertEquals(0f, stage.area.top, 0f)
            assertEquals(stage.game.bottom, stage.area.bottom, 0.5f)
            if (under) assertEquals(stage.game.bottom, companion.top, 0.5f) else assertEquals(1100f, companion.bottom, 0.5f)
            val top = TouchGeometry.place(TouchControlsStyle(position = TouchPosition.TOP), stage.area.width, stage.area.height, 1f, true)
            val bottom = TouchGeometry.place(TouchControlsStyle(position = TouchPosition.BOTTOM), stage.area.width, stage.area.height, 1f, true)
            assertTrue(top.items.minOf { it.top } < bottom.items.minOf { it.top })
        }
    }

    @Test fun uprightPhoneControlsTakeTheGapUnderTheCompanion() {
        val stage = gamePanelLayout(400f, 1000f, 1f)
        assertEquals(stage.companion!!.bottom, stage.area.top, 0.5f)
        assertEquals(1000f, stage.area.bottom, 0f)
        assertFalse(stage.area.overlaps(stage.game))
    }

    @Test fun sidewaysControlsUseGameAreaWithoutAnExtraStrip() {
        val stage = gamePanelLayout(1100f, 744f, 1f)
        assertEquals(0f, stage.area.top, 0f)
        assertEquals(744f, stage.area.bottom, 0f)
        assertEquals(stage.area.right, stage.companion!!.left, 0f)
        assertEquals(stage.area.center, stage.game.center)
        assertEquals(stage.game, gamePanelLayout(1100f, 744f, 1f, controlsVisible = false).game)
    }
}

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
                val stage = gamePanelLayout(w, h, 1f, visible, gameBoy = gb, underGame = under, style = TouchControlsStyle())
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

    /** UNDER GAME (the default), as on Android: the controls get their own strip along the bottom, their natural
     * height, and the companion gives up the room - on an iPad mini and an iPhone alike, never over the game. */
    @Test fun uprightUnderGameKeepsAStripAlongTheBottom() {
        val style = TouchControlsStyle()
        for ((w, h) in listOf(744f to 1100f, 402f to 778f, 400f to 1000f)) {
            val stage = gamePanelLayout(w, h, 1f, underGame = true, style = style)
            val companion = stage.companion!!
            assertEquals("$w x $h", h, stage.area.bottom, 0f)
            assertEquals("$w x $h", companion.bottom, stage.area.top, 0.5f)
            assertTrue(stage.area.height >= TouchGeometry.naturalHeight(style, w, 1f) - 1)
            assertFalse(stage.area.overlaps(stage.game))
            assertEquals(stage.game.bottom, companion.top, 0.5f)
        }
    }

    /** AT BOTTOM on a screen with no gap under the game: the controls lie over the game, as on an Android tablet. */
    @Test fun uprightAtBottomWithNoGapLiesOverTheGame() {
        val stage = gamePanelLayout(744f, 1100f, 1f, underGame = false, style = TouchControlsStyle())
        assertEquals(0f, stage.area.top, 0f)
        assertEquals(stage.game.bottom, stage.area.bottom, 0.5f)
        assertEquals(1100f, stage.companion!!.bottom, 0.5f)
        val top = TouchGeometry.place(TouchControlsStyle(position = TouchPosition.TOP), stage.area.width, stage.area.height, 1f, true)
        val bottom = TouchGeometry.place(TouchControlsStyle(position = TouchPosition.BOTTOM), stage.area.width, stage.area.height, 1f, true)
        assertTrue(top.items.minOf { it.top } < bottom.items.minOf { it.top })
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

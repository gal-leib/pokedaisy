package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import kotlin.test.*

class BattleOverlayStateTest {
    @Test fun readinessSurvivesQuietReadsButNeverTheEndOfBattle() {
        val state = BattleOverlayState()
        state.observe(true, BATTLE_INPUT_NONE); assertFalse(state.read)
        state.observe(true, BATTLE_INPUT_ACTION_SELECT); assertTrue(state.read)
        state.observe(true, BATTLE_INPUT_NONE); assertTrue(state.read)
        state.observe(false, BATTLE_INPUT_ACTION_SELECT); assertFalse(state.read)
        state.observe(true, BATTLE_INPUT_NONE); assertFalse(state.read)
        state.observe(true, BATTLE_INPUT_MOVE_SELECT); assertTrue(state.read)
    }

    @Test fun onlySupportedSingleBattlesOnLandscapeSingleScreensAreEligible() {
        val battle = SnapshotView(connected = true, game = GameKind.EMERALD, inBattle = true,
            location = LocationView("Test", null, 0, 0, 0, 0))
        assertTrue(battleButtonsWanted(true, true, false, battle))
        assertFalse(battleButtonsWanted(false, true, false, battle))
        assertFalse(battleButtonsWanted(true, false, false, battle))
        assertFalse(battleButtonsWanted(true, true, true, battle))
        for (snapshot in listOf(battle.copy(inBattle = false), battle.copy(connected = false),
            battle.copy(unsupported = true), battle.copy(isDoubleBattle = true), battle.copy(game = null))) {
            assertFalse(battleButtonsWanted(true, true, false, snapshot))
        }
    }

    @Test fun actionLaneMovesOffShouldersAndFaceButtonsAndDeclinesAnObstructedScreen() {
        val box = androidx.compose.ui.geometry.Rect(0f, 0f, 1000f, 500f)
        val controls = listOf(androidx.compose.ui.geometry.Rect(880f, 10f, 970f, 60f),
            androidx.compose.ui.geometry.Rect(850f, 200f, 990f, 330f))
        assertEquals(androidx.compose.ui.geometry.Rect(750f, 0f, 850f, 500f), battleButtonsLane(box, 100f, controls))
        assertEquals(androidx.compose.ui.geometry.Rect(900f, 0f, 1000f, 500f), battleButtonsLane(box, 100f, emptyList()))
        assertNull(battleButtonsLane(box, 100f, listOf(box)))
        assertEquals(battleButtonsLane(box, 100f, emptyList()), battleButtonsLane(box, 100f,
            listOf(androidx.compose.ui.geometry.Rect(0f, 600f, 1000f, 700f))))
    }

    @Test fun settingsSurviveHostRecreationWithoutChangingHudChoices() {
        val store = PrefsCompanionSettingsTest.Store()
        val settings = PrefsCompanionSettingsTest.Settings(Prefs(store))
        settings.setFieldHud(false); settings.setBattleButtons(false)
        val reopened = PrefsCompanionSettingsTest.Settings(Prefs(store))
        assertFalse(reopened.battleButtons); assertFalse(reopened.fieldHud)
        reopened.setBattleButtons(true)
        assertTrue(settings.battleButtons); assertFalse(settings.fieldHud)
    }
}

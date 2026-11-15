package com.pokedaisy.app.ios

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.PlaybackPolicy
import com.pokedaisy.app.companion.StateSlotController

/** Platform actions only: chords, game-key capture and speed priorities stay in the shared models. */
internal class IosGameHotkeys(
    private val settings: IosCompanionSettings,
    private val slots: StateSlotController,
    private val panels: IosGameScreen,
) {
    fun handle(event: Hotkeys.Event) {
        when (event.action) {
            Hotkeys.Action.FF_HOLD -> settings.holdHardwareFastForward(event.pressed)
            Hotkeys.Action.SLOWMO_HOLD -> settings.holdSlowmo(event.pressed)
            Hotkeys.Action.REWIND_HOLD -> settings.holdHardwareRewind(event.pressed)
            else -> if (event.pressed) when (event.action) {
                Hotkeys.Action.SAVE_STATE -> slots.requestSave(slots.currentIndex)
                Hotkeys.Action.LOAD_STATE -> slots.requestLoad(slots.currentIndex)
                Hotkeys.Action.UNDO_SAVE -> slots.requestUndoSave()
                Hotkeys.Action.UNDO_LOAD -> slots.requestUndoLoad()
                Hotkeys.Action.SLOT_NEXT -> slots.select((slots.currentIndex + 1) % 10)
                Hotkeys.Action.SLOT_PREV -> slots.select((slots.currentIndex + 9) % 10)
                Hotkeys.Action.FF_TOGGLE -> settings.setFfToggled(!settings.ffToggled)
                Hotkeys.Action.SPEED_CYCLE -> settings.setGameSpeed(PlaybackPolicy.cycleSpeed(settings.gameSpeed))
                Hotkeys.Action.EXIT_GAME -> settings.closeGame()
                Hotkeys.Action.RESTART_GAME -> settings.restartGame()
                Hotkeys.Action.COMPANION -> panels.toggleCompanion()
                Hotkeys.Action.MUTE -> settings.setGameMuted(!settings.gameMuted)
                else -> Unit // No binding for an unavailable action enters this host.
            }
        }
    }

    companion object {
        val ACTIONS = listOf(
            Hotkeys.Action.SAVE_STATE, Hotkeys.Action.LOAD_STATE, Hotkeys.Action.UNDO_SAVE, Hotkeys.Action.UNDO_LOAD,
            Hotkeys.Action.SLOT_NEXT, Hotkeys.Action.SLOT_PREV,
            Hotkeys.Action.FF_HOLD, Hotkeys.Action.FF_TOGGLE, Hotkeys.Action.SPEED_CYCLE, Hotkeys.Action.SLOWMO_HOLD,
            Hotkeys.Action.REWIND_HOLD,
            Hotkeys.Action.EXIT_GAME, Hotkeys.Action.RESTART_GAME, Hotkeys.Action.COMPANION, Hotkeys.Action.MUTE,
        )
    }
}

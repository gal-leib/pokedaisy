package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkApiCore
import platform.GameController.*

/**
 * The game's keys ([PkApiCore.KEY_A] ...), read once a frame on the game's thread: every connected controller, a
 * hardware keyboard, and whatever the app's own touch controls hold ([touchKeys], set by whoever draws them).
 *
 * A controller: its A / B, Menu = START, Options = SELECT, shoulders, D-pad and left stick. A keyboard, as mGBA's
 * defaults: arrows, X = A, Z = B, A = L, S = R, Return = START, Backspace or right Shift = SELECT.
 */
class GameInput {
    /** The keys the on-screen controls hold (set on the main thread, the game's). */
    var touchKeys: Int = 0

    fun keys(): Int {
        var keys = touchKeys
        for (c in GCController.controllers()) {
            val pad = (c as? GCController)?.extendedGamepad ?: continue
            keys = keys or padKeys(pad)
        }
        GCKeyboard.coalescedKeyboard()?.keyboardInput?.let { keys = keys or keyboardKeys(it) }
        return keys
    }

    private fun padKeys(pad: GCExtendedGamepad): Int {
        var k = 0
        if (pad.buttonA.pressed) k = k or PkApiCore.KEY_A
        if (pad.buttonB.pressed) k = k or PkApiCore.KEY_B
        if (pad.buttonMenu.pressed) k = k or PkApiCore.KEY_START
        if (pad.buttonOptions?.pressed == true) k = k or PkApiCore.KEY_SELECT
        if (pad.leftShoulder.pressed || pad.leftTrigger.pressed) k = k or PkApiCore.KEY_L
        if (pad.rightShoulder.pressed || pad.rightTrigger.pressed) k = k or PkApiCore.KEY_R
        val x = pad.leftThumbstick.xAxis.value
        val y = pad.leftThumbstick.yAxis.value
        if (pad.dpad.up.pressed || y > STICK) k = k or PkApiCore.KEY_UP
        if (pad.dpad.down.pressed || y < -STICK) k = k or PkApiCore.KEY_DOWN
        if (pad.dpad.left.pressed || x < -STICK) k = k or PkApiCore.KEY_LEFT
        if (pad.dpad.right.pressed || x > STICK) k = k or PkApiCore.KEY_RIGHT
        return k
    }

    private fun keyboardKeys(input: GCKeyboardInput): Int {
        var k = 0
        for ((code, key) in KEYBOARD) {
            if (input.buttonForKeyCode(code)?.pressed == true) k = k or key
        }
        return k
    }

    private companion object {
        /** How far the stick goes before it's a direction. */
        const val STICK = 0.5f

        val KEYBOARD = listOf(
            GCKeyCodeUpArrow to PkApiCore.KEY_UP,
            GCKeyCodeDownArrow to PkApiCore.KEY_DOWN,
            GCKeyCodeLeftArrow to PkApiCore.KEY_LEFT,
            GCKeyCodeRightArrow to PkApiCore.KEY_RIGHT,
            GCKeyCodeKeyX to PkApiCore.KEY_A,
            GCKeyCodeKeyZ to PkApiCore.KEY_B,
            GCKeyCodeKeyA to PkApiCore.KEY_L,
            GCKeyCodeKeyS to PkApiCore.KEY_R,
            GCKeyCodeReturnOrEnter to PkApiCore.KEY_START,
            GCKeyCodeDeleteOrBackspace to PkApiCore.KEY_SELECT,
            GCKeyCodeRightShift to PkApiCore.KEY_SELECT,
        )
    }
}

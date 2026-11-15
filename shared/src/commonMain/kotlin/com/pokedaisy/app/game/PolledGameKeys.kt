package com.pokedaisy.app.game

import com.pokedaisy.app.GbaInput
import com.pokedaisy.app.companion.KeyCapture

/** Converts polled canonical keys into events for the existing mapper and key capture. */
class PolledGameKeys(val capture: KeyCapture = KeyCapture()) {
    val input = GbaInput()
    private var previous = emptySet<Int>()
    private val suppressed = mutableSetOf<Int>()
    private var epoch = 0

    /** Ownership/rebinding can change entirely between polls. Held keys need a release before playing again. */
    fun quarantine(held: Set<Int>) {
        epoch++
        previous = held.toSet()
        suppressed += held
        input.releaseAll()
    }

    fun poll(held: Set<Int>, blocked: Boolean) {
        if (held == previous) return
        val before = previous
        previous = held.toSet()
        suppressed.retainAll(held)
        if (blocked || capture.active) suppressed += held
        val started = epoch
        // Releases first: a disconnected pad releases its keys; an alias held on another device stays pressed.
        for (key in before) {
            if (key in held) continue
            if (!capture.onKey(key, false)) input.onKey(key, false)
            if (epoch != started) return
        }
        for (key in held) {
            if (key in before) continue
            if (!capture.onKey(key, true) && !blocked && key !in suppressed) input.onKey(key, true)
            if (epoch != started) return
        }
    }
}

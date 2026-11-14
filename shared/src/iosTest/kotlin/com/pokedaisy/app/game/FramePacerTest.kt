package com.pokedaisy.app.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramePacerTest {
    private fun run(hz: Double, seconds: Double, pacer: FramePacer = FramePacer()): List<Int> =
        (0 until (hz * seconds).toInt()).map { pacer.framesAt(it / hz) }

    @Test
    fun keepsTheGamesSpeedAtSixtyHertz() {
        val frames = run(60.0, 60.0)
        assertTrue(frames.all { it in 0..1 }, "never two frames a refresh")
        val total = frames.sum()
        assertTrue(kotlin.math.abs(total - FramePacer.FPS * 60) <= 2, "$total frames in a minute")
    }

    @Test
    fun runsEveryOtherRefreshAtOneTwentyHertz() {
        val frames = run(120.0, 10.0)
        assertTrue(frames.all { it in 0..1 })
        assertTrue(kotlin.math.abs(frames.sum() - FramePacer.FPS * 10) <= 2)
    }

    @Test
    fun aLongGapIsNotOwed() {
        val pacer = FramePacer()
        pacer.framesAt(0.0)
        assertEquals(FramePacer.MAX_CATCH_UP, pacer.framesAt(5.0))
        assertEquals(1, pacer.framesAt(5.0 + 1 / 60.0))
        pacer.reset()
        assertEquals(1, pacer.framesAt(100.0))
        assertEquals(1, pacer.framesAt(100.0 + 1 / 60.0))
    }
}

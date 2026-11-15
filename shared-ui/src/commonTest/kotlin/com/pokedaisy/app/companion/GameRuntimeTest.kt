package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath

class GameRuntimeTest {
    private fun withPlayback(test: (GameRuntime, GameSession, GameBattleInput) -> Unit) {
        val telemetry = TelemetryStore()
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
            override fun read(addr: Long, len: Int) = ByteArray(len)
            override fun write(addr: Long, bytes: ByteArray) = false
        }
        val session = GameSession(calls, { true }) { _, _ -> }
        val battle = GameBattleInput(telemetry, null, 0)
        val playback = GameRuntime(session, telemetry, Prefs(PrefsCompanionSettingsTest.Store()), battle)
        try { test(playback, session, battle) } finally { playback.close() }
    }

    @Test fun liveConfigurationDrivesScenesAndSmartMenusWithoutLosingTheRequestedSpeed() = withPlayback { p, session, _ ->
        p.baseSpeed = 1.5f; p.bikeSpeed = 3f
        p.fieldChanged(GameField.AVATAR or GameField.BIKE)
        assertEquals(3f, p.speed()); assertTrue(p.fieldMovesReadable)
        p.fastForward = true; p.ffMaxSpeed = 4f
        session.observeScreens(gba = false, dsMenu = true)
        assertEquals(4f, p.requestedSpeed(PlaybackPolicy.Scene.MENU))
        assertEquals(1f, p.speed())
        p.ffMode = FfMode.NORMAL; assertEquals(4f, p.speed())
        p.fastForward = false; p.slowMotion = true; assertEquals(.5f, p.speed())
        p.fastForward = true; p.ffMaxSpeed = 0f
        assertTrue(p.speed().isInfinite())
        p.resetFieldMoves(); assertFalse(p.fieldMovesReadable)
    }

    @Test fun startingAndClosingResetQueuedFieldWorkAndUnsupportedFieldReadiness() = withPlayback { p, session, _ ->
        var ran = false
        session.post { ran = true }
        p.fieldChanged(GameField.AVATAR)
        p.start("missing-diagnostic.gb".toPath(), null, 0)
        p.beforeFrame()
        assertFalse(ran); assertFalse(p.fieldReadable); assertFalse(p.fieldMovesReadable)
        session.post { ran = true }; p.close(); session.servicePosted()
        assertFalse(ran); assertNull(FfMenuWatch.onLearned)
    }

    @Test fun lateCloseCannotRemoveTheNextGamesLearnedMenuCallback() = withPlayback { first, _, _ ->
        first.start("first-diagnostic.gb".toPath(), null, 1)
        withPlayback { second, _, _ ->
            second.start("second-diagnostic.gb".toPath(), null, 2)
            val callback = FfMenuWatch.onLearned
            first.close()
            assertSame(callback, FfMenuWatch.onLearned)
            second.close(); assertNull(FfMenuWatch.onLearned)
        }
    }

    @Test fun aClosedOwnerCannotRestartWatchersOrSampleAReplacementGame() = withPlayback { first, session, battle ->
        var checks = 0
        first.startSampling(gameBoy = true, game = Dispatchers.Unconfined,
            current = { checks++; false })
        val delayedBattleTrigger = battle.changed
        delayedBattleTrigger()
        assertEquals(1, checks)
        first.close(); first.close()
        withPlayback { next, _, _ ->
            next.start("replacement-diagnostic.gb".toPath(), null, 2)
            val callback = FfMenuWatch.onLearned
            first.start("old-diagnostic.gb".toPath(), null, 1)
            first.startSampling(gameBoy = true, game = Dispatchers.Unconfined, current = { error("closed owner") })
            delayedBattleTrigger(); first.sample(); battle.changed()
            assertEquals(1, checks)
            assertSame(callback, FfMenuWatch.onLearned)
            var ran = false
            session.post { ran = true }; session.servicePosted()
            assertFalse(ran)
        }
    }
}

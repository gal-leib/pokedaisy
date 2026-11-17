package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.GameSession
import kotlin.test.*

class GameBoyFieldActionsTest {
    private class Harness {
        val memory = mutableMapOf<Long, Byte>()
        val writes = mutableListOf<Long>()
        val deliveries = mutableListOf<() -> Unit>()
        var called = 0
        var handoffs = 0
        var battling = false
        var onField = true
        var field: GbField? = GB_FIELD_YELLOW
        operator fun set(address: Long, value: Int) { memory[address] = value.toByte() }
        operator fun get(address: Long) = memory[address]?.toInt()?.and(255) ?: 0
        val calls = object : GameCalls {
            override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = error("GB used a GBA call")
            override fun read(addr: Long, len: Int) = ByteArray(len) { memory[addr + it] ?: 0 }
            override fun write(addr: Long, bytes: ByteArray): Boolean {
                writes += addr
                bytes.forEachIndexed { index, byte -> memory[addr + index] = byte }
                return true
            }
            override fun gbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int): Boolean { called++; return true }
        }
        val session = GameSession(calls, { error("GB queried GBA call safety") }) { _, e -> throw e }
        val actions = GameBoyFieldActions(session, { field }, { battling }, { deliveries += it }, { handoffs++ })
        fun frames(n: Int = 1) = repeat(n) { session.serviceField(false, onField) }
        fun deliver() { val batch = deliveries.toList(); deliveries.clear(); batch.forEach { it() } }
        fun flyer() {
            this[0xD355] = 4 // badge
            this[0xD162] = 1 // party count
            this[0xD172] = 19 // field move
            this[0xD70A] = 3 // visited towns
            this[0xC1F2] = 0x12 // companion sprite, restored if the warp is withdrawn
        }
    }

    @Test fun pcRunsOnTheCoreQueueAndHandsOverOnlyAfterAcceptanceAndHostDelivery() {
        val h = Harness(); var outcome: ItemUseOutcome? = null
        assertTrue(h.actions.available)
        h.actions.openPc { outcome = it }
        assertEquals(0, h.called)
        h.frames(); assertEquals(1, h.called); assertNull(outcome)
        h.frames(GbField.TAKE_FRAMES); assertTrue(h.deliveries.isEmpty())
        h.frames(); assertNull(outcome); assertEquals(0, h.handoffs)
        h.deliver(); assertEquals(ItemUseResult.USED, outcome?.result); assertEquals(1, h.handoffs)
    }

    @Test fun flyTargetsUseTheGamesCurrentBadgePartyAndVisitedTowns() {
        val h = Harness(); val results = mutableListOf<FlyTargets>()
        h.actions.targets(results::add); h.frames(); h.deliver()
        assertEquals(FlyResult.NO_BADGE, results.last().result)
        h.flyer(); h.actions.targets(results::add); h.frames()
        assertEquals(1, results.size)
        h.deliver(); assertEquals(FlyTargets(FlyResult.OK, listOf(0, 1), 0), results.last())
    }

    @Test fun anUnacceptedFlyIsWithdrawnAndRestoresTheCompanionSprite() {
        val h = Harness(); h.flyer(); var result: FlyResult? = null
        h.actions.fly(1, FlyTargets(FlyResult.OK, listOf(0, 1), 0)) { result = it }
        h.frames(); assertEquals(8, h[0xD731]); assertEquals(255, h[0xC1F2])
        h.frames(25); h.deliver()
        assertEquals(FlyResult.NOT_NOW, result); assertEquals(0, h.handoffs)
        assertEquals(0, h[0xD731]); assertEquals(0, h[0xD732]); assertEquals(0x12, h[0xC1F2])
    }

    @Test fun anAcceptedFlyClosesTheCompanionOnceAndDoesNotUndoTheGamesWarp() {
        val h = Harness(); h.flyer(); var result: FlyResult? = null
        h.actions.fly(1, FlyTargets(FlyResult.OK, listOf(0, 1), 0)) { result = it }
        h.frames(); h[0xD731] = h[0xD731] or 4
        h.frames(25); h.deliver()
        assertEquals(FlyResult.OK, result); assertEquals(1, h.handoffs); assertEquals(12, h[0xD731])
    }

    @Test fun cancellationWithdrawsBeforeStateReplacementAndRetiresAllOldCallbacks() {
        val h = Harness(); h.flyer(); var delivered = false
        h.actions.fly(1, FlyTargets(FlyResult.OK, listOf(0, 1), 0)) { delivered = true }
        h.frames(); h.actions.cancel()
        assertEquals(0, h[0xD731]); assertEquals(0x12, h[0xC1F2])
        h[0xD731] = 0x55; h.writes.clear()
        h.frames(100); h.deliver()
        assertFalse(delivered); assertEquals(0x55, h[0xD731]); assertTrue(h.writes.isEmpty())
    }

    @Test fun crystalWithdrawsAQueuedPcAndReportsNotNowIfTheGameNeverTakesIt() {
        val h = Harness(); h.field = GbField.Crystal; var result: ItemUseOutcome? = null
        h.actions.openPc { result = it }; h.frames()
        assertEquals(1, h[0x1D45C])
        h.frames(25); h.deliver()
        assertEquals(0, h[0x1D45C]); assertEquals(ItemUseResult.NOT_NOW, result?.result)
        h.actions.openPc { result = it }; h.frames(); h[0x1D45C] = 0 // game's script queue consumed it
        h.frames(25); h.deliver()
        assertEquals(ItemUseResult.USED, result?.result); assertEquals(1, h.handoffs)
    }

    @Test fun battleRequestsRetainTheirRefusalEvenIfTheBattleEndsBeforeTheCoreRuns() {
        val h = Harness(); h.battling = true
        var item: ItemUseOutcome? = null; var targets: FlyTargets? = null; var fly: FlyResult? = null
        h.actions.openPc { item = it }; h.actions.targets { targets = it }
        h.actions.fly(0, FlyTargets(FlyResult.OK)) { fly = it }
        h.battling = false; h.frames(40); h.deliver()
        assertEquals(ItemUseResult.IN_BATTLE, item?.result); assertEquals(FlyResult.IN_BATTLE, targets?.result)
        assertEquals(FlyResult.IN_BATTLE, fly); assertEquals(0, h.called); assertTrue(h.writes.isEmpty())
    }

    @Test fun busyUnsupportedAndOverlappingActionsDoNotReplacePendingWork() {
        val h = Harness(); val results = mutableListOf<ItemUseOutcome>()
        h.onField = false; h.actions.openPc(results::add); h.frames(); h.deliver()
        assertEquals(ItemUseResult.NOT_NOW, results.last().result)
        h.onField = true; h.field = null; assertFalse(h.actions.available)
        h.actions.openPc(results::add); h.frames(); h.deliver()
        assertEquals(ItemUseResult.FAILED, results.last().result)
        h.field = GB_FIELD_YELLOW
        h.actions.openPc(results::add); h.actions.openPc(results::add); h.frames(); h.deliver()
        assertEquals(ItemUseResult.NOT_NOW, results.last().result); assertEquals(1, h.called)
        h.frames(25); h.deliver(); assertEquals(ItemUseResult.USED, results.last().result)
    }

    @Test fun closeAndCancellationDiscardResultsAlreadyWaitingForTheUi() {
        for (close in listOf(false, true)) {
            val h = Harness(); var delivered = false
            h.actions.targets { delivered = true }; h.frames(); assertEquals(1, h.deliveries.size)
            if (close) h.actions.close() else h.actions.cancel()
            h.deliver(); assertFalse(delivered)
        }
        val queued = Harness(); queued.actions.openPc { fail("retired result") }; queued.actions.close()
        queued.frames(100); queued.deliver(); assertFalse(queued.actions.available); assertEquals(0, queued.called)
    }

    @Test fun unsupportedRomSignaturesFailClosedAndAreCachedForTheLoadedGame() {
        val previous = romLanguage; romLanguage = 'E'
        try {
            val cache = GameBoyFieldSupport(); var reads = 0
            val rom: (Long, Int) -> ByteArray? = { _, length -> reads++; ByteArray(length) }
            assertNull(cache.get(GameKind.YELLOW, rom)); val first = reads; assertTrue(first > 0)
            assertNull(cache.get(GameKind.YELLOW, rom)); assertEquals(first, reads)
            assertNull(cache.get(GameKind.GOLD, rom)); assertEquals(first, reads)
            assertNull(cache.get(GameKind.RED, rom)); assertTrue(reads > first)
        } finally { romLanguage = previous }
    }

    @Test fun cachedCompanionDataCannotVerifyACartridgeBeforeTheCoreConnects() {
        val h = Harness()
        val previousGame = activeGame; val previousLanguage = romLanguage; val previousCode = romGameCode
        val telemetry = TelemetryStore().apply {
            showCached(SnapshotView(connected = true, game = GameKind.YELLOW, cachedTabs = setOf("PARTY")))
        }
        assertTrue(telemetry.snapshot.value.connected) // The cached copy still describes its old connected game.
        val runtime = GameRuntime(h.session, telemetry,
            com.pokedaisy.app.Prefs(PrefsCompanionSettingsTest.Store()), GameBattleInput(telemetry, null, 0),
            object : RomOptionPatcher {
                override fun unpatch() {}
                override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray) = true
            }, { true })
        try {
            var reads = 0
            val fields = runtime.bindGameBoyFields({ true }, { _, _ -> reads++; null }, { it() }, {})
            assertFalse(fields.available); assertEquals(0, reads)
        } finally { runtime.close(); activeGame = previousGame; romLanguage = previousLanguage; romGameCode = previousCode }
    }
}

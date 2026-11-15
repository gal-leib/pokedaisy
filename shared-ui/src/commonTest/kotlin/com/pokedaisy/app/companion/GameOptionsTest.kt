package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okio.FileSystem
import okio.SYSTEM
import okio.use

class GameOptionsTest {
    private val sites = listOf(ReusableTms.Site(0x08001000L, ByteArray(16)))
    private val text = InstantText.Patch(listOf(InstantText.Write(0x08002000L, byteArrayOf(1), byteArrayOf(2))))
    private class Core : RomOptionPatcher {
        val active = mutableSetOf<Long>()
        var resets = 0
        var duringPatch: () -> Unit = {}
        override fun unpatch() { resets++; active.clear() }
        override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray): Boolean {
            active += at; duringPatch(); return true
        }
    }

    @Test fun uiChangesWaitForTheFrameAndInputSettingsNeverRepatch() {
        val core = Core()
        val options = GameOptions(core, { true })
        try {
            options.setReusableTms(sites, true); options.setInstantText(text, true)
            assertTrue(core.active.isEmpty())
            assertEquals(GameOptionPatches.Result(true, true), options.tick())
            assertEquals(setOf(sites.single().branch, text.writes.single().addr), core.active)
            options.autoRun = true; options.runIndoors = true; options.wildEncounters = 0
            assertFalse(options.dirty); assertNull(options.tick()); assertEquals(1, core.resets)
            var indoor = false; var keep = -1
            val step = { run: Boolean, wild: Int -> indoor = run; keep = wild; GameField.AVATAR }
            assertEquals(GameField.AVATAR, options.fieldState(step))
            assertTrue(indoor); assertEquals(100, keep) // Unknown wild switch must retain the game's encounters.
            options.fieldState(step, indoorsSupported = false, wildKnown = true)
            assertFalse(indoor); assertEquals(0, keep)
            options.wildEncounters = 17; options.fieldState(step, wildKnown = true)
            assertEquals(100, keep)
        } finally { options.close() }
    }

    @Test fun disablingAndClosingRestoreTheCoreOnlyOnceAndRestartReapplies() {
        val core = Core(); var allowed = false
        val options = GameOptions(core, { allowed })
        options.setReusableTms(sites, true)
        assertNull(options.tick()); assertTrue(core.active.isEmpty())
        allowed = true; options.tick(); assertTrue(core.active.isNotEmpty())
        allowed = false; options.tick(); assertTrue(core.active.isEmpty())
        val resets = core.resets
        assertNull(options.tick()); assertEquals(resets, core.resets)
        allowed = true; options.tick()
        core.active.clear(); options.coreStarted(); options.tick()
        assertEquals(setOf(sites.single().branch), core.active)
        options.close(); val closedResets = core.resets
        options.setInstantText(text, true); options.close()
        assertNull(options.tick()); assertEquals(closedResets, core.resets)
        assertEquals(0, options.fieldState({ _, _ -> error("closed core") }))
    }

    @Test fun anUpdateDuringPatchingRemainsPendingForTheNextFrame() {
        val core = Core(); val options = GameOptions(core, { true })
        try {
            options.setReusableTms(sites, true)
            core.duringPatch = { options.setInstantText(text, true) }
            assertEquals(GameOptionPatches.Result(true, false), options.tick())
            assertTrue(options.dirty)
            core.duringPatch = {}
            assertEquals(GameOptionPatches.Result(true, true), options.tick())
            assertFalse(options.dirty)
        } finally { options.close() }
    }

    @Test fun cancelledDiscoveryCannotPublishIntoAClosedGame() = runBlocking {
        val queue = Channel<Runnable>(Channel.UNLIMITED)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { check(queue.trySend(block).isSuccess) }
        }
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-options-${Random.nextLong().toULong()}.gba"
        FileSystem.SYSTEM.sink(file).use { it.write(okio.Buffer().write(ByteArray(65536)), 65536) }
        val core = Core(); val options = GameOptions(core, { true }, dispatcher)
        try {
            var published = false
            options.start(file, { _, _ -> published = true; true }, { published = true })
            val publication = withTimeout(10_000) { queue.receive() }
            options.close()
            publication.run()
            assertFalse(published); assertTrue(core.active.isEmpty())
            assertEquals(GameOptions.Discovery(), options.discovery)
        } finally { options.close(); FileSystem.SYSTEM.delete(file) }
    }

    @Test fun retiringForAnEngineOwnedCoreNeverUnpatchesOnTheClosingThread() {
        val core = Core()
        val options = GameOptions(core, { true })
        options.setReusableTms(sites, true); options.tick()
        val resets = core.resets
        options.close(restorePatches = false)
        assertEquals(resets, core.resets)
        assertEquals(setOf(sites.single().branch), core.active)
        assertNull(options.tick())
        assertFalse(options.dirty)
    }
}

package com.pokedaisy.app.companion

import com.pokedaisy.app.LinkSavePaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class LinkSetupFlowTest {
    @Test fun aDuplicateCartridgeHasItsOwnSlotAndCanBeRemoved() {
        val setup = LinkSetupFlow<String>()
        setup.seed("Emerald")
        repeat(3) {
            val scan = assertNotNull(setup.begin(setup.slots.size))
            assertTrue(setup.ready(scan, listOf("Emerald")))
            setup.choose(assertNotNull(setup.choice), "Emerald")
        }
        assertEquals(listOf("Emerald", "Emerald", "Emerald", "Emerald"), setup.slots)
        assertEquals("Emerald, save 4", setup.rowLabel(3, { it }, { it }))
        assertNull(setup.begin(4))
        assertTrue(setup.ready(assertNotNull(setup.begin(1)), listOf("Emerald")))
        setup.choose(assertNotNull(setup.choice), null)
        assertEquals(3, setup.slots.size)
    }

    @Test fun lateScansAndDismissedSelectionsCannotChangeTheCurrentSlots() {
        val setup = LinkSetupFlow<String>()
        setup.seed("Ruby")
        val old = assertNotNull(setup.begin(0))
        val current = assertNotNull(setup.begin(1))
        assertFalse(setup.ready(old, listOf("Sapphire")))
        assertTrue(setup.ready(current, listOf("Emerald")))
        val choice = assertNotNull(setup.choice)
        setup.dismiss(choice)
        setup.choose(choice, "Emerald")
        assertEquals(listOf("Ruby"), setup.slots)
        val pending = assertNotNull(setup.begin(1))
        setup.clearPicker()
        assertFalse(setup.ready(pending, listOf("Emerald")))
    }

    @Test fun duplicateDestinationsNeverOverwriteAnotherCartridgesOwnBattery() {
        val originals = listOf("/saves/Game.srm", "/saves/Game.srm", "/saves/Game (link 2).srm", "/saves/Game.srm").map { it.toPath() }
        val assigned = LinkSavePaths.forGames(originals).map { it.toString() }
        assertEquals(listOf("/saves/Game.srm", "/saves/Game (link 3).srm", "/saves/Game (link 2).srm", "/saves/Game (link 4).srm"), assigned)
        assertEquals(listOf("/Game.sav", "/Game (link 2).sav"), LinkSavePaths.forGames(List(2) { "/Game.sav".toPath() }).map { it.toString() })
    }
}

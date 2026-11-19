package com.pokedaisy.app

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

class LinkSavePathsTest {
    @Test fun duplicateDestinationsNeverOverwriteAnotherCartridgesOwnBattery() {
        val originals = listOf("/saves/Game.srm", "/saves/Game.srm", "/saves/Game (link 2).srm", "/saves/Game.srm").map { it.toPath() }
        val assigned = LinkSavePaths.forGames(originals).map { it.toString() }
        assertEquals(listOf("/saves/Game.srm", "/saves/Game (link 3).srm", "/saves/Game (link 2).srm", "/saves/Game (link 4).srm"), assigned)
        assertEquals(listOf("/Game.sav", "/Game (link 2).sav"), LinkSavePaths.forGames(List(2) { "/Game.sav".toPath() }).map { it.toString() })
    }
}

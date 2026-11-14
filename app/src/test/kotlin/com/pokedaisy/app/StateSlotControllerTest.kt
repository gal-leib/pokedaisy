package com.pokedaisy.app

import com.pokedaisy.app.companion.StateSlotController
import com.pokedaisy.app.companion.data.*
import java.nio.file.Files
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class StateSlotControllerTest {
    private val root = Files.createTempDirectory("pokedaisy-state-slots").toFile().toOkioPath()
    private val files = StateFiles(root)
    private var game = 1
    private var rejectWrite = false
    private var rejectBackup = false
    private var backups = 0
    private var errors = 0
    private val slots = StateSlotController(files, onCore = { it() },
        write = { p -> if (rejectWrite) false else { p.writeText(game.toString()); true } },
        read = { p -> p.readText().toIntOrNull()?.let { game = it; true } ?: false },
        beforeLoad = { if (rejectBackup) error("backup unavailable"); backups++ },
        failed = { errors++ })
    @After fun clean() = root.deleteRecursively()

    @Test fun saveLoadAndBothUndosRestoreTheExpectedMoment() {
        slots.requestSave(3)
        game = 2; slots.requestSave(3)
        slots.requestUndoSave()
        game = 9; slots.requestLoad(3)
        assertEquals(1, game); assertEquals(3, slots.currentIndex)
        slots.requestUndoLoad()
        assertEquals(9, game); assertEquals(2, backups); assertEquals(0, errors)
    }

    @Test fun loadIsRefusedWhenTheBackupOrUndoSnapshotFails() {
        slots.requestSave(0)
        game = 5; rejectBackup = true; slots.requestLoad(0)
        assertEquals(5, game)
        rejectBackup = false; rejectWrite = true; slots.requestLoad(0)
        assertEquals(5, game); assertEquals(2, errors)
    }

    @Test fun deletesAndInvalidSlotRequestsDoNotReachOtherFiles() {
        slots.requestSave(2); slots.requestDelete(2)
        assertFalse(files.exists(2))
        slots.requestSave(-1); slots.requestLoad(10); slots.requestDelete(100)
        assertEquals(2, slots.currentIndex)
        assertEquals(0, errors)
        assertEquals(10, slots.list().size)
    }
}

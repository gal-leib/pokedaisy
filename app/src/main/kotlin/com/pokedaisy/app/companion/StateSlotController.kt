package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.i18n.tr
import okio.Path

/** Shared slot actions. The host serializes [onCore] between frames, and backs up battery data in [beforeLoad]. */
class StateSlotController(
    val files: StateFiles,
    private val onCore: (() -> Boolean) -> Boolean,
    private val write: (Path) -> Boolean,
    private val read: (Path) -> Boolean,
    private val beforeLoad: () -> Unit,
    private val thumbnail: () -> ByteArray? = { null },
    private val snapshot: () -> ByteArray? = { null },
    private val loaded: (Path) -> Unit = {},
    private val failed: (String) -> Unit = {},
    initialIndex: Int = 0,
    private val selected: (Int) -> Unit = {},
) : StateSlots {
    override var currentIndex by mutableStateOf(initialIndex.coerceIn(0, 9))
        private set

    override fun list(): List<StateSlots.Slot> = (0..9).map { i ->
        val state = files.stateFile(i)
        val thumb = files.thumbFile(i)
        StateSlots.Slot(i, files.exists(i), state.lastModified,
            thumb.takeIf { it.isFile }?.toString(), thumb.lastModified)
    }

    override fun requestSave(index: Int) {
        if (!choose(index)) return
        perform(tr("Slot {0}: save failed", index)) {
            files.saveSlot(index, write, thumbnail(), snapshot())
        }
    }

    override fun requestLoad(index: Int) {
        if (!choose(index)) return
        perform(tr("Slot {0}: nothing to load", index)) {
            if (!files.exists(index)) return@perform false
            beforeLoad()
            // Refuse the load if its undo snapshot couldn't be made.
            if (!files.writeState(files.undoLoadFile, write, snapshot = snapshot())) return@perform false
            load(files.stateFile(index))
        }
    }

    override fun requestDelete(index: Int) {
        if (index !in 0..9) return
        perform(tr("Couldn't delete slot {0}", index)) { files.deleteSlot(index); true }
    }

    override fun requestUndoSave() = perform(tr("Nothing to undo")) { files.undoSave(currentIndex) }

    override fun requestUndoLoad() = perform(tr("Nothing to undo")) {
        if (!files.undoLoadFile.isFile) return@perform false
        beforeLoad()
        load(files.undoLoadFile)
    }

    private fun load(path: Path): Boolean {
        if (!read(path)) return false
        loaded(path)
        return true
    }

    private fun choose(index: Int): Boolean {
        if (index !in 0..9) return false
        currentIndex = index
        selected(index)
        return true
    }

    private fun perform(message: String, action: () -> Boolean) {
        if (!runCatching { onCore(action) }.getOrDefault(false)) failed(message)
    }
}

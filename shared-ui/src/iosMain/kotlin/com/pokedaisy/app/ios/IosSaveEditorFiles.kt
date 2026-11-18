@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.SaveEditFiles
import com.pokedaisy.app.SaveTradeJournal
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.delete
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.mkdirs
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.writeAtomically
import com.pokedaisy.app.companion.data.writeBytes
import com.pokedaisy.app.companion.data.writeTextAtomically
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.game.RomPicker
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open

/** Save edits publish while the game is closed. Commit fresh-boot intent before replacing the old battery. */
internal fun commitIosSaveEdit(
    save: Path, states: StateFiles, expected: ByteArray, edited: ByteArray,
): SaveEditFiles.Result = synchronized(tradeLock) {
    if (!recoverIosSaveTradesLocked()) return@synchronized SaveEditFiles.Result.Failed(iosSaveTradeRecoveryError())
    val marker = states.freshBootFile
    var previousMarker: ByteArray? = null
    var marked = false
    val disk = SaveEditFiles(
        backup = { StateFiles.backupBattery(it) },
        writeSynced = ::writeSynced,
        publish = { source, destination ->
            if (!marked) {
                previousMarker = marker.takeIf { it.isFile }?.readBytes()
                marker.writeTextAtomically("fresh")
                marked = true
            }
            FileSystem.SYSTEM.atomicMove(source, destination)
        },
    )
    val result = disk.write(SaveEditFiles.Edit(save, expected, edited))
    if (result is SaveEditFiles.Result.Written) {
        // The persisted marker already protects the edited battery even if cleanup cannot finish.
        runCatching { StateResume.startFresh(states) }
    } else if (marked && runCatching { save.readBytes().contentEquals(expected) }.getOrDefault(false)) {
        runCatching {
            val original = previousMarker
            if (original == null) marker.delete() else marker.writeAtomically(original)
        }
    }
    result
}

private val tradeLock = SynchronizedObject()

internal fun iosSaveTradeRecoveryError() = tr("Couldn't finish recovering the interrupted save trade. Open the app again to retry.")

internal fun recoverIosSaveTrades(): Boolean = synchronized(tradeLock) { recoverIosSaveTradesLocked() }

internal fun commitIosSaveTrade(a: SaveTradeJournal.Side, b: SaveTradeJournal.Side): SaveEditFiles.Result =
    synchronized(tradeLock) {
        if (!recoverIosSaveTradesLocked()) return@synchronized SaveEditFiles.Result.Failed(iosSaveTradeRecoveryError())
        val journal = tradeJournal()
        try {
            appFilesDir().mkdirs()
            journal.write(a, b)
        } catch (_: Exception) {
            return@synchronized SaveEditFiles.Result.Failed(tr("Can't write the save"))
        }
        val files = SaveEditFiles(
            backup = { StateFiles.backupBattery(it) },
            writeSynced = ::writeSynced,
            publish = FileSystem.SYSTEM::atomicMove,
        )
        val result = files.writeBoth(a.edit, b.edit)
        if (!recoverIosSaveTradesLocked()) SaveEditFiles.Result.Failed(iosSaveTradeRecoveryError()) else result
    }

private fun tradeJournal() = SaveTradeJournal(appFilesDir() / "save-trade.pending", ::writeSynced)

private fun recoverIosSaveTradesLocked(): Boolean = tradeJournal().recover(
    accept = { side ->
        val saveDir = RomPicker.savesDir.toPath(normalize = true)
        val key = side.stateKey
        side.edit.path.parent == saveDir && side.edit.path.name.endsWith(".sav") &&
            key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }
    },
    finishCommitted = { side -> StateResume.startFresh(StateFiles(RomPicker.statesDir.toPath() / side.stateKey)) },
)

private fun writeSynced(path: Path, data: ByteArray) {
    path.writeBytes(data)
    val fd = open(path.toString(), O_WRONLY)
    check(fd >= 0)
    try { check(fsync(fd) == 0) } finally { close(fd) }
}

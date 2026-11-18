@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.SaveEditFiles
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.delete
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.writeAtomically
import com.pokedaisy.app.companion.data.writeBytes
import com.pokedaisy.app.companion.data.writeTextAtomically
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open

/** Save edits publish while the game is closed. Commit fresh-boot intent before replacing the old battery. */
internal fun commitIosSaveEdit(save: Path, states: StateFiles, expected: ByteArray, edited: ByteArray): SaveEditFiles.Result {
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
    return result
}

private fun writeSynced(path: Path, data: ByteArray) {
    path.writeBytes(data)
    val fd = open(path.toString(), O_WRONLY)
    check(fd >= 0)
    try { check(fsync(fd) == 0) } finally { close(fd) }
}

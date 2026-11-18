package com.pokedaisy.app

import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.random.Random
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** Read/backup/stage/verify for closed games. Hosts supply their backup policy and durable file operations. */
class SaveEditFiles(
    private val backup: (Path) -> Path?,
    private val writeSynced: (Path, ByteArray) -> Unit,
    private val publish: (Path, Path) -> Unit,
) {
    sealed interface Result {
        class Written(val save: Path, val backup: Path) : Result
        class Failed(val reason: String) : Result
    }

    class Edit(val path: Path, val expected: ByteArray, val updated: ByteArray)

    fun write(edit: Edit): Result = synchronized(lock) { writeLocked(edit) }

    fun writeBoth(a: Edit, b: Edit): Result = synchronized(lock) {
        if (a.path == b.path) return@synchronized Result.Failed(tr("Both games use the same save file"))
        val first = writeLocked(b)
        if (first !is Result.Written) return@synchronized first
        val second = writeLocked(a)
        if (second is Result.Failed) {
            // Restore B only while it still contains our edit. If recovery fails, its original backup remains.
            val dir = b.path.parent!!
            val tmp = stagedPath(dir, b.path.name)
            val restored = try {
                matches(first.backup, b.expected) && restore(tmp, b.path, b.expected, still = b.updated)
            } finally {
                removeStaged(tmp)
            }
            if (!restored) return@synchronized restorationFailed(first.backup)
        }
        second
    }

    private fun writeLocked(edit: Edit): Result {
        val save = edit.path
        val dir = save.parent ?: return Result.Failed(tr("Can't write the save to {0}", save.toString()))
        val current = runCatching { save.readBytes() }.getOrNull()
            ?: return Result.Failed(tr("Can't read the save {0}", save.toString()))
        if (!current.contentEquals(edit.expected)) return changed()

        val backupFile = runCatching { backup(save) }.getOrNull()
        if (backupFile == null || !matches(backupFile, current)) {
            return Result.Failed(tr("Can't back up the save in {0} - nothing was changed", dir.toString()))
        }
        val tmp = stagedPath(dir, save.name)
        try {
            val staged = runCatching { writeSynced(tmp, edit.updated) }.isSuccess
            if (!staged || !matches(tmp, edit.updated)) return Result.Failed(tr("Can't write the save to {0}", dir.toString()))
            if (!matches(save, current)) return changed()
            if (runCatching { publish(tmp, save) }.isFailure) {
                return Result.Failed(tr("Can't write the save to {0}", dir.toString()))
            }
            if (!matches(save, edit.updated)) {
                return if (restore(tmp, save, current)) {
                    Result.Failed(tr("The save didn't read back as written - the backup was put back"))
                } else restorationFailed(backupFile)
            }
            return Result.Written(save, backupFile)
        } finally {
            removeStaged(tmp)
        }
    }

    private fun restore(tmp: Path, save: Path, original: ByteArray, still: ByteArray? = null): Boolean = runCatching {
        writeSynced(tmp, original)
        if (!matches(tmp, original) || (still != null && !matches(save, still))) return@runCatching false
        publish(tmp, save)
        matches(save, original)
    }.getOrDefault(false)

    private fun stagedPath(dir: Path, name: String) =
        dir / "$name.edit-${Random.nextLong().toULong().toString(16)}.tmp"

    private fun removeStaged(tmp: Path) { runCatching { FileSystem.SYSTEM.delete(tmp) } }

    private fun restorationFailed(backup: Path) =
        Result.Failed(tr("Couldn't restore the previous save. Its backup is {0}", backup.toString()))

    private fun changed() = Result.Failed(tr("The save changed since it was opened - open it again"))

    private fun matches(path: Path, expected: ByteArray): Boolean =
        runCatching { path.readBytes().contentEquals(expected) }.getOrDefault(false)

    private companion object {
        val lock = SynchronizedObject()
    }
}

package com.pokedaisy.app.ios

import com.pokedaisy.app.GameSaves
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.lastModified
import okio.Path

/** Include both in-place import backups and the copies made before running or rewinding a game. */
internal fun iosSaveBackups(save: Path): List<Path> {
    val directory = save.parent ?: return emptyList()
    return (GameSaves.backups(directory, save.name.substringBeforeLast('.')) + StateFiles.batteryBackups(save))
        .sortedWith(compareByDescending<Path> { it.lastModified }.thenByDescending { it.name })
}

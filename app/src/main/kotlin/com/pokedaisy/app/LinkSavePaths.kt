package com.pokedaisy.app

import okio.Path

/** Distinct battery destinations for linked cartridges, including repeats of the same cartridge. */
object LinkSavePaths {
    fun forGames(own: List<Path>): List<Path> {
        val reserved = own.toSet()
        val used = mutableSetOf<Path>()
        return own.map { save ->
            if (used.add(save)) return@map save
            var copy = 2
            val stem = save.name.substringBeforeLast('.')
            val extension = save.name.substringAfterLast('.', "")
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            val parent = requireNotNull(save.parent)
            var candidate: Path
            do {
                candidate = parent / "$stem (link ${copy++})$suffix"
            } while (candidate in reserved || !used.add(candidate))
            candidate
        }
    }
}

package com.pokedaisy.app.ios

import com.pokedaisy.app.cheats.Cheat
import com.pokedaisy.app.cheats.CheatFiles
import com.pokedaisy.app.cheats.CheatStore
import com.pokedaisy.app.companion.data.romCrc32
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.GameViewController
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import platform.Foundation.NSLog

/** Storage and lifecycle only; parsing, validation, file format and cheat execution are shared. Main/game thread. */
internal class IosGameCheats(private val romPath: String, private val game: GameViewController,
                             private val enabled: () -> Boolean) {
    val supported = romPath.substringAfterLast('.').lowercase() == "gba"
    private var store: CheatStore? = null
    val editable get() = store != null
    var list: List<Cheat> = emptyList()
        private set

    fun start() {
        if (!supported) return
        store = runCatching { CheatStore.forCrc(appFilesDir(), romCrc32(romPath.toPath()).toString(16).padStart(8, '0')) }.getOrNull()
        list = store?.load().orEmpty()
        apply()
    }

    fun apply() {
        if (supported && game.isRunning && PkApiCore.applyCheats(if (enabled()) CheatFiles.coreText(list) else "") < 0)
            NSLog("PokeDaisy: cheats didn't load")
    }

    fun save(cheats: List<Cheat>) {
        val store = store ?: return
        store.save(cheats)
        list = cheats
        apply()
    }

    fun import(path: String): CheatStore.Import? {
        val store = store ?: return null
        val fs = FileSystem.SYSTEM
        val file = path.toPath()
        val text = runCatching {
            if ((fs.metadataOrNull(file)?.size ?: 0) !in 1L..(1L shl 20)) return null
            fs.read(file) { readUtf8() }.removePrefix("\uFEFF")
        }.getOrNull() ?: return null
        val result = store.import(text, file.name.substringBeforeLast('.').ifBlank { tk("CHEAT") })
        list = store.load()
        apply()
        return result
    }
}

package com.pokedaisy.app.ios

import com.pokedaisy.app.achievements.RaSession
import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.delete
import com.pokedaisy.app.companion.data.writeAtomically
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.IosRaClient
import com.pokedaisy.app.game.RomPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

internal object IosAchievements {
    val session = RaSession(IosRaClient, { iosPreferences }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    fun start() {
        val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
        session.init("PokeDaisy/$version (iOS ${UIDevice.currentDevice.systemVersion})")
    }

    fun attach(rom: Path, restoredProgress: ByteArray?, track: Boolean) {
        val hash = lazy(LazyThreadSafetyMode.NONE) { if (track) RomIdentityReader.forPath(rom).raHash() else null }
        session.onCoreStarted({ hash.value }, restoredProgress)
    }

    fun writeState(path: Path): Boolean {
        if (!PkApiCore.saveState(path.toString())) return false
        val sidecar = StateFiles.achievementProgressFile(path)
        runCatching {
            val bytes = session.saveProgress()
            if (bytes == null) sidecar.delete() else sidecar.writeAtomically(bytes)
        }.onFailure { runCatching { sidecar.delete() } }
        return true
    }
}

internal fun iosStateFiles(rom: Path): StateFiles = iosStateFiles(StateFiles.romKey(rom))

internal fun iosStateFiles(key: String): StateFiles =
    StateFiles(RomPicker.statesDir.toPath() / key, listOf(StateFiles.ACHIEVEMENT_PROGRESS_SUFFIX))

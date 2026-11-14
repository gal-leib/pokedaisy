@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.data.Gen1Art
import com.pokedaisy.app.companion.data.Gen2Art
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.ui.TouchControls
import com.pokedaisy.app.companion.ui.TouchInput
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.game.GameViewController
import com.pokedaisy.app.game.RomPicker
import kotlinx.atomicfu.atomic
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSFileManager
import platform.Foundation.NSTimer
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue

/** How often the companion reads the running game (Android's sampler runs about this often too). */
private const val SAMPLE_SECONDS = 0.5

/**
 * What the iPad app's Swift side calls (as `PokeDaisyHost.shared` in Swift; not `PokeDaisy`, which is the app's own
 * Swift module): [start] once at launch, [companionViewController] for the companion's screens, [games] /
 * [addGames] for the games added from Files, [gameViewController] / [closeGame] to play one and
 * [touchControlsViewController] (or [setTouchKeys], for the app's own Swift buttons) to press its keys. While a game runs,
 * [telemetry] reads it every half second, off the main thread (the core's reads may come from any thread), and the
 * companion follows it.
 */
object PokeDaisyHost {
    /** The companion's view of the running game; "starting…" until a game feeds it. */
    val telemetry = TelemetryStore()

    /** Swift's: CLOSE GAME / RESTART GAME in the companion's SETTINGS (the game's place is SwiftUI's to change). */
    var onCloseRequested: () -> Unit = {}
    var onRestartRequested: () -> Unit = {}

    // The running game's SETTINGS; null = NO GAME RUNNING.
    private var settings by mutableStateOf<IosCompanionSettings?>(null)
    private var started = false
    private var game: GameViewController? = null
    private var timer: NSTimer? = null
    // 1 while a read is on its way, so a slow one never piles up behind the next.
    private val reading = atomic(false)

    /** Sets the shared screens' hosts (fonts, pictures, settings, the manual). Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        IosHosts.install(appFilesDir())
    }

    /** The companion (PARTY, BAG, MAP, ... SETTINGS) as a view controller, for any part of the window. */
    fun companionViewController(): UIViewController {
        start()
        return ComposeUIViewController {
            AppTheme {
                val snapshot by telemetry.snapshot.collectAsState()
                CompanionScreen(snapshot, settings = settings)
            }
        }
    }

    /** The games added from Files (their paths in the app's Documents/roms), by name. */
    fun games(): List<String> {
        val dir = RomPicker.romsDir
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>().orEmpty()
        return names.filter { it.substringAfterLast('.', "").lowercase() in RomPicker.EXTENSIONS }
            .sortedBy { it.lowercase() }
            .map { "$dir/$it" }
    }

    /** Files' picker over [from]; [onAdded] gets the games it copied in (none if cancelled). */
    fun addGames(from: UIViewController, onAdded: (List<String>) -> Unit) = RomPicker.pick(from, onAdded)

    /** The game at [romPath] running, its battery save beside the others; closes the one before. [onFailed] hears a
     * game that won't open. */
    fun gameViewController(romPath: String, onFailed: () -> Unit): UIViewController {
        closeGame()
        telemetry.reset()
        val g = GameViewController(romPath, RomPicker.savePathFor(romPath), onFailed)
        game = g
        val code = romCode(romPath)
        settings = IosCompanionSettings(romPath, code, onClose = { onCloseRequested() }, onRestart = { onRestartRequested() })
            .also { L10n.apply(it.appLanguage, code) }
        prefetchArt(romPath)
        timer = NSTimer.scheduledTimerWithTimeInterval(SAMPLE_SECONDS, repeats = true) { _ -> sample() }
        return g
    }

    /**
     * The on-screen buttons for the running game: the app's own touch controls (the Android app's drawing and hit
     * test), laid out for a strip under the game. The extras (FF, QUICK MENU, states, turbo...) and X / Y stay off
     * until the iPad app has what they press.
     */
    fun touchControlsViewController(gameBoy: Boolean): UIViewController {
        start()
        val style = TouchControlsStyle(extras = emptySet(), xyButtons = false)
        val input = TouchInput(onKeys = { gba, _ -> setTouchKeys(gba) })
        return ComposeUIViewController {
            AppTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    TouchControls(style, portrait = true, input = input, modifier = Modifier.fillMaxSize(), gameBoy = gameBoy)
                }
            }
        }
    }

    /** The keys the on-screen buttons hold ([com.pokedaisy.app.core.PkApiCore.KEY_A] ...), on the main thread. */
    fun setTouchKeys(keys: Int) {
        game?.touchKeys = keys
    }

    /** Writes the running game's save and closes it; the companion goes back to waiting. */
    fun closeGame() {
        timer?.invalidate()
        timer = null
        game?.close()
        game = null
        settings = null
        telemetry.reset()
    }

    /** The party art, region maps and card pieces the screens draw, rebuilt from the player's ROM the first time it
     * runs (as the Android app does on launch; RomArt / Gen1Art / Gen2Art scan in the background). */
    private fun prefetchArt(romPath: String) {
        val files = appFilesDir()
        val rom = romPath.toPath()
        when (romPath.substringAfterLast('.', "").lowercase()) {
            "gba" -> RomArt.prefetch(files, "${rom.name}-${FileSystem.SYSTEM.metadataOrNull(rom)?.size ?: 0}", rom)
            "gb", "gbc" -> {
                Gen1Art.prefetch(files, rom)
                Gen2Art.prefetch(files, rom)
            }
        }
    }

    /** A GBA ROM's four-letter game code (header 0xAC), or null. */
    private fun romCode(romPath: String): String? {
        if (!romPath.lowercase().endsWith(".gba")) return null
        return runCatching {
            FileSystem.SYSTEM.source(romPath.toPath()).buffer().use { s ->
                s.skip(0xAC)
                s.readUtf8(4)
            }
        }.getOrNull()
    }

    private fun sample() {
        if (game?.isRunning != true || !reading.compareAndSet(false, true)) return
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            try {
                runCatching { telemetry.refresh() }
            } finally {
                reading.value = false
            }
        }
    }
}

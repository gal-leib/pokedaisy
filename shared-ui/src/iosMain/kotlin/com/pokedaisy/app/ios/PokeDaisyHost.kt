package com.pokedaisy.app.ios

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.game.GameViewController
import com.pokedaisy.app.game.RomPicker
import kotlinx.atomicfu.atomic
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
 * [addGames] for the games added from Files, and [gameViewController] / [closeGame] to play one. While a game runs,
 * [telemetry] reads it every half second, off the main thread (the core's reads may come from any thread), and the
 * companion follows it.
 */
object PokeDaisyHost {
    /** The companion's view of the running game; "starting…" until a game feeds it. */
    val telemetry = TelemetryStore()

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
                CompanionScreen(snapshot)
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
        timer = NSTimer.scheduledTimerWithTimeInterval(SAMPLE_SECONDS, repeats = true) { _ -> sample() }
        return g
    }

    /** Writes the running game's save and closes it; the companion goes back to waiting. */
    fun closeGame() {
        timer?.invalidate()
        timer = null
        game?.close()
        game = null
        telemetry.reset()
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

package com.pokedaisy.app.ios

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.theme.AppTheme
import platform.UIKit.UIViewController

/**
 * What the iPad app's Swift side calls (as `PokeDaisyHost.shared` in Swift; not `PokeDaisy`, which is the app's own Swift module): [start] once at launch, then
 * [companionViewController] for the companion's screens. The game side reads the running game into [telemetry]
 * (`telemetry.refresh()` after a frame, with EmulatorCore set to the iOS core) and the companion follows it.
 */
object PokeDaisyHost {
    /** The companion's view of the running game; "starting…" until a game feeds it. */
    val telemetry = TelemetryStore()

    private var started = false

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
}

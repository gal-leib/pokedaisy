package com.pokedaisy.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchShow
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.ui.*
import com.pokedaisy.app.companion.ui.theme.AppTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Developer-only interoperability experiment. Reuses the production engine, GL renderer, native input windows,
 * shared controls and companion. Run separately with --es layout native / compose; never changes the game's screen.
 * All game/save/cache data belongs to this probe, under cacheDir/compose-stage-probe. See docs/COMPOSE_STAGE_PROBE.md.
 */
class ComposeStageProbeActivity : Activity() {
    private val input = GbaInput()
    private val owner = ComposeHostOwner()
    private lateinit var game: EmulatorView
    private lateinit var controls: TouchControlsView
    private lateinit var engine: EmulatorEngine
    private lateinit var directory: File
    private lateinit var rom: File
    private var nativeStage: GameStageLayout? = null
    private var side: SidePanel? = null
    private var upright: PortraitPanel? = null
    private var windowShown by mutableStateOf(false)
    private var narrow by mutableStateOf(false)
    private val surfaces = AtomicInteger()
    @Volatile private var frames = 0L
    private var began = 0L
    private val style = TouchControlsStyle(show = TouchShow.ALWAYS, xyButtons = false, extras = emptySet())
    private val snapshot = SnapshotView(connected = true, unsupported = true, error = "Generated diagnostic ROM")
    private val mode get() = if (intent.getStringExtra("layout") == "native") "native" else "compose"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner.create()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        directory = File(cacheDir, "compose-stage-probe").apply { mkdirs() }
        // A fixed probe-owned file; adb can populate it through run-as without exposing the player's ROMs.
        rom = File(directory, "diagnostic.gba")
        if (!rom.isFile) { Log.e(TAG, "Copy the generated diagnostic to ${rom.path} first"); finish(); return }
        GameVolume.set(0, true)
        game = EmulatorView(this)
        game.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surface created=${surfaces.incrementAndGet()} view=${System.identityHashCode(game)} mode=$mode")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "surface size=${width}x$height view=${System.identityHashCode(game)} mode=$mode")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { Log.i(TAG, "surface destroyed mode=$mode") }
        })
        controls = TouchControlsView(this).apply {
            this.style = this@ComposeStageProbeActivity.style
            onMask = { keys, turbo ->
                input.setTouchBits(if (windowShown) 0 else keys, if (windowShown) 0 else turbo)
                Log.i(TAG, "keys=$keys mask=${input.mask} thread=${Thread.currentThread().name} uptime=${SystemClock.uptimeMillis()} mode=$mode")
            }
            inputAllowed = { !windowShown }
            onPlaced = {
                val a = TouchGeometry.place(this.style, width.toFloat(), height.toFloat(), resources.displayMetrics.density,
                    portrait, gameAspect, gameBoy = !shoulders, ds = dsButtons).item(com.pokedaisy.app.companion.TouchButton.A)
                if (a != null) {
                    val location = IntArray(2)
                    getLocationOnScreen(location)
                    Log.i(TAG, "A x=${(location[0] + a.cx).toInt()} y=${(location[1] + a.cy).toInt()} mode=$mode")
                }
            }
        }
        engine = EmulatorEngine(input, SaveStates(directory, "diagnostic"), FfMusicCache(directory, "diagnostic")).apply {
            onCoreReady = { w, h -> MgbaCore.pkVideoBuffer()?.let { game.bindCore(it, MgbaCore.pkWideBuffer(), w, h) } }
            onCoreStopping = game::unbindCoreBlocking
            onFrame = { game.publishFrame(); frames++ }
        }
        game.onVsync = engine::onVsync
        if (mode == "native") makeNativeStage()
        owner.resume()
        val root = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent { Probe() }
        }
        setContentView(root)
        Log.i(TAG, "start mode=$mode view=${System.identityHashCode(game)} nativeWindows=${TouchInputWindows.supported}")
    }

    /** Uses the real Android containers and private preferences, including the real portrait grip. */
    private fun makeNativeStage() {
        val context = object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("compose-stage-probe-$name", mode)
        }
        val prefs = Prefs(context).apply { companionWindow = false; sidePanelDocked = true }
        val bar = View(this).apply { visibility = View.GONE }
        val root = GameStageLayout(this, game, bar, emptyList()).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(game, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(0, 0))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
        }
        nativeStage = root
        side = SidePanel(root, game, controls, prefs, CompanionBack(), CompanionKeys(), {}, companion = { CompanionScreen(snapshot) })
        upright = PortraitPanel(root, game, controls, prefs, CompanionBack(), {}) { CompanionScreen(snapshot) }
        root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> configureNative(r - l) }
        configureNative(resources.displayMetrics.widthPixels)
    }

    private fun configureNative(width: Int) {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        nativeStage?.topAligned = portrait
        controls.portrait = portrait
        if (portrait) side?.setEnabled(false) else upright?.setEnabled(false)
        side?.setEnabled(!portrait)
        upright?.setEnabled(portrait)
        upright?.padStrip = if (portrait) TouchGeometry.naturalHeight(style, width.toFloat(), resources.displayMetrics.density) else 0
    }

    @Composable private fun Probe() {
        AppTheme {
            Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { windowShown = !windowShown; input.releaseAll(); controls.syncInputWindows() }) { Text("Window") }
                    Button(onClick = { narrow = !narrow }) { Text("Resize") }
                    Button(onClick = { metrics() }) { Text("Report") }
                    Button(onClick = {
                        Log.i(TAG, "UI stall begin uptime=${SystemClock.uptimeMillis()}")
                        SystemClock.sleep(200)
                        Log.i(TAG, "UI stall end uptime=${SystemClock.uptimeMillis()}")
                    }) { Text("Stall") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxHeight().fillMaxWidth(if (narrow) .72f else 1f)) {
                        if (mode == "native") AndroidView(factory = { nativeStage!! }, modifier = Modifier.fillMaxSize())
                        else {
                            val density = LocalDensity.current.density
                            val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
                            val stage = gamePanelLayout(maxWidth.value * density, maxHeight.value * density, density, style = style)
                            GameScreen(stage,
                                game = { AndroidView(factory = { game }, modifier = Modifier.fillMaxSize()) },
                                companion = { SidePanelHost(window = false, onClose = {}) { CompanionScreen(snapshot) } },
                                controls = { AndroidView(factory = { controls }, modifier = Modifier.fillMaxSize(), update = { it.portrait = portrait }) })
                        }
                    }
                    if (windowShown) SidePanelHost(window = true, onClose = {
                        windowShown = false
                        input.releaseAll()
                        controls.syncInputWindows()
                    }) { CompanionScreen(snapshot) }
                }
            }
        }
    }

    private fun metrics() {
        Log.i(TAG, "report mode=$mode uptime=${SystemClock.uptimeMillis()} elapsed=${SystemClock.elapsedRealtime() - began} frames=$frames shown=${game.framesShown} late=${game.lateFrames} repeats=${game.repeatedDraws} unshown=${game.unshownFrames} surfaces=${surfaces.get()} view=${System.identityHashCode(game)} mask=${input.mask} error=${engine.lastError}")
    }

    override fun onResume() {
        super.onResume()
        if (!::engine.isInitialized) return
        began = SystemClock.elapsedRealtime()
        game.onResume()
        engine.start(rom, File(directory, "diagnostic.sav"), null)
    }

    override fun onPause() {
        if (::engine.isInitialized) {
            controls.releaseInputWindows()
            input.releaseAll()
            metrics()
            engine.stop()
            game.onPause()
        }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::controls.isInitialized) return
        controls.inputWindowsConfigurationChanged()
        configureNative(nativeStage?.width ?: resources.displayMetrics.widthPixels)
        game.reportGamePixel()
    }

    override fun onDestroy() {
        if (::controls.isInitialized) controls.releaseInputWindows()
        owner.destroy()
        GameVolume.load(Prefs(this))
        super.onDestroy()
    }

    private companion object { const val TAG = "pokedaisy/stage-probe" }
}

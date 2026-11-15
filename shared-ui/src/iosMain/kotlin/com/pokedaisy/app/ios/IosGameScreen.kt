package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.ui.*
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.GameViewController
import platform.Foundation.NSUserDefaults
import platform.QuartzCore.CACurrentMediaTime
import kotlin.math.roundToInt

/** iOS storage, controller and input-ownership adapter. Screen assembly and every panel gesture are shared. */
internal class IosGameScreen(
    private val game: GameViewController?,
    private val touch: IosTouchControls,
    private val gameBoy: Boolean,
) {
    private val d = NSUserDefaults.standardUserDefaults
    val back = CompanionBack()
    val keys = CompanionKeys()
    private var releaseFocus: () -> Unit = {}
    private var previousButtons = 0
    private var menuSuppressed = 0
    private var nextRepeat = 0.0
    private var portraitGripHeight = 0
    private var density = 1f
    private val aspect = if (gameBoy) 160f / 144 else 1.5f
    private var frames by mutableStateOf<GameScreenFrames?>(null)
    private fun bool(key: String, default: Boolean) = if (d.objectForKey(key) == null) default else d.boolForKey(key)
    private fun number(key: String, default: Float) = if (d.objectForKey(key) == null) default else d.doubleForKey(key).toFloat()

    val state = GameScreenState(
        // Preserve iOS's existing docked default. These are Android's preference keys and shared state thereafter.
        side = SidePanelState(window = bool("companion_window", false), docked = bool("side_panel_docked", true),
            widthFraction = number("side_panel_width", .5f)),
        portrait = PortraitPanelState(ratio = number("portrait_companion_ratio", PortraitLayout.DEFAULT_RATIO),
            hidden = bool("portrait_companion_hidden", false), underGame = IosTouchPreferences.underGame),
        saveSide = { d.setDouble(it.widthFraction.toDouble(), "side_panel_width"); d.setBool(it.docked, "side_panel_docked") },
        savePortrait = { d.setDouble(it.ratio.toDouble(), "portrait_companion_ratio"); d.setBool(it.hidden, "portrait_companion_hidden") },
        windowChanged = { on ->
            menuSuppressed = game?.controllerButtons ?: 0
            previousButtons = 0
            nextRepeat = CACurrentMediaTime() + .35
            game?.companionInputCaptured = on
            touch.releaseKeys()
        },
        companionHidden = { releaseFocus() },
    )
    val portrait get() = state.portrait.enabled
    var window: Boolean
        get() = state.side.window
        set(on) { d.setBool(on, "companion_window"); state.setSide(state.side.copy(window = on)) }

    init {
        game?.onCompanionButtons = ::controllerInput
        touch.previewStage = ::previewStage
        touch.beforeEdit = { if (state.side.windowOpen) state.hide() }
    }

    @Composable
    fun Content(gameContent: @Composable () -> Unit, companion: @Composable () -> Unit) {
        val focus = LocalFocusManager.current
        DisposableEffect(focus) {
            releaseFocus = { focus.clearFocus(force = true) }
            onDispose { releaseFocus = {} }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val upright = maxHeight >= maxWidth
            density = LocalDensity.current.density
            portraitGripHeight = with(LocalDensity.current) { (rememberGbaTextMetrics().u.toPx() * 24).roundToInt() }
            val visible = IosTouchPreferences.visible
            val strip = if (visible && IosTouchPreferences.underGame)
                TouchGeometry.naturalHeight(IosTouchPreferences.style, constraints.maxWidth.toFloat(), density, gameBoy) else 0
            SideEffect {
                state.setSide(state.side.withEnabled(!upright))
                state.setPortrait(state.portrait.copy(enabled = upright, underGame = IosTouchPreferences.underGame, padStrip = strip))
            }
            GameScreen(state, gameHeight = { (it / aspect).roundToInt() },
                game = { GamePicture(aspect, upright, Modifier.fillMaxSize(), gameContent) },
                controls = { if (visible) touch.Controls(gameBoy, upright) },
                companion = {
                    CompositionLocalProvider(LocalCompanionTopInset provides (frames?.portrait?.topInset(state.gripHeight) ?: 0),
                        LocalCompanionBack provides back, LocalCompanionKeys provides keys) {
                        // The same host/call site in either orientation keeps tab, scroll and navigation state.
                        SidePanelHost(window = !upright && state.side.isWindow, onClose = state::hide, keys = keys,
                            compactText = minOf(maxWidth, maxHeight) >= 600.dp, content = companion)
                    }
                },
                pictureOverlay = { game?.keyCapture?.let { KeyCapturePopup(it) } },
                dismissSideOnGameTap = !visible,
                onFrames = { measured, _ -> frames = measured; touch.stage = stage(measured, visible) })
        }
    }

    /** The editor closes a modal window first; hidden controls use a correctly sized visible stand-in. */
    fun previewStage(otherOrientation: Boolean = false): TouchStage? {
        if (state.width <= 0 || state.height <= 0) return null
        val w = if (otherOrientation) state.height else state.width
        val h = if (otherOrientation) state.width else state.height
        val upright = h >= w
        val strip = if (IosTouchPreferences.underGame)
            TouchGeometry.naturalHeight(IosTouchPreferences.style, w.toFloat(), density, gameBoy) else 0
        val p = state.portrait.copy(enabled = upright, padStrip = strip, underGame = IosTouchPreferences.underGame)
        val side = state.side.withEnabled(!upright)
        val measured = gameScreenFrames(w, h, (w / aspect).roundToInt(), if (upright) portraitGripHeight else 0, side, p)
        return stage(measured, true, w, h, upright)
    }

    private fun stage(f: GameScreenFrames, visible: Boolean, w: Int = state.width, h: Int = state.height,
                      upright: Boolean = state.portrait.enabled): TouchStage {
        val picture = fittedGamePicture(f.game.width, f.game.height, aspect, upright).translate(f.game.left, f.game.top)
        val area = if (visible) f.controls else Rect(0f, h.toFloat(), w.toFloat(), h.toFloat())
        val companion = f.companion.takeIf { f.companionShown && (upright || !state.side.isWindow) }
        return TouchStage(w.toFloat(), h.toFloat(), density, upright, picture, area, companion, aspect, gameBoy)
    }

    private fun controllerInput(buttons: Int) {
        if (!state.side.windowOpen) return
        menuSuppressed = menuSuppressed and buttons
        val current = buttons and menuSuppressed.inv()
        val pressed = current and previousButtons.inv()
        val now = CACurrentMediaTime()
        val repeated = if (now >= nextRepeat) current and DIRECTIONS else 0
        if (pressed != 0) nextRepeat = now + .35 else if (repeated != 0) nextRepeat = now + .1
        previousButtons = current
        for ((bit, key) in BUTTONS) {
            if (!state.side.windowOpen) break
            when {
                pressed and bit != 0 -> menuKey(key, false)
                repeated and bit != 0 -> menuKey(key, true)
            }
        }
    }
    private fun menuKey(key: MenuKey, repeat: Boolean) {
        when (key) {
            MenuKey.B -> if (!repeat && !back.back()) state.hide()
            MenuKey.START -> if (!repeat) state.hide()
            else -> keys.key(key, repeat)
        }
    }
    fun close() {
        state.setSide(state.side.withEnabled(false))
        game?.onCompanionButtons = {}
        game?.companionInputCaptured = false
        touch.previewStage = null
        touch.beforeEdit = {}
        releaseFocus()
    }
    private companion object {
        const val DIRECTIONS = PkApiCore.KEY_UP or PkApiCore.KEY_DOWN or PkApiCore.KEY_LEFT or PkApiCore.KEY_RIGHT
        val BUTTONS = listOf(PkApiCore.KEY_B to MenuKey.B, PkApiCore.KEY_START to MenuKey.START,
            PkApiCore.KEY_L to MenuKey.L, PkApiCore.KEY_R to MenuKey.R, PkApiCore.KEY_UP to MenuKey.UP,
            PkApiCore.KEY_DOWN to MenuKey.DOWN, PkApiCore.KEY_LEFT to MenuKey.LEFT, PkApiCore.KEY_RIGHT to MenuKey.RIGHT,
            PkApiCore.KEY_A to MenuKey.A, PkApiCore.KEY_SELECT to MenuKey.SELECT)
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import com.pokedaisy.app.companion.StateSlotController
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.ui.gamePanelLayout
import com.pokedaisy.app.companion.ui.TouchControls
import com.pokedaisy.app.companion.ui.TouchInput
import com.pokedaisy.app.companion.ui.TouchLayoutEditor
import com.pokedaisy.app.companion.ui.decodeImage
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.GameViewController
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController

/** The host's native presentation/input bridge. Drawing, hit testing, settings and editing come from shared UI. */
internal class IosTouchControls(
    private val game: () -> GameViewController?,
    private val settings: () -> IosCompanionSettings?,
    private val slots: () -> StateSlotController?,
) {
    var stage by mutableStateOf<TouchStage?>(null)
    private var editor: UIViewController? = null
    private var ffDownAt: Double? = null
    private var inputEpoch by mutableIntStateOf(0)
    var previewStage: ((Boolean) -> TouchStage?)? = null
    var beforeEdit: () -> Unit = {}

    fun releaseKeys() {
        inputEpoch++
        game()?.let { it.touchKeys = 0; it.touchTurbo = 0 }
        settings()?.holdFastForward(false)
        settings()?.holdTouchRewind(false)
        ffDownAt = null
    }

    fun close() {
        releaseKeys()
        editor?.dismissViewControllerAnimated(false, completion = null)
        editor = null
        stage = null
    }

    @Composable
    fun Controls(gameBoy: Boolean, portrait: Boolean) {
        val input = remember {
            TouchInput(
                onKeys = { keys, turbo -> game()?.takeUnless { it.uiPaused }?.let { it.touchKeys = keys; it.touchTurbo = turbo } },
                onAction = ::action,
                onPress = { IosTouchPreferences.feel(IosTouchPreferences.style.vibration) })
        }
        DisposableEffect(Unit) { onDispose { releaseKeys() } }
        // Changing ownership cancels existing fingers; only a new DOWN may play after a modal closes.
        key(inputEpoch) {
            TouchControls(IosTouchPreferences.drawingStyle, portrait, input, Modifier.fillMaxSize(),
                gameAspect = if (gameBoy) 160f / 144 else 1.5f, gameBoy = gameBoy, ffOn = settings()?.ffActive == true)
        }
    }

    /** A hidden pad still has an editable stand-in, using the same layout with controls enabled. */
    fun readStage(): TouchStage? = stage?.let {
        previewStage?.invoke(false) ?: if (IosTouchPreferences.visible) it else
            gamePanelLayout(it.w, it.h, it.dp, gameBoy = it.gameBoy, underGame = IosTouchPreferences.underGame, style = IosTouchPreferences.style)
    }

    fun edit() {
        if (editor != null) return
        val current = game() ?: return
        beforeEdit()
        val measured = readStage() ?: return
        val from = current.view.window?.rootViewController ?: return
        releaseKeys()
        current.uiPaused = true
        current.pause()
        presentEditor(from, measured, current, PkApiCore.stateThumbnail()?.let(::decodeImage))
    }

    fun editStandalone(from: UIViewController, stage: TouchStage? = null) {
        if (editor != null || stage == null) return
        presentEditor(from, stage, null, null)
    }

    private fun presentEditor(from: UIViewController, measured: TouchStage, current: GameViewController?,
                              shot: androidx.compose.ui.graphics.ImageBitmap?) {
        val view = ComposeUIViewController(configure = { configureUiAutomation() }) {
            AppTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    TouchLayoutEditor(measured, IosTouchPreferences.drawingStyle, rememberGbaTextMetrics(),
                        shot = shot, drawBackdrop = true,
                        onDone = { layout ->
                            IosTouchPreferences.style = IosTouchPreferences.style.withLayout(measured.portrait, layout)
                            editor?.dismissViewControllerAnimated(true) {
                                editor = null
                                current?.uiPaused = false
                                current?.resume()
                            }
                        })
                }
            }
        }
        view.modalPresentationStyle = UIModalPresentationFullScreen
        editor = view
        from.presentViewController(view, animated = true, completion = null)
    }

    private fun action(button: TouchButton, down: Boolean) {
        if (game()?.let { it.isRunning && !it.uiPaused && !it.companionInputCaptured } != true) return
        val s = settings() ?: return
        when (button) {
            TouchButton.FF -> if (down) {
                ffDownAt = CACurrentMediaTime()
                s.holdFastForward(true)
            } else {
                s.holdFastForward(false)
                ffDownAt?.let { if (CACurrentMediaTime() - it < 0.3) s.setFfToggled(!s.ffToggled) }
                ffDownAt = null
            }
            TouchButton.SAVE_STATE -> if (down) slots()?.let { it.requestSave(it.currentIndex) }
            TouchButton.LOAD_STATE -> if (down) slots()?.let { it.requestLoad(it.currentIndex) }
            TouchButton.REWIND -> s.holdTouchRewind(down)
            else -> Unit
        }
    }
}

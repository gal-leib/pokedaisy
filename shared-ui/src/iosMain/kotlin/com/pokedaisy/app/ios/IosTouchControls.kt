@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.ComposeUIViewController
import com.pokedaisy.app.companion.data.EmulatorCore
import com.pokedaisy.app.companion.StateSlotController
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.ui.gamePanelLayout
import com.pokedaisy.app.companion.ui.GamePanelLayout
import com.pokedaisy.app.companion.ui.TouchControls
import com.pokedaisy.app.companion.ui.TouchGeometry
import com.pokedaisy.app.companion.ui.TouchInput
import com.pokedaisy.app.companion.ui.TouchLayoutEditor
import com.pokedaisy.app.companion.ui.decodeImage
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.GameViewController
import kotlinx.cinterop.useContents
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
        private set
    private var editor: UIViewController? = null
    private var ffDownAt: Double? = null

    fun layout(width: Double, height: Double, gameBoy: Boolean): GamePanelLayout = gamePanelLayout(
        width.toFloat(), height.toFloat(), 1f, IosTouchPreferences.drawingStyle, IosTouchPreferences.visible, gameBoy)

    fun standaloneStage(from: UIViewController): TouchStage {
        val dp = from.view.contentScaleFactor.toFloat()
        return from.view.bounds.useContents {
            gamePanelLayout(size.width.toFloat() * dp, size.height.toFloat() * dp, dp,
                IosTouchPreferences.drawingStyle).touchStage(dp)
        }
    }

    fun releaseKeys() {
        game()?.let { it.touchKeys = 0; it.touchTurbo = 0 }
        settings()?.holdFastForward(false)
        ffDownAt = null
    }

    fun close() {
        releaseKeys()
        editor?.dismissViewControllerAnimated(false, completion = null)
        editor = null
        stage = null
    }

    fun controller(gameBoy: Boolean, portrait: Boolean): UIViewController {
        lateinit var controller: UIViewController
        val input = TouchInput(
            onKeys = { keys, turbo -> game()?.takeUnless { it.uiPaused }?.let { it.touchKeys = keys; it.touchTurbo = turbo } },
            onAction = ::action,
            onPress = { IosTouchPreferences.feel(IosTouchPreferences.style.vibration) })
        controller = ComposeUIViewController {
            AppTheme {
                val dp = LocalDensity.current.density
                DisposableEffect(Unit) { onDispose { releaseKeys() } }
                Box(Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { coordinates ->
                    val window = controller.view.window
                    if (window != null && coordinates.size.width > 0 && coordinates.size.height > 0) {
                        val pos = coordinates.positionInWindow()
                        val bounds = window.bounds.useContents { Rect(0f, 0f, size.width.toFloat() * dp, size.height.toFloat() * dp) }
                        val picture = game()?.view?.let { view ->
                            view.convertRect(view.bounds, toView = window).useContents {
                                Rect(origin.x.toFloat() * dp, origin.y.toFloat() * dp,
                                    (origin.x + size.width).toFloat() * dp, (origin.y + size.height).toFloat() * dp)
                            }
                        } ?: Rect.Zero
                        val area = Rect(pos.x, pos.y, pos.x + coordinates.size.width, pos.y + coordinates.size.height)
                        stage = TouchStage(bounds.width, bounds.height, dp, portrait, picture, area,
                            companion = if (portrait) Rect(0f, area.bottom, bounds.right, bounds.bottom)
                                else Rect(area.right, picture.top, bounds.right, bounds.bottom),
                            gameAspect = if (gameBoy) 160f / 144 else 1.5f, gameBoy = gameBoy)
                    }
                }) {
                    if (IosTouchPreferences.visible) TouchControls(
                        IosTouchPreferences.drawingStyle, portrait, input, Modifier.fillMaxSize(),
                        gameAspect = if (gameBoy) 160f / 144 else 1.5f, gameBoy = gameBoy,
                        ffOn = settings()?.ffActive == true)
                }
            }
        }
        return controller
    }

    /** SHOW can be NEVER (or AUTO with a pad): the editor still needs a virtual controls area below the picture. */
    fun readStage(): TouchStage? {
        if (IosTouchPreferences.visible) stage?.let { return it }
        val current = game() ?: return null
        val view = current.view
        val window = view.window ?: return null
        val dp = view.contentScaleFactor.toFloat()
        val bounds = window.bounds.useContents { Rect(0f, 0f, size.width.toFloat() * dp, size.height.toFloat() * dp) }
        val picture = view.convertRect(view.bounds, toView = window).useContents {
            Rect(origin.x.toFloat() * dp, origin.y.toFloat() * dp,
                (origin.x + size.width).toFloat() * dp, (origin.y + size.height).toFloat() * dp)
        }
        val gameBoy = PkApiCore.platform() == EmulatorCore.PLATFORM_GB
        val height = TouchGeometry.naturalHeight(IosTouchPreferences.drawingStyle, picture.width, dp, gameBoy)
            .toFloat().coerceAtMost(bounds.height)
        val top = picture.bottom.coerceAtMost(bounds.height - height)
        return TouchStage(bounds.width, bounds.height, dp, bounds.height > bounds.width, picture,
            Rect(picture.left, top, picture.right, top + height),
            gameAspect = if (gameBoy) 160f / 144 else 1.5f, gameBoy = gameBoy)
    }

    fun edit() {
        if (editor != null) return
        val current = game() ?: return
        val measured = readStage() ?: return
        val from = current.view.window?.rootViewController ?: return
        val window = current.view.window ?: return
        // The measured stage is in window pixels. The editor's body excludes the same safe-area insets.
        val inset = window.safeAreaInsets.useContents {
            floatArrayOf(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()).map { it * measured.dp }
        }
        val offset = Offset(-inset[0], -inset[1])
        val safe = measured.copy(w = measured.w - inset[0] - inset[2], h = measured.h - inset[1] - inset[3],
            game = measured.game.translate(offset), area = measured.area.translate(offset),
            companion = measured.companion?.translate(offset))
        releaseKeys()
        current.uiPaused = true
        current.pause()
        presentEditor(from, safe, current, PkApiCore.stateThumbnail()?.let(::decodeImage))
    }

    fun editStandalone(from: UIViewController, stage: TouchStage? = null) {
        if (editor == null) presentEditor(from, stage ?: standaloneStage(from), null, null)
    }

    private fun presentEditor(from: UIViewController, measured: TouchStage, current: GameViewController?,
                              shot: androidx.compose.ui.graphics.ImageBitmap?) {
        val view = ComposeUIViewController {
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
        if (game()?.let { it.isRunning && !it.uiPaused } != true) return
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
            else -> Unit
        }
    }
}

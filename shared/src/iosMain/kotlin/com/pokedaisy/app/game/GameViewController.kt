@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkApiCore
import kotlinx.cinterop.CValue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Metal.MTLClearColor
import platform.MetalKit.MTKView
import platform.MetalKit.MTKViewDelegateProtocol
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/** Up to 5 s of frames for a save the game is writing (pk_api_finish_save); top level, as a Kotlin subclass of an
 * Objective-C class can't have a companion with fields. */
private const val FINISH_SAVE_FRAMES = 300

/**
 * A running game for the iPad app: the game drawn with Metal ([GameRenderer]), heard ([GameAudio]) and played with
 * controllers, a keyboard or [touchKeys] ([GameInput]). The app shell embeds its view wherever the game goes (full
 * screen, or beside the companion) and puts its own controls over it; nothing else of the app is in here.
 *
 * The display's refreshes drive it, on the main thread, which is the game's (pk_api.h's one thread): each refresh runs
 * the frames [FramePacer] says are due, then draws the last. It opens [romPath] with its battery save at [savePath]
 * when its view loads and pauses while the app isn't active; [close] writes the save and closes it (call it before
 * letting go of the controller). [onFailed] hears it if the game won't open.
 */
class GameViewController(
    private val romPath: String,
    private val savePath: String?,
    private val onFailed: (() -> Unit)? = null,
) : UIViewController(nibName = null, bundle = null) {
    private val renderer = GameRenderer.create()
    private val queue = renderer?.device?.newCommandQueue()
    private val audio = GameAudio()
    private val input = GameInput()
    private val pacer = FramePacer()
    private val observers = mutableListOf<NSObjectProtocol>()
    private var running = false
    private lateinit var gameView: MTKView

    /** The keys the app's on-screen controls hold ([PkApiCore.KEY_A] ...), main thread. */
    var touchKeys: Int
        get() = input.touchKeys
        set(value) {
            input.touchKeys = value
        }

    /** Whether a game is open and running in this controller. */
    val isRunning: Boolean get() = running

    // Kept by the controller: MTKView holds its delegate weakly.
    private val drawer = object : NSObject(), MTKViewDelegateProtocol {
        override fun drawInMTKView(view: MTKView) = refresh(view)

        override fun mtkView(view: MTKView, drawableSizeWillChange: CValue<CGSize>) {}
    }

    override fun loadView() {
        val v = MTKView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), device = renderer?.device)
        v.preferredFramesPerSecond = 60
        v.clearColor = cValue<MTLClearColor> {
            red = GameRenderer.CLEAR_RED
            green = GameRenderer.CLEAR_GREEN
            blue = GameRenderer.CLEAR_BLUE
            alpha = 1.0
        }
        v.delegate = drawer
        gameView = v
        view = v
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        if (renderer == null) NSLog("PokeDaisy: no Metal device, the game won't show")
        if (!PkApiCore.start(romPath, savePath)) {
            NSLog("PokeDaisy: the game didn't open: %@", romPath)
            gameView.paused = true
            onFailed?.invoke()
            return
        }
        running = true
        audio.start()
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
            _ -> pause()
        }
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            _ -> resume()
        }
    }

    /** Stops running (the app went to the background, or the shell asks); the game stays open. */
    fun pause() {
        if (!running) return
        gameView.paused = true
        audio.stop()
    }

    /** Runs again after [pause]. */
    fun resume() {
        if (!running) return
        pacer.reset()
        audio.start()
        gameView.paused = false
    }

    /** Writes the game's save and closes it; the controller then shows nothing. */
    fun close() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        if (!running) return
        running = false
        gameView.paused = true
        audio.release()
        PkApiCore.finishSave(FINISH_SAVE_FRAMES)
        PkApiCore.stop()
    }

    private fun refresh(view: MTKView) {
        if (!running) return
        val frames = pacer.framesAt(CACurrentMediaTime())
        if (frames > 0) {
            PkApiCore.setKeys(input.keys())
            repeat(frames) {
                PkApiCore.runFrame()
                audio.push()
            }
            renderer?.upload()
        }
        draw(view)
    }

    private fun draw(view: MTKView) {
        val renderer = renderer ?: return
        val pass = view.currentRenderPassDescriptor ?: return
        val drawable = view.currentDrawable ?: return
        val buffer = queue?.commandBuffer() ?: return
        val encoder = buffer.renderCommandEncoderWithDescriptor(pass) ?: return
        view.drawableSize.useContents { renderer.encode(encoder, width, height) }
        encoder.endEncoding()
        buffer.presentDrawable(drawable)
        buffer.commit()
    }
}

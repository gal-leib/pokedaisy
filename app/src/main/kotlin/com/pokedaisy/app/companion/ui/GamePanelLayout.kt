package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchStage
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The game's screen on one screen laid out by Android's own rules: upright [PortraitLayout] (the companion under the
 * game when [underGame], the touch controls in a strip of their own along the bottom, [style]'s natural height, as
 * PokeDaisyActivity gives them; along the bottom, the gap the companion leaves, or over the game when there's none),
 * sideways [sidePanel] decides whether the companion is docked, over the game or put away. The default remains
 * docked for hosts that do not yet expose the panel controls. Portrait sizing reuses [PortraitLayout].
 */
fun gamePanelLayout(
    width: Float, height: Float, dp: Float,
    controlsVisible: Boolean = true, gameBoy: Boolean = false, underGame: Boolean = true,
    style: TouchControlsStyle? = null,
    sidePanel: SidePanelState = SidePanelState(enabled = true, open = true, window = false, docked = true),
    portraitRatio: Float = PortraitLayout.DEFAULT_RATIO, portraitHidden: Boolean = false,
): TouchStage {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    // Book mode uses left/right pages even when the enclosing window is taller than wide, as on Android.
    val portrait = h >= w && !(sidePanel.enabled && sidePanel.book != null)
    val aspect = if (gameBoy) 160f / 144 else 1.5f
    if (portrait) {
        val strip = if (controlsVisible && underGame && style != null && w > 0f) TouchGeometry.naturalHeight(style, w, dp, gameBoy) else 0
        val a = PortraitLayout.arrange(w.roundToInt(), h.roundToInt(), (w / aspect).roundToInt(),
            gripH = 0, ratio = portraitRatio, underGame = underGame, padStrip = strip, hidden = portraitHidden)
        val gh = min(w / aspect, a.gameBottom.toFloat()).coerceAtLeast(0f)
        val gw = gh * aspect
        val area = if (controlsVisible) Rect(0f, a.padTop.toFloat(), w, a.padBottom.toFloat()) else Rect(0f, h, w, h)
        return TouchStage(w, h, dp, true, Rect((w - gw) / 2, 0f, (w + gw) / 2, gh), area,
            if (portraitHidden) null else Rect(0f, a.companionTop.toFloat(), w, (a.companionTop + a.companionHeight).toFloat()), aspect, gameBoy)
    }
    val side = sidePanel.arrange(w.roundToInt())
    val column = (w - side.gameRightMargin).coerceAtLeast(0f)
    val gw = min(column, h * aspect)
    val gh = gw / aspect
    val companion = when {
        !sidePanel.shown -> null
        sidePanel.isWindow -> Rect(0f, 0f, w, h)
        else -> Rect((w - side.panelWidth).coerceAtLeast(0f), 0f, w, h)
    }
    return TouchStage(w, h, dp, false, Rect((column - gw) / 2, (h - gh) / 2, (column + gw) / 2, (h + gh) / 2),
        Rect(0f, 0f, (w - side.controlsRightMargin).coerceAtLeast(0f), if (controlsVisible) h else 0f),
        companion, aspect, gameBoy)
}

/** Shared layout slots around a platform's game surface. Repositioning never replaces the game controller. */
@Composable
fun GameScreen(
    stage: TouchStage, modifier: Modifier = Modifier,
    game: @Composable () -> Unit, companion: @Composable () -> Unit, controls: @Composable () -> Unit,
) {
    Layout(content = {
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { game() }
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { controls() }
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) {
            // Hidden companions must release their focus/input handlers and never measure a window at 0 x 0.
            if (stage.companion?.let { it.width > 0f && it.height > 0f } == true) companion()
        }
    }, modifier = modifier.fillMaxSize()) { items, constraints ->
        // An open companion window owns the overlay, including the scrim above the game's controls.
        val rects = listOf(stage.game, stage.area, stage.companion ?: Rect.Zero)
        val placed = items.zip(rects).map { (item, rect) ->
            val left = rect.left.roundToInt().coerceIn(0, constraints.maxWidth)
            val top = rect.top.roundToInt().coerceIn(0, constraints.maxHeight)
            val right = rect.right.roundToInt().coerceIn(left, constraints.maxWidth)
            val bottom = rect.bottom.roundToInt().coerceIn(top, constraints.maxHeight)
            Triple(item.measure(Constraints.fixed(right - left, bottom - top)), left, top)
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placed.forEach { (item, left, top) -> item.place(left, top) }
        }
    }
}

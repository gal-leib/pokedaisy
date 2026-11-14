package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchStage
import kotlin.math.min
import kotlin.math.roundToInt

/** Android's UNDER GAME arrangement upright, or a docked companion with the controls over the game sideways. */
fun gamePanelLayout(
    width: Float, height: Float, dp: Float, style: TouchControlsStyle,
    controlsVisible: Boolean = true, gameBoy: Boolean = false,
): TouchStage {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val portrait = h >= w
    val aspect = if (gameBoy) 160f / 144 else 1.5f
    if (portrait) {
        val controls = if (!controlsVisible) 0f else
            TouchGeometry.naturalHeight(style, w, dp, gameBoy).toFloat().coerceAtMost(h * 0.3f)
        val a = PortraitLayout.arrange(w.roundToInt(), h.roundToInt(), (w / aspect).roundToInt(),
            gripH = 0, ratio = PortraitLayout.DEFAULT_RATIO, underGame = true, padStrip = controls.roundToInt())
        val gh = min(w / aspect, a.gameBottom.toFloat()).coerceAtLeast(0f)
        val gw = gh * aspect
        return TouchStage(w, h, dp, true, Rect((w - gw) / 2, 0f, (w + gw) / 2, gh),
            Rect(0f, if (controlsVisible) a.padTop.toFloat() else h, w, h),
            Rect(0f, a.companionTop.toFloat(), w, (a.companionTop + a.companionHeight).toFloat()), aspect, gameBoy)
    }
    val column = w * (1f - SIDE_PANEL_DEFAULT_WIDTH)
    val gw = min(column, h * aspect)
    val gh = gw / aspect
    return TouchStage(w, h, dp, false, Rect((column - gw) / 2, (h - gh) / 2, (column + gw) / 2, (h + gh) / 2),
        Rect(0f, 0f, column, if (controlsVisible) h else 0f),
        Rect(column, 0f, w, h), aspect, gameBoy)
}

/** Shared layout slots around a platform's game surface. Repositioning never replaces the game controller. */
@Composable
fun GameScreen(
    stage: TouchStage, modifier: Modifier = Modifier,
    game: @Composable () -> Unit, companion: @Composable () -> Unit, controls: @Composable () -> Unit,
) {
    Layout(content = { Box { game() }; Box { companion() }; Box { controls() } }, modifier = modifier.fillMaxSize()) { items, constraints ->
        val rects = listOf(stage.game, stage.companion ?: Rect.Zero, stage.area)
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

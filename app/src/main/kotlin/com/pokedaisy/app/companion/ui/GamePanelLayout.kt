package com.pokedaisy.app.companion.ui

import androidx.compose.ui.geometry.Rect
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchStage
import kotlin.math.min

/** One-window tablet layout, also used by the app-settings editor's stand-in of the same screen. */
data class GamePanelLayout(
    val width: Float, val height: Float, val wide: Boolean,
    val columnWidth: Float, val gameHeight: Float, val controlsHeight: Float,
) {
    val columnHeight get() = gameHeight + controlsHeight
    fun touchStage(dp: Float, gameBoy: Boolean = false) = TouchStage(
        width, height, dp, !wide, Rect(0f, 0f, columnWidth, gameHeight),
        Rect(0f, gameHeight, columnWidth, columnHeight),
        companion = if (wide) Rect(columnWidth, 0f, width, height) else Rect(0f, columnHeight, width, height),
        gameAspect = if (gameBoy) 160f / 144 else 1.5f, gameBoy = gameBoy,
    )
}

fun gamePanelLayout(
    width: Float, height: Float, dp: Float, style: TouchControlsStyle,
    controlsVisible: Boolean = true, gameBoy: Boolean = false,
): GamePanelLayout {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val wide = w > h
    val controls = if (!controlsVisible) 0f else
        TouchGeometry.naturalHeight(style, w * if (wide) 0.6f else 1f, dp, gameBoy)
            .toFloat().coerceAtLeast(220 * dp).coerceAtMost(h * if (wide) 0.5f else 0.3f)
    val column = if (wide) min(w * 0.6f, (h - controls) * 1.5f) else w
    val game = min(column / 1.5f, h - controls)
    return GamePanelLayout(w, h, wide, column, game, controls)
}

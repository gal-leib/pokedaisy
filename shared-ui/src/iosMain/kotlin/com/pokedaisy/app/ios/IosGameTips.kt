package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.FeatureTips
import com.pokedaisy.app.companion.FieldHudPart
import com.pokedaisy.app.companion.ui.FieldTipActions
import com.pokedaisy.app.companion.FlyAction
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.GameRuntime
import com.pokedaisy.app.companion.ItemUse
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.TipId
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.FeatureTipHost
import com.pokedaisy.app.companion.ui.FlyStyleAsk
import com.pokedaisy.app.companion.ui.GameQuickActions
import com.pokedaisy.app.game.GameViewController
import kotlinx.coroutines.delay
import platform.GameController.GCController
import kotlin.time.TimeSource

internal class IosGameTips(
    private val game: GameViewController,
    private val runtime: GameRuntime,
    private val settings: IosCompanionSettings,
    private val screen: IosGameScreen,
    private val snapshot: () -> SnapshotView,
    items: () -> ItemUse?,
    fly: () -> FlyAction?,
    private val quick: GameQuickActions,
) {
    private val field = FieldTipActions(settings, snapshot, items, fly, { screen.triggerQuickMenu(true) },
        quick::run, screen::showQuickNotice)

    var summaryRequest by mutableIntStateOf(0)
        private set

    private fun touchHas(button: TouchButton) = IosTouchPreferences.visible && button in IosTouchPreferences.drawingStyle.extras

    private fun shortcut(action: Hotkeys.Action): String? {
        if (!settings.hotkeysEnabled || GCController.controllers().isEmpty()) return null
        return settings.hotkeyBindings()[action].orEmpty().map { it.split('+').map { key -> key.trim() } }
            .firstOrNull { keys -> keys.all { it.startsWith("BUTTON_") } }
            ?.joinToString(" + ") { KeyCapture.label(it) }
    }

    private fun available(tip: TipId) = when (tip) {
        TipId.ZOOM -> screen.expanded && (touchHas(TouchButton.ZOOM) || shortcut(Hotkeys.Action.ZOOM) != null)
        TipId.EXPAND -> settings.wideSupported && !screen.expanded
        TipId.QUICK_MENU -> (touchHas(TouchButton.QUICK_MENU) || shortcut(Hotkeys.Action.QUICK_MENU) != null) &&
            settings.quickMenuActions.any(quick::offered)
        TipId.HUD -> settings.fieldHud && !screen.portrait && FieldHudPart.PARTY !in settings.fieldHudStyle.hidden && snapshot().party.isNotEmpty()
        else -> field.available(tip)
    }

    private fun onField(snap: SnapshotView) = game.playingForward && runtime.settledOnField && snap.hasData && !snap.unsupported && !snap.inBattle
    private fun blocked() = screen.tipBlocked || FlyStyleAsk.showing

    private fun tryIt(tip: TipId) {
        if (!onField(snapshot()) || blocked() || !available(tip)) return
        when (tip) {
            TipId.ZOOM -> settings.cycleZoom()
            TipId.EXPAND -> settings.setGameAspect(GameAspect.WIDE)
            TipId.QUICK_MENU -> screen.triggerQuickMenu(true)
            TipId.HUD -> summaryRequest++
            else -> field.tryIt(tip)
        }
    }

    @Composable
    fun Clock() {
        val scheduler = FeatureTips.scheduler ?: return
        val offer = remember(this) { ::available }
        DisposableEffect(offer) {
            FeatureTips.available = offer
            onDispose { if (FeatureTips.available === offer) FeatureTips.available = { false } }
        }
        LaunchedEffect(this, scheduler) {
            val mark = TimeSource.Monotonic.markNow()
            var last = 0L
            while (true) {
                delay(500)
                val now = mark.elapsedNow().inWholeMilliseconds
                val snap = snapshot()
                val fieldNow = onField(snap)
                val calm = fieldNow && !blocked()
                if (!calm) scheduler.current?.let(scheduler::timedOut)
                if (game.playingForward) runtime.tipMoments.observe(snap, runtime.session.mapOpen, scheduler)
                scheduler.tick((now - last).coerceAtMost(1000), fieldNow, calm)
                last = now
            }
        }
    }

    @Composable
    fun Card() {
        BoxWithConstraints {
            val top = with(LocalDensity.current) {
                maxOf(screen.hudClear().first.toFloat(), screen.hudFrame()?.top ?: 0f).toDp()
            }
            FeatureTipHost(active = true, roomy = false, modifier = Modifier.padding(top = top + 8.dp),
                cardModifier = Modifier.width(maxWidth * if (maxWidth < 600.dp) 0.94f else 0.54f), maxWidth = maxWidth,
                keys = { tip -> when (tip) {
                    TipId.ZOOM -> listOfNotNull(shortcut(Hotkeys.Action.ZOOM)?.let { it to tr("ZOOM") })
                    TipId.QUICK_MENU -> listOfNotNull(shortcut(Hotkeys.Action.QUICK_MENU)?.let { it to tr("QUICK MENU") })
                    else -> emptyList()
                } }, onTry = ::tryIt)
        }
    }
}

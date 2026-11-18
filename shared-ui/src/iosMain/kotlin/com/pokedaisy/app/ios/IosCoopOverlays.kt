package com.pokedaisy.app.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pokedaisy.app.LockNotice
import com.pokedaisy.app.companion.coop.lock.LockWait
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CoopBattleOverlays
import com.pokedaisy.app.companion.ui.CoopLockProposal
import com.pokedaisy.app.companion.ui.CoopWaitOverlay

@Composable
internal fun IosCoopOverlays(coop: IosCoopGame, panels: IosGameScreen, closeGame: () -> Unit) {
    val lock = coop.storyLock
    val ui = lock?.ui?.collectAsState()?.value
    val notice = lock?.notice?.collectAsState()?.value
    var dismissed by remember(ui?.partnerProposed) { mutableStateOf(false) }
    val proposal = ui?.partnerProposed == true && !dismissed
    val captured = coop.overlay.takesKeys || proposal || ui?.waiting == LockWait.Partner
    DisposableEffect(captured) {
        panels.captureOverlay(IosGameScreen.Overlay.COOP, captured)
        onDispose { panels.captureOverlay(IosGameScreen.Overlay.COOP, false) }
    }

    CoopBattleOverlays(coop.overlay)
    ui?.waiting?.let { CoopWaitOverlay(it, coop.partnerName, closeGame) }
    if (proposal && lock != null) CoopLockProposal(coop.partnerName, lock) { dismissed = true }
    LaunchedEffect(notice) {
        val text = when (notice) {
            LockNotice.PARTNER_TURNED_OFF -> tr("Your partner turned off PLAY TOGETHER ONLY.")
            LockNotice.NO_STORY_HOLD -> tr("Story events can't wait for your partner in this game")
            null -> return@LaunchedEffect
        }
        coop.overlay.showNotice(text)
        lock?.clearNotice()
    }
}

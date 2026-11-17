package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Paired ownership notifications, including removal of an open overlay from a retained screen. */
@Composable
internal fun OverlayPresence(active: Boolean, changed: (Boolean) -> Unit) {
    val current by rememberUpdatedState(changed)
    DisposableEffect(Unit) { onDispose { current(false) } }
    SideEffect { current(active) }
}

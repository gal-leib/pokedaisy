package com.pokedaisy.app.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.pokedaisy.app.LinkSavePaths
import com.pokedaisy.app.companion.LinkSetupFlow
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.romCrc32
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.*
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.game.LinkGameSession
import com.pokedaisy.app.game.RomPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

private enum class LinkPhase { SETUP, STARTING, PLAYING, CLOSING }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
@Composable
internal fun IosLinkPlay(
    games: List<String>,
    initial: String?,
    label: (String) -> String,
    onClose: () -> Unit,
    onSaves: () -> Unit,
) {
    val setup = remember(initial) { LinkSetupFlow<String>().apply { initial?.let(::seed) } }
    val scope = rememberCoroutineScope()
    val m = rememberGbaTextMetrics()
    var phase by remember { mutableStateOf(LinkPhase.SETUP) }
    var notice by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<LinkGameSession?>(null) }
    var focused by remember { mutableIntStateOf(0) }
    var fast by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    fun start() {
        if (phase != LinkPhase.SETUP || setup.slots.size < LinkSetupFlow.MIN_GAMES) return
        val picked = setup.slots.toList()
        setup.clearPicker()
        phase = LinkPhase.STARTING
        scope.launch {
            val prepared = withContext(Dispatchers.Default) { runCatching { IosLinkSaves.prepare(picked) } }
            val saves = prepared.getOrElse {
                notice = it.message ?: tr("Can't read that file")
                phase = LinkPhase.SETUP
                return@launch
            }
            focused = 0
            fast = false
            val game = LinkGameSession(
                picked, saves.paths.map(Path::toString), iosPreferences.gameVolume / 100f,
                onOpen = { opened ->
                    if (opened) {
                        phase = LinkPhase.PLAYING
                        hint = true
                    } else {
                        session?.close()
                        phase = LinkPhase.CLOSING
                        notice = tr("The games couldn't be started. Check that each one is a GBA game that opens on its own.")
                    }
                },
                onFocus = { focused = it },
                onFault = { notice = tr("The link stopped. Leave and start it again; your saves are safe.") },
                finishSaves = saves::finish,
                onClosed = { finished ->
                    session = null
                    phase = LinkPhase.SETUP
                    onSaves()
                    if (!finished) notice = tr("Couldn't finish updating the games' resume files.")
                    else if (leaving) onClose()
                    leaving = false
                },
            )
            session = game
            game.start()
        }
    }

    fun leave() {
        confirming = false
        leaving = true
        phase = LinkPhase.CLOSING
        session?.close()
    }

    DisposableEffect(setup) {
        onDispose { setup.clearPicker(); session?.close() }
    }
    CompanionBackHandler {
        when (phase) {
            LinkPhase.SETUP -> onClose()
            LinkPhase.PLAYING -> confirming = !confirming
            LinkPhase.STARTING, LinkPhase.CLOSING -> Unit
        }
    }
    LaunchedEffect(confirming, notice, session) { session?.blockInput(confirming || notice != null) }
    if (hint) LaunchedEffect(Unit) { delay(6000); hint = false }
    when (phase) {
        LinkPhase.SETUP, LinkPhase.STARTING -> LinkSetupScreen(
            setup, m, phase == LinkPhase.STARTING, label, { it },
            onPick = { slot ->
                setup.begin(slot)?.let {
                    val cartridges = games.sortedBy { path -> label(path).lowercase() }
                    if (setup.ready(it, cartridges) && cartridges.isEmpty()) notice = tr("No GBA games in the library yet")
                }
            },
            onStart = ::start, onClose = onClose,
        )
        LinkPhase.PLAYING -> session?.let { game ->
            val picked = setup.slots
            val shown = IosTouchPreferences.visible && !confirming && notice == null
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    IosLinkGrid(picked.size, wide = maxWidth >= maxHeight) { slot ->
                        val isFocused = slot == focused
                        val description = tr("GAME {0}", slot + 1) + " " + label(picked[slot])
                        Box(Modifier.fillMaxSize().clickable { game.focus(slot) }.semantics {
                            contentDescription = description
                            selected = isFocused
                        }, contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(1.5f).then(
                                if (isFocused) Modifier.border(2.dp, AppColors.focusRing) else Modifier),
                            ) {
                                UIKitView(factory = { game.picture(slot) }, modifier = Modifier.fillMaxSize(),
                                    properties = UIKitInteropProperties(interactionMode = null))
                                Box(Modifier.fillMaxSize().background(
                                    if (isFocused) Color.Transparent else Color.Black.copy(alpha = 0.45f)))
                            }
                        }
                    }
                    LinkPlayChrome(m, hint, fast, onFast = { fast = !fast; game.fast = fast },
                        requestLeave = { confirming = true })
                    if (confirming) LinkLeaveConfirmation(m, onLeave = ::leave, onDismiss = { confirming = false })
                }
                if (shown) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val style = IosTouchPreferences.drawingStyle
                            .forButtons(setOf(TouchButton.TURBO_A, TouchButton.TURBO_B))
                        val touch = remember(game) {
                            TouchInput(onKeys = game::touch, onPress = {
                                val current = IosTouchPreferences.style
                                if (current.vibrate) IosTouchPreferences.feel(current.vibrationStrength)
                            })
                        }
                        DisposableEffect(game) { onDispose { game.touch(0, 0) } }
                        val compact = maxWidth < 480.dp
                        TouchControls(style, portrait = compact, touch,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 185.dp else 128.dp))
                    }
                }
            }
        }
        LinkPhase.CLOSING -> LinkPlayMessage(tr("Finishing the games' saves…"), m)
    }
    notice?.let {
        OptionConfirm(tk("LINK PLAY"), it, tk("OK"), m,
            onConfirm = { notice = null }, onDismiss = { notice = null }, cancelLabel = null)
    }
}

@Composable
private fun IosLinkGrid(count: Int, wide: Boolean, tile: @Composable (Int) -> Unit) {
    Layout(content = { repeat(count) { tile(it) } }, modifier = Modifier.fillMaxSize()) { tiles, max ->
        val (columns, rows) = linkGridShape(count, wide)
        val width = max.maxWidth / columns
        val height = max.maxHeight / rows
        val places = tiles.map { it.measure(Constraints.fixed(width, height)) }
        layout(max.maxWidth, max.maxHeight) {
            places.forEachIndexed { slot, picture ->
                picture.place((slot % columns) * width, (slot / columns) * height)
            }
        }
    }
}

/** Back up each actual battery destination and retire only resumes invalidated by a changed primary save. */
private class IosLinkSaves private constructor(
    val paths: List<Path>,
    private val originals: List<Original>,
) {
    private data class Original(val rom: String, val save: Path, val crc: Long?)

    fun finish() {
        originals.filter { stamp(it.save) != it.crc }.forEach {
            val state = iosStateFiles(it.rom.toPath())
            StateResume.startFresh(state)
        }
    }

    companion object {
        fun prepare(roms: List<String>): IosLinkSaves {
            check(recoverIosSaveTrades()) { iosSaveTradeRecoveryError() }
            val own = roms.map { RomPicker.savePathFor(it).toPath(normalize = true) }
            val destinations = LinkSavePaths.forGames(own)
            destinations.forEach { StateFiles.backupBattery(it) }
            val previous = roms.indices.filter { own[it] == destinations[it] }
                .map { Original(roms[it], own[it], stamp(own[it])) }
            return IosLinkSaves(destinations, previous)
        }

        private fun stamp(path: Path): Long? =
            FileSystem.SYSTEM.metadataOrNull(path)?.size?.takeIf { it > 0 }?.let { romCrc32(path) }
    }
}

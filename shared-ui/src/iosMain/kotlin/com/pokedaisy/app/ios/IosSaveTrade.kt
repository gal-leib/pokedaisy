package com.pokedaisy.app.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.pokedaisy.app.SaveEditFiles
import com.pokedaisy.app.SaveTradeJournal
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CompanionBackHandler
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.ui.SaveEditorText
import com.pokedaisy.app.companion.ui.TradeScreen
import com.pokedaisy.app.companion.ui.TradeState
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class OpenTrade(val a: IosGen3Save.Ready, val b: IosGen3Save.Ready, val state: TradeState)

@Composable
internal fun IosSaveTrade(
    paths: List<String>, fixedFrom: String?, title: (String) -> String, onClose: () -> Unit, onSaved: () -> Unit,
    initialPick: MonRef? = null,
) = SaveEditorText {
    val m = rememberGbaTextMetrics()
    val scope = rememberCoroutineScope()
    var candidates by remember(paths) { mutableStateOf<List<String>?>(null) }
    var from by remember(fixedFrom) { mutableStateOf(fixedFrom) }
    var to by remember(fixedFrom) { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<OpenTrade?>(null) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(paths) {
        candidates = withContext(Dispatchers.Default) { editableIosGames(paths) }
    }

    fun chooseAgain() {
        to = null
        open = null
    }

    val source = from
    val partner = to
    LaunchedEffect(source, partner, reload) {
        if (source == null || partner == null) return@LaunchedEffect
        val aName = title(source)
        val bName = title(partner)
        val (a, b) = withContext(Dispatchers.Default) {
            readIosGen3Save(source, aName) to readIosGen3Save(partner, bName)
        }
        if (a is IosGen3Save.Ready && b is IosGen3Save.Ready && a.path != b.path) {
            val state = TradeState(TradeSide(aName, Gen3SaveEditor(a.source.save), a.gameCode),
                TradeSide(bName, Gen3SaveEditor(b.source.save), b.gameCode))
            if (reload == 0) initialPick?.let { state.focus(it, firstSide = true) }
            open = OpenTrade(a, b, state)
        } else {
            notice = (a as? IosGen3Save.Failed)?.message ?: (b as? IosGen3Save.Failed)?.message
                ?: tr("Both games use the same save file")
            chooseAgain()
        }
    }

    val trade = open
    if (trade != null) {
        CompanionBackHandler { if (!saving) trade.state.back(onClose) }
        TradeScreen(trade.state, m, onBack = onClose, saving = saving, iconsA = trade.a.icons, iconsB = trade.b.icons, onSave = {
            if (!saving) {
                val edits = runCatching {
                    fun side(source: IosGen3Save.Ready, updated: ByteArray) =
                        SaveTradeJournal.Side(SaveEditFiles.Edit(source.path, source.source.bytes, updated), source.stateKey)
                    side(trade.a, trade.state.a.editor.build()) to side(trade.b, trade.state.b.editor.build())
                }.getOrNull()
                if (edits == null) {
                    notice = tr("Can't write the save")
                } else {
                    saving = true
                    scope.launch {
                        val result = withContext(NonCancellable + Dispatchers.Default) {
                            runCatching { commitIosSaveTrade(edits.first, edits.second) }
                                .getOrElse { SaveEditFiles.Result.Failed(tr("Can't write the save")) }
                        }
                        saving = false
                        when (result) {
                            is SaveEditFiles.Result.Written -> {
                                notice = tr("Saved. The previous save is in {0}", result.backup.parent?.name ?: "pokedaisy-backups")
                                open = null
                                reload++
                                onSaved()
                            }
                            is SaveEditFiles.Result.Failed -> notice = result.reason
                        }
                    }
                }
            }
        })
    } else {
        val games = candidates
        val choices = if (source == null && games.orEmpty().size < 2) emptyList() else games.orEmpty().filter { it != source }
        if (games == null || partner != null || choices.isEmpty()) {
            val message = if (games == null || partner != null) tr("LOADING…")
                else tr("No other game here has a save the save editor can read")
            val close = if (partner != null) ({ chooseAgain() }) else onClose
            OptionConfirm(tr("TRADE SAVES"), message, tr("CLOSE"), m,
                onConfirm = close, onDismiss = close, cancelLabel = null)
        } else {
            OptionSelector(if (source == null) tr("TRADE FROM") else tr("TRADE WITH"), choices, choices.first(), title, m,
                onPick = { if (source == null) from = it else to = it },
                onDismiss = { if (source == null || fixedFrom != null) onClose() else from = null })
        }
    }
    IosSaveWriteStatus(tr("TRADE SAVES"), saving, notice, clearNotice = { notice = null })
}

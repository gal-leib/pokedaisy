package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.SaveEditFiles
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.Gen3SaveFile
import com.pokedaisy.app.companion.data.save.MissingDex
import com.pokedaisy.app.companion.data.save.MonRef
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CompanionBackHandler
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.MissingView
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.PcBoxesScreen
import com.pokedaisy.app.companion.ui.PcBoxesState
import com.pokedaisy.app.companion.ui.SaveEditorText
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.game.RomPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

@Composable
internal fun IosSaveEditorPicker(paths: List<String>, label: (String) -> String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val m = rememberGbaTextMetrics()
    var choices by remember(paths) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(paths) {
        choices = withContext(Dispatchers.Default) {
            editableIosGames(paths)
        }
    }
    val games = choices
    if (games.isNullOrEmpty()) {
        val message = if (games == null) tr("LOADING…") else tr("No game has a save the editor can read.")
        OptionConfirm(tr("SAVE EDITOR"), message, tr("CLOSE"), m, onConfirm = onClose, onDismiss = onClose, cancelLabel = null)
    } else {
        OptionSelector(tr("SAVE EDITOR"), games, games.first(), label, m, onPick = onPick, onDismiss = onClose)
    }
}

private class OpenedSave(val source: IosGen3Save.Ready, val state: PcBoxesState)

@Composable
internal fun IosSaveEditor(
    path: String, title: String, paths: List<String>, label: (String) -> String, onClose: () -> Unit, onSaved: () -> Unit,
    onTrade: (IosTradeRequest) -> Unit, initialPick: MonRef? = null,
) = SaveEditorText {
    val m = rememberGbaTextMetrics()
    var generation by remember(path) { mutableIntStateOf(0) }
    var opened by remember(path) { mutableStateOf<OpenedSave?>(null) }
    var openError by remember(path) { mutableStateOf<String?>(null) }
    var notice by remember(path) { mutableStateOf<String?>(null) }
    var saving by remember(path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, generation) {
        when (val result = withContext(Dispatchers.Default) { readIosGen3Save(path, title) }) {
            is IosGen3Save.Ready -> {
                val state = PcBoxesState(Gen3SaveEditor(result.source.save))
                if (generation == 0) initialPick?.let(state::focus)
                opened = OpenedSave(result, state)
            }
            is IosGen3Save.Failed -> {
                opened = null
                openError = result.message
            }
        }
    }

    val edit = opened
    if (edit == null) {
        Box(Modifier.fillMaxSize()) {
            val message = openError ?: tr("LOADING…")
            OptionConfirm(tr("SAVE EDITOR"), message, tr("CLOSE"), m, onConfirm = onClose, onDismiss = onClose, cancelLabel = null)
        }
    } else {
        val state = edit.state
        var dexSources by remember(state) { mutableStateOf<List<String>>(emptyList()) }
        fun loadMissing() {
            if (saving) return
            val target = if (state.changed) {
                val bytes = runCatching { state.editor.build() }.getOrNull() ?: return
                val parsed = Gen3SaveFile.parse(bytes, edit.source.source.save.format) as? Gen3SaveFile.Parsed.Ok ?: return
                TradeSide(title, Gen3SaveEditor(parsed.save), edit.source.gameCode)
            } else edit.source.tradeSide(title)
            val loading = MissingView(null, emptyList())
            state.missing = loading
            val gamePaths = paths.filter { it != path }
            val titles = gamePaths.associateWith(label)
            scope.launch {
                val (sources, sides, missing) = withContext(Dispatchers.Default) {
                    val read = editableIosGames(gamePaths).mapNotNull { candidate ->
                        val save = readIosGen3Save(candidate, titles.getValue(candidate)) as? IosGen3Save.Ready
                        if (save == null || save.path == edit.source.path) null else candidate to save
                    }
                    val sides = read.map { (candidate, save) -> save.tradeSide(titles.getValue(candidate)) }
                    Triple(read.map { it.first }, sides, MissingDex.find(target, sides))
                }
                if (state.missing === loading) {
                    dexSources = sources
                    state.missing = MissingView(missing, sides)
                }
            }
        }
        fun saveChanges() {
            if (saving) return
            val bytes = runCatching { state.editor.build() }.getOrNull()
            if (bytes == null) {
                notice = tr("Can't write the save")
                return
            }
            saving = true
            scope.launch {
                val result = withContext(NonCancellable + Dispatchers.Default) {
                    runCatching {
                        val states = iosStateFiles(edit.source.stateKey)
                        commitIosSaveEdit(edit.source.path, states, edit.source.source.bytes, bytes)
                    }.getOrElse { SaveEditFiles.Result.Failed(tr("Can't write the save")) }
                }
                saving = false
                when (result) {
                    is SaveEditFiles.Result.Written -> {
                        opened = null
                        generation++
                        notice = tr("Saved. The previous save is in {0}", result.backup.parent?.name ?: "pokedaisy-backups")
                        onSaved()
                    }
                    is SaveEditFiles.Result.Failed -> notice = result.reason
                }
            }
        }
        CompanionBackHandler { if (!saving) state.back(onClose) }
        PcBoxesScreen(state, title, m, onBack = onClose, saving = saving, icons = edit.source.icons,
            onTrade = { onTrade(IosTradeRequest(first = path)) },
            onMissing = ::loadMissing,
            onGet = { selected ->
                val source = dexSources.getOrNull(selected.source)
                if (state.changed) {
                    state.missing = null
                    state.notice = tr("Save your changes first, then bring it over")
                } else if (source != null) {
                    val trade = if (selected.way == MissingDex.Way.TRADE_BACK)
                        IosTradeRequest(first = path, second = source, firstPick = selected.at)
                    else IosTradeRequest(first = path, second = source, secondPick = selected.at)
                    onTrade(trade)
                }
            },
            onSave = ::saveChanges,
        )
    }
    IosSaveWriteStatus(tr("SAVE EDITOR"), saving, notice, clearNotice = { notice = null })
}

@Composable
internal fun IosSaveWriteStatus(title: String, saving: Boolean, notice: String?, clearNotice: () -> Unit) {
    val m = rememberGbaTextMetrics()
    if (saving) {
        OptionOverlay({}, Modifier.widthIn(max = 480.dp)) {
            Column {
                OptionTitleWindow(title, m)
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    GbaText(tr("SAVING…"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(16.dp))
                }
            }
        }
    } else if (notice != null) {
        OptionConfirm(title, notice, tr("OK"), m, onConfirm = clearNotice, onDismiss = clearNotice, cancelLabel = null)
    }
}

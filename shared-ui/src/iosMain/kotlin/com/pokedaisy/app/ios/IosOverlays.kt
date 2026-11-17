package com.pokedaisy.app.ios

import androidx.compose.runtime.*
import com.pokedaisy.app.companion.OverlayImport
import com.pokedaisy.app.overlay.*
import kotlinx.coroutines.*
import platform.UIKit.UIViewController

/** Native Files scheduling and publication; import rules, notices, storage and rendering are shared. */
internal object IosOverlays {
    val store by lazy { OverlayStore(appFilesDir()) }
    var revision by mutableIntStateOf(0)
        private set
    var choices by mutableStateOf(builtInOverlayChoices().map { (key, label) -> OverlayChoice.of(key) to label })
        private set
    private var pending = emptyList<OverlayStore.Picked>()
    init { refresh() }

    private fun refresh() = IosFileImports.scope.launch {
        choices = withContext(Dispatchers.IO) { overlayChoices(store) }
        revision++
    }

    fun cancel() { pending = emptyList() }

    fun pick(from: UIViewController, before: () -> Unit, done: (OverlayImport.Completed?) -> Unit) {
        val earlier = pending
        pending = emptyList()
        IosFileImports.pick(from, before, read = { path ->
            val picked = runCatching { OverlayStore.readPicked(path) }.getOrNull()
            val files = earlier + listOfNotNull(picked)
            val result = if (picked == null) OverlayStore.Result.Failed(OverlayStore.Reason.TOO_BIG) else store.import(files)
            Triple(result, files, overlayChoices(store))
        }, done = { result ->
            var completed: OverlayImport.Completed? = null
            try {
                if (result != null) {
                    completed = OverlayImport.complete(result.first, iosPreferences.overlay, result.third)
                    iosPreferences.overlay = completed.selection
                    choices = result.third
                    pending = if (completed.needsImage != null) result.second else emptyList()
                    revision++
                }
            } finally { done(completed) }

        })
    }

    fun delete(id: String) = IosFileImports.scope.launch {
        val choicesAfter = withContext(Dispatchers.IO) { store.delete(id); overlayChoices(store) }
        if (iosPreferences.overlay == OverlayChoice.Imported(id).key) iosPreferences.overlay = ""
        choices = choicesAfter
        revision++
    }

    fun deleteAll() = IosFileImports.scope.launch {
        val choicesAfter = withContext(Dispatchers.IO) { store.deleteAll(); overlayChoices(store) }
        if (OverlayChoice.of(iosPreferences.overlay) is OverlayChoice.Imported) iosPreferences.overlay = ""
        choices = choicesAfter
        revision++
    }
}

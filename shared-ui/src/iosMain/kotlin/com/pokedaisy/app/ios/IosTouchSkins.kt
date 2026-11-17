package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.DeltaSkinStore
import com.pokedaisy.app.companion.SkinSystem
import com.pokedaisy.app.companion.TouchSkinImport
import platform.UIKit.UIViewController

/** Only Files presentation and off-thread import scheduling; Delta parsing, storage and selection policy are shared. */
internal object IosTouchSkins {
    val store by lazy { DeltaSkinStore(appFilesDir() / "skins") }
    var revision by mutableIntStateOf(0)
        private set
    fun selected(gameBoy: Boolean, shown: Boolean = true) = if (!shown) null else
        IosTouchPreferences.style.skinFor(if (gameBoy) SkinSystem.GB else SkinSystem.GBA)?.let(store::load)

    fun pick(from: UIViewController, before: () -> Unit, done: (Pair<String, String>?) -> Unit) {
        IosFileImports.pick(from, before, read = { path ->
            runCatching { store.import(path) }.getOrElse {
                DeltaSkinStore.ImportResult(emptyList(), listOf("it couldn't be opened"))
            }
        }, done = { result ->
            var notice: Pair<String, String>? = null
            try {
                val completed = result?.let { TouchSkinImport.complete(it, IosTouchPreferences.style) }
                if (completed != null) {
                    IosTouchPreferences.style = completed.style
                    revision++
                    notice = completed.notice
                }
            } finally { done(notice) }
        })
    }

    fun delete(id: String) {
        if (!store.delete(id)) return
        val style = IosTouchPreferences.style
        IosTouchPreferences.style = style.withoutSkin(id)
        revision++
    }
}

package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.DeltaSkinStore
import com.pokedaisy.app.companion.SkinSystem
import com.pokedaisy.app.companion.TouchSkinImport
import com.pokedaisy.app.game.RomPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import platform.UIKit.UIViewController

/** Only Files presentation and off-thread import scheduling; Delta parsing, storage and selection policy are shared. */
internal object IosTouchSkins {
    val store by lazy { DeltaSkinStore(appFilesDir() / "skins") }
    var revision by mutableIntStateOf(0)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var picking = false

    fun selected(gameBoy: Boolean, shown: Boolean = true) = if (!shown) null else
        IosTouchPreferences.style.skinFor(if (gameBoy) SkinSystem.GB else SkinSystem.GBA)?.let(store::load)

    fun pick(from: UIViewController, before: () -> Unit, done: (Pair<String, String>?) -> Unit) {
        if (picking) return
        picking = true
        try {
            before()
            val presented = RomPicker.pickData(from) { path ->
                if (path == null) {
                    picking = false
                    done(null)
                } else scope.launch {
                    var notice: Pair<String, String>? = null
                    try {
                        val result = withContext(Dispatchers.Default) {
                            runCatching { store.import(path.toPath()) }.getOrElse {
                                DeltaSkinStore.ImportResult(emptyList(), listOf("it couldn't be opened"))
                            }
                        }
                        // Read the latest choices after I/O, retaining any other system's selection.
                        val completed = TouchSkinImport.complete(result, IosTouchPreferences.style)
                        IosTouchPreferences.style = completed.style
                        revision++
                        notice = completed.notice
                    } finally { picking = false; done(notice) }
                }
            }
            if (!presented) { picking = false; done(null) }
        } catch (error: Throwable) {
            picking = false
            done(null)
        }
    }

    fun delete(id: String) {
        if (!store.delete(id)) return
        val style = IosTouchPreferences.style
        IosTouchPreferences.style = style.withoutSkin(id)
        revision++
    }
}

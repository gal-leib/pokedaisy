package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.core.PkApiCore
import okio.Path.Companion.toPath
import platform.darwin.*

/** Platform scheduling only: shared finders and patch transactions do the game work. */
internal class IosGameOptions(private val path: String, private val allowed: () -> Boolean) {
    private var closed = false
    private var dirty = false
    private var applied = false
    private var instant = false
    private var tms = false
    private var text by mutableStateOf<InstantText.Patch?>(null)
    private var sites by mutableStateOf<List<ReusableTms.Site>>(emptyList())
    val instantTextSupported get() = allowed() && text != null
    val reusableTmsSupported get() = allowed() && sites.isNotEmpty()

    fun start() {
        if (!allowed()) return
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val foundText = InstantText.find(path.toPath())
            val foundSites = runCatching { ReusableTms.find(path.toPath()) }.getOrDefault(emptyList())
            dispatch_async(dispatch_get_main_queue()) {
                if (!closed && allowed()) {
                    text = foundText; sites = foundSites; dirty = true
                }
            }
        }
    }
    fun settingsChanged(s: IosCompanionSettings) {
        val nextTms = s.reusableTms && !s.reusableTmsBuiltIn
        if (instant != s.instantText || tms != nextTms) {
            instant = s.instantText; tms = nextTms; dirty = true
        }
    }
    fun tick() {
        if (closed) return
        if (!allowed()) {
            if (applied) PkApiCore.unpatch()
            applied = false
            return
        }
        if (!dirty) return
        dirty = false
        val result = GameOptionPatches.apply(PkApiCore, if (tms) sites else emptyList(), text.takeIf { instant })
        applied = result.tms || result.text
        Logs.i("game-option patches: text=${result.text}, reusableTms=${result.tms}")
    }
    fun close() {
        closed = true
        if (applied) PkApiCore.unpatch()
    }
}

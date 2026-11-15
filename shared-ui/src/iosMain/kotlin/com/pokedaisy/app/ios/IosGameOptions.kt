package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.core.PkApiCore
import okio.Path.Companion.toPath
import platform.darwin.*

/** Platform scheduling only: shared finders and patch transactions do the game work. */
internal class IosGameOptions(private val path: String, private val code: String?, private val allowed: () -> Boolean) {
    private var closed = false
    private var dirty = false
    private var applied = false
    private var instant = false
    private var tms = false
    private var autoRun = false
    private var runIndoors = false
    private var wildKeep = 100
    private var wildFlag by mutableStateOf(0L)
    val wildFlagFound get() = allowed() && wildFlag != 0L
    private var text by mutableStateOf<InstantText.Patch?>(null)
    private var sites by mutableStateOf<List<ReusableTms.Site>>(emptyList())
    val instantTextSupported get() = allowed() && text != null
    val reusableTmsSupported get() = allowed() && sites.isNotEmpty()

    fun start() {
        if (!allowed()) return
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val foundText = InstantText.find(path.toPath())
            val foundSites = runCatching { ReusableTms.find(path.toPath()) }.getOrDefault(emptyList())
            val foundWild = runCatching { WildEncounters.find(path.toPath()) }.getOrDefault(0L)
            dispatch_async(dispatch_get_main_queue()) {
                if (!closed && allowed()) {
                    text = foundText; sites = foundSites; dirty = true
                    wildFlag = if (PkApiCore.configureWild(foundWild, wildKeep == 0)) foundWild else 0L
                }
            }
        }
    }
    fun settingsChanged(s: IosCompanionSettings) {
        autoRun = s.autoRun
        runIndoors = s.runIndoors
        wildKeep = s.wildEncounters
        val nextTms = s.reusableTms && !s.reusableTmsBuiltIn
        if (instant != s.instantText || tms != nextTms) {
            instant = s.instantText; tms = nextTms; dirty = true
        }
    }
    fun fieldState(): Int = if (!closed && allowed()) PkApiCore.stepField(
        runIndoors && GameField.supportsRunIndoors(code.orEmpty()), if (wildFlagFound) wildKeep else 100) else 0

    /** Applied to manual keys before the shared battle driver adds its own scripted input. */
    fun keys(held: Int, field: Int, onField: Boolean): Int = if (!closed && autoRun && allowed())
        AutoRunPolicy.apply(held, field, onField, activeGame in GAMES_WITH_OWN_AUTO_RUN) else held

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

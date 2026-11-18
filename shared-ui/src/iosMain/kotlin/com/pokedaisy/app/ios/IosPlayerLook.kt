package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.GamePlayerLook
import com.pokedaisy.app.companion.data.AppearanceAssets
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.PlayerLookAssets
import com.pokedaisy.app.companion.data.RETAIL_LOOK_TITLES
import com.pokedaisy.app.companion.data.RETAIL_PORT_TITLES
import com.pokedaisy.app.core.PkApiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path

/** The iOS settings and game-view hooks for the shared appearance catalog and renderer. */
internal class IosPlayerLook(private val prefs: Prefs, private val files: Path) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val renderer = GamePlayerLook(PkApiCore)
    private var catalog by mutableStateOf<PlayerLookAssets.Catalog?>(null)
    private var key: String? = null
    private var female by mutableStateOf<Boolean?>(null)
    private var pick by mutableStateOf<LookChar?>(null)
    val supported get() = catalog != null
    val rivalSupported get() = catalog?.engine?.let(PlayerLook::hasRival) == true
    val choices get() = catalog?.availableHeroes(female).orEmpty().map { it.name }
    val selected get() = pick?.name
    val own get() = catalog?.let { current -> female?.let { LookChar.of(current.engine, it).name } }

    fun load(rom: Path, code: String, romKey: String, library: () -> List<Path>) {
        val engine = PlayerLook.engineFor(code) ?: return
        if (key != null) return
        key = romKey
        scope.launch {
            try {
                catalog = withContext(Dispatchers.Default) {
                    val worker = currentCoroutineContext()
                    PlayerLookAssets(files) { it in RETAIL_LOOK_TITLES || it in RETAIL_PORT_TITLES }.prepare(
                        AppearanceAssets.file(rom), engine, code,
                        { library().mapNotNull { runCatching { AppearanceAssets.file(it) }.getOrNull() } },
                        { worker.isActive },
                    )
                }
                apply()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: scan failed", error)
            }
        }
    }

    fun observe(gender: Int) {
        if (gender !in 0..1 || female == (gender == 1)) return
        female = gender == 1
        apply()
    }

    fun select(look: String?) {
        val currentKey = key ?: return
        if (look != null && look !in choices) return
        prefs.setPlayerLookFor(currentKey, look)
        apply()
    }

    fun apply() {
        val current = catalog ?: return
        val currentKey = key ?: return
        pick = LookChar.byName(prefs.playerLookFor(currentKey))?.takeIf { it.name in choices }
        val rival = rivalSupported && prefs.rivalLook
        val patch = female?.let { gender ->
            current.heroes[Triple(gender, pick, rival)] ?: current.heroes[Triple(gender, pick, false)]
        }
        PlayerLook.worn.value = pick.takeIf { patch?.blocks?.isNotEmpty() == true }
        renderer.select(patch)
    }

    fun close() {
        scope.cancel()
        catalog = null
        renderer.select(null)
        PlayerLook.worn.value = null
    }
}

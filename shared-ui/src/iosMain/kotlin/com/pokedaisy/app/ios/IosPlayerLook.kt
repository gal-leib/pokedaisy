package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.GamePlayerLook
import com.pokedaisy.app.companion.GameRide
import com.pokedaisy.app.companion.data.AppearanceAssets
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.MonArt
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.PokemonCostumes
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.PlayerLookAssets
import com.pokedaisy.app.companion.data.TextLook
import com.pokedaisy.app.companion.data.RideOn
import com.pokedaisy.app.companion.data.RideVehicle
import com.pokedaisy.app.companion.data.RETAIL_LOOK_TITLES
import com.pokedaisy.app.companion.data.RETAIL_PORT_TITLES
import com.pokedaisy.app.core.PkApiCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val ride = GameRide(PkApiCore)
    val vehicle get() = RideVehicle.of(prefs.rideVehicleId)
    private var catalog by mutableStateOf<PlayerLookAssets.Catalog?>(null)
    private var key: String? = null
    private var female by mutableStateOf<Boolean?>(null)
    private var pick by mutableStateOf<LookChar?>(null)
    val supported get() = catalog != null
    val isFemale get() = female == true
    val rivalSupported get() = catalog?.engine?.let(PlayerLook::hasRival) == true
    private var party: Pair<PokedexTables, List<Int>>? = null
    private var partyJob: Job? = null
    private var costumeChoices by mutableStateOf<Map<Int, String>>(emptyMap())
    private data class Costume(val hero: LookChar, val national: Int, val party: Set<Int>)
    private var costume: Costume? = null
    private var costumeJob: Job? = null
    private var costumePatch: PlayerLook.Patch? = null
    private var pickedCostume by mutableStateOf<Int?>(null)

    val choices get() = catalog?.availableHeroes(female).orEmpty().map { it.name } +
        costumeChoices.keys.map { "MON:$it" }
    val selected get() = pickedCostume?.let { "MON:$it" } ?: pick?.name
    fun costumeLabel(name: String): String? = MonArt.monOf(name)?.let { costumeChoices[it] ?: "#$it" }
    val textChoices get() = catalog?.text?.keys.orEmpty().map { it.name }
    private var pickedText by mutableStateOf<String?>(null)
    val textSelected get() = pickedText
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
                refreshParty()
                apply()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: scan failed", error)
            }
        }
    }

    fun observe(snapshot: SnapshotView) {
        if (!snapshot.connected) return
        val nextParty = snapshot.pokedex?.tables?.let { tables -> tables to snapshot.party.map { it.species } }
        val gender = snapshot.playerGender
        if (gender in 0..1 && female != (gender == 1)) {
            female = gender == 1
            apply()
        }
        if (nextParty != party) {
            party = nextParty
            refreshParty()
        }
    }

    private fun refreshParty() {
        partyJob?.cancel()
        if (catalog == null) return
        val input = party
        if (input == null) {
            if (costumeChoices.isNotEmpty()) {
                costumeChoices = emptyMap()
                apply()
            }
            return
        }
        partyJob = scope.launch {
            try {
                val offered = withContext(Dispatchers.Default) {
                    val available = MonArt.available(files)
                    PokemonCostumes.party(input.first, input.second).filterKeys { it in available }
                }
                if (party == input && costumeChoices != offered) {
                    costumeChoices = offered
                    apply()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: costume inventory failed", error)
            }
        }
    }

    fun select(look: String?) {
        val currentKey = key ?: return
        if (look != null && look !in choices) return
        prefs.setPlayerLookFor(currentKey, look)
        apply()
    }

    fun selectText(style: String?) {
        val currentKey = key ?: return
        if (style != null && style !in textChoices) return
        prefs.setTextLookFor(currentKey, style)
        apply()
    }

    fun selectRide(vehicle: RideVehicle) {
        if (!supported) return
        prefs.rideVehicleId = vehicle.id
        apply()
    }

    fun apply() {
        val current = catalog ?: return
        val currentKey = key ?: return
        val saved = prefs.playerLookFor(currentKey)
        val own = female?.let { LookChar.of(current.engine, it) }
        val national = MonArt.monOf(saved)?.takeIf { it in costumeChoices }
        val request = if (own != null && national != null) Costume(own, national, costumeChoices.keys.toSet()) else null
        if (request != costume) prepareCostume(current, request)

        pickedCostume = national.takeIf { costumePatch != null }
        pick = LookChar.byName(saved)?.takeIf { it.name in choices }
        val rival = rivalSupported && prefs.rivalLook
        val hero = female?.let { gender ->
            current.heroes[Triple(gender, pick, rival)] ?: current.heroes[Triple(gender, pick, false)]
        }
        PlayerLook.worn.value = pick.takeIf { hero?.blocks?.isNotEmpty() == true }
        val style = current.text.keys.firstOrNull { it.name == prefs.textLookFor(currentKey) }
        pickedText = style?.name
        // As on Android, a costume has its own mounted frames.
        val appearance = costumePatch ?: own?.let {
            RideOn.withAppearance(current.scan, it, hero, vehicle)
        } ?: hero
        renderer.select(TextLook.withAppearance(appearance, style?.let { current.text[it] }.orEmpty()))
    }

    private fun prepareCostume(current: PlayerLookAssets.Catalog, request: Costume?) {
        costumeJob?.cancel()
        costume = request
        costumePatch = null
        if (request == null) return
        costumeJob = scope.launch {
            try {
                val patch = withContext(Dispatchers.Default) {
                    PokemonCostumes.build(files, current.scan, request.hero, request.national, request.party, current.known)
                }
                if (catalog === current && costume == request) {
                    costumePatch = patch
                    apply()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: costume patch failed", error)
            }
        }
    }

    fun close() {
        scope.cancel()
        catalog = null
        costumeChoices = emptyMap()
        costumePatch = null
        renderer.select(null)
        PlayerLook.worn.value = null
    }
}

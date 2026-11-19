package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.GamePlayerLook
import com.pokedaisy.app.companion.GameRide
import com.pokedaisy.app.companion.data.AppearanceAssets
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.MonArt
import com.pokedaisy.app.companion.data.PlayerAppearance
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.PlayerLookAssets
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.PokemonCostumes
import com.pokedaisy.app.companion.data.RETAIL_LOOK_TITLES
import com.pokedaisy.app.companion.data.RETAIL_PORT_TITLES
import com.pokedaisy.app.companion.data.RideVehicle
import com.pokedaisy.app.companion.data.SnapshotView
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
    private var appearance by mutableStateOf<PlayerAppearance?>(null)
    private var key: String? = null
    private var female by mutableStateOf<Boolean?>(null)
    private var selection by mutableStateOf<PlayerAppearance.Selection?>(null)
    val supported get() = appearance != null
    val isFemale get() = female == true
    val rivalSupported get() = appearance?.rivalSupported == true
    private var party: Pair<PokedexTables, List<Int>>? = null
    private var partyJob: Job? = null
    private var costumeChoices by mutableStateOf<Map<Int, String>>(emptyMap())
    private var costumeJob: Job? = null

    val choices get() = selection?.heroes.orEmpty().map { it.name } + costumeChoices.keys.map { "MON:$it" }
    val selected get() = selection?.let { it.pickedCostume?.let { national -> "MON:$national" } ?: it.pickedHero?.name }
    fun costumeLabel(name: String): String? = MonArt.monOf(name)?.let { costumeChoices[it] ?: "#$it" }
    val textChoices get() = appearance?.textStyles.orEmpty().map { it.name }
    val textSelected get() = selection?.textStyle?.name
    val own get() = selection?.own?.name

    fun load(rom: Path, code: String, romKey: String, library: () -> List<Path>) {
        val engine = PlayerLook.engineFor(code) ?: return
        if (key != null) return
        key = romKey
        scope.launch {
            try {
                val catalog = withContext(Dispatchers.Default) {
                    val worker = currentCoroutineContext()
                    PlayerLookAssets(files) { it in RETAIL_LOOK_TITLES || it in RETAIL_PORT_TITLES }.prepare(
                        AppearanceAssets.file(rom), engine, code,
                        { library().mapNotNull { runCatching { AppearanceAssets.file(it) }.getOrNull() } },
                        { worker.isActive },
                    )
                }
                appearance = catalog?.let(::PlayerAppearance)
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
        if (appearance == null) return
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
        val current = appearance ?: return
        val currentKey = key ?: return
        val next = current.select(
            female, prefs.playerLookFor(currentKey), costumeChoices.keys, prefs.rivalLook,
            vehicle, prefs.textLookFor(currentKey),
        )
        selection = next
        PlayerLook.worn.value = next.wornHero
        renderer.select(next.patch)
        if (next.pickedCostume == null) costumeJob?.cancel()
        next.costumeToPrepare?.let { prepareCostume(current, it) }
    }

    private fun prepareCostume(current: PlayerAppearance, request: PlayerAppearance.CostumeRequest) {
        costumeJob?.cancel()
        costumeJob = scope.launch {
            val patch = try {
                withContext(Dispatchers.Default) { current.prepareCostume(files, request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: costume patch failed", error)
                null
            }
            if (appearance === current && current.completeCostume(request, patch)) apply()
        }
    }

    fun close() {
        scope.cancel()
        appearance = null
        costumeChoices = emptyMap()
        selection = null
        renderer.select(null)
        PlayerLook.worn.value = null
    }
}

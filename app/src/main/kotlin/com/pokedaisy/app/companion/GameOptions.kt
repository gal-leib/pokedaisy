package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.*
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import okio.Path

/** Shared option discovery and desired state. [tick], [coreStarted] and [close] run between frames;
 * the host retains frame scheduling and co-op guards. UI setters never patch the core. */
class GameOptions(
    private val core: RomOptionPatcher,
    private val allowed: () -> Boolean,
    private val publish: CoroutineDispatcher = Dispatchers.Main,
) {
    data class Discovery(val wildFlag: Long = 0, val sites: List<ReusableTms.Site> = emptyList(),
                         val text: InstantText.Patch? = null)
    private data class Patches(val sites: List<ReusableTms.Site> = emptyList(), val tms: Boolean = false,
                               val text: InstantText.Patch? = null, val instant: Boolean = false)
    private data class Inputs(val autoRun: Boolean = false, val indoors: Boolean = false, val wild: Int = 100)
    private val desired = atomic(Patches())
    private val inputs = atomic(Inputs())
    private val found = atomic(Discovery())
    private val wildAvailable = atomic(false)
    private val closed = atomic(false)
    private val generation = atomic(0)
    private var revision by mutableIntStateOf(0)
    private var applied: Patches? = null
    private var appliedAny = false
    private var scan: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val discovery: Discovery get() { revision; return found.value }
    val wildFlagFound: Boolean get() { revision; return allowed() && wildAvailable.value }
    val instantTextSupported get() = allowed() && discovery.text != null
    val reusableTmsSupported get() = allowed() && discovery.sites.isNotEmpty()
    val dirty get() = !closed.value && if (allowed()) desired.value != applied else appliedAny

    var autoRun: Boolean
        get() = inputs.value.autoRun
        set(v) { inputs.update { it.copy(autoRun = v) } }
    var runIndoors: Boolean
        get() = inputs.value.indoors
        set(v) { inputs.update { it.copy(indoors = v) } }
    var wildEncounters: Int
        get() = inputs.value.wild
        set(v) { inputs.update { it.copy(wild = if (v == 0) 0 else 100) } }

    fun setReusableTms(sites: List<ReusableTms.Site>, on: Boolean) {
        desired.update { it.copy(sites = sites.toList(), tms = on) }
    }
    fun setInstantText(patch: InstantText.Patch?, on: Boolean) {
        desired.update { it.copy(text = patch, instant = on) }
    }
    fun settingsChanged(s: CompanionSettings) {
        autoRun = s.autoRun; runIndoors = s.runIndoors; wildEncounters = s.wildEncounters
        val metadata = found.value
        setReusableTms(metadata.sites, s.reusableTms && !s.reusableTmsBuiltIn)
        setInstantText(metadata.text, s.instantText)
    }

    /** Discovery reads files off-thread; publication, availability checks and host notifications use its UI dispatcher. */
    fun start(path: Path, configureWild: (Long, Boolean) -> Boolean, ready: (Discovery) -> Unit = {}) {
        cancelDiscovery()
        if (closed.value || !allowed()) return
        val ticket = generation.value
        scan = scope.launch {
            val result = Discovery(
                runCatching { WildEncounters.find(path) }.getOrDefault(0L),
                runCatching { ReusableTms.find(path) }.getOrDefault(emptyList()),
                runCatching { InstantText.find(path) }.getOrNull())
            withContext(publish) {
                if (closed.value || ticket != generation.value || !allowed()) return@withContext
                found.value = result
                desired.update { it.copy(sites = result.sites, text = result.text) }
                wildAvailable.value = configureWild(result.wildFlag, wildEncounters == 0)
                revision++
                ready(result)
            }
        }
    }

    /** A new core has no patches, even if its desired settings equal the previous run. */
    fun coreStarted() { applied = null; appliedAny = false }

    fun tick(): GameOptionPatches.Result? {
        if (closed.value) return null
        if (!allowed()) {
            if (!appliedAny) return null
            core.unpatch(); applied = null; appliedAny = false
            return GameOptionPatches.Result(false, false)
        }
        val state = desired.value
        if (state == applied) return null
        val result = GameOptionPatches.apply(core, state.sites.takeIf { state.tms }.orEmpty(), state.text.takeIf { state.instant })
        applied = state; appliedAny = result.tms || result.text
        Logs.i("game-option patches: text=${result.text}, reusableTms=${result.tms}")
        return result
    }

    fun fieldState(step: (Boolean, Int) -> Int, indoorsSupported: Boolean = true,
                   wildKnown: Boolean = wildAvailable.value): Int {
        if (closed.value || !allowed()) return 0
        val state = inputs.value
        return step(state.indoors && indoorsSupported, if (wildKnown) state.wild else 100)
    }
    fun keys(held: Int, field: Int, onField: Boolean, ownAutoRun: Boolean): Int =
        if (!closed.value && allowed() && autoRun) AutoRunPolicy.apply(held, field, onField, ownAutoRun) else held

    fun cancelDiscovery() { generation.incrementAndGet(); scan?.cancel(); scan = null }
    fun close() {
        closed.value = true
        cancelDiscovery(); scope.cancel()
        if (appliedAny) core.unpatch()
        appliedAny = false; applied = null
    }
}

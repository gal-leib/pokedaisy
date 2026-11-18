package com.pokedaisy.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.pokedaisy.app.companion.data.AppearanceAssets
import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.LookEngine
import com.pokedaisy.app.companion.data.LookPart
import com.pokedaisy.app.companion.data.MonArt
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.PlayerLookAssets
import com.pokedaisy.app.companion.data.RideOn
import com.pokedaisy.app.companion.data.RideVehicle
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * PLAYER LOOK for the game running ([PlayerLook]): one background pass when a ROM loads scans it (keeping its own
 * heroes' graphics for the other games, [com.pokedaisy.app.companion.data.LookArt]), looks through the library's other FireRed / LeafGreen / Ruby /
 * Sapphire / Emerald ROMs for heroes not kept yet (once per file: `rom-art/look/scanned.txt`), and builds the patch for every hero
 * either gender of player could wear - then lets go of the ROM's bytes. [apply] hands the engine the one for the
 * save's gender ([female]: read from the save file at load, then followed in the game's own RAM) and the player's
 * pick for this ROM ([Prefs.playerLookFor]).
 */
class PlayerLookRunner(private val context: Context, private val onPatch: (PlayerLook.Patch?) -> Unit) {
    /** The heroes this game can wear (empty: not a FireRed / LeafGreen / Emerald ROM, or none kept yet) - for SETTINGS. */
    val offered = mutableStateOf<List<LookChar>>(emptyList())

    /** The game is one PLAYER LOOK knows (whether or not another hero is kept yet). */
    val supported = mutableStateOf(false)

    /** The game has a rival of the other gender to dress up too (RIVAL LOOK): Emerald, Ruby, Sapphire. */
    val rivalSupported get() = engine?.let { PlayerLook.hasRival(it) } == true

    /** The hero worn now (null = the game's own) - for the companion's MAP head and CARD. */
    val worn get() = PlayerLook.worn

    /** The pick for this ROM as SETTINGS shows it: [worn], or before the game has a gender the one that will go on. */
    val picked = mutableStateOf<LookChar?>(null)

    /** The player's own hero (the picker's OFF tile); null before the game has a gender. */
    val own = mutableStateOf<LookChar?>(null)

    /** TEXT LOOK: the other games' text looks this one can take ([com.pokedaisy.app.companion.data.TextLook]; empty: none kept, or not a game of ours). */
    val textOffered = mutableStateOf<List<LookEngine>>(emptyList())

    /** The text look on now (null = the game's own). */
    val textPicked = mutableStateOf<LookEngine?>(null)

    /** PLAYER LOOK's Pokémon: the party's species (national numbers) whose sprites are kept ([MonArt]), as
     * "MON:<number>" picks, each with its name in the game ([monNames]). */
    val offeredMons = mutableStateOf<List<Int>>(emptyList())
    @Volatile var monNames: Map<Int, String> = emptyMap()
        private set
    @Volatile private var party: List<Int> = emptyList()
    /** The running ROM's scan, kept for the Pokémon's patches (built when one is picked, [monPatch]). */
    @Volatile private var keptScan: PlayerLook.Scan? = null
    @Volatile private var keptKnown: Map<LookChar, Map<LookPart, ByteArray>> = emptyMap()
    private val monPatches = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, Int>, PlayerLook.Patch>()
    private val building = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Boolean, Int>>()

    @Volatile private var romKey: String? = null
    @Volatile private var engine: LookEngine? = null
    /** By (female, look, rival dressed too). */
    @Volatile private var patches: Map<Triple<Boolean, LookChar?, Boolean>, PlayerLook.Patch?> = emptyMap()
    /** TEXT LOOK's blocks by style. */
    @Volatile private var textPatches: Map<LookEngine, List<PlayerLook.Block>> = emptyMap()
    @Volatile var female: Boolean? = null
        private set
    @Volatile private var generation = 0

    /** A ROM loaded: forget the last one's patches and start this one's pass (off the UI thread). */
    fun load(rom: File, key: String, code: String, save: File?) {
        val gen = ++generation
        romKey = key
        engine = PlayerLook.engineFor(code)
        patches = emptyMap()
        textPatches = emptyMap()
        keptScan = null
        monPatches.clear()
        textOffered.value = emptyList()
        textPicked.value = null
        offered.value = emptyList()
        worn.value = null
        picked.value = null
        own.value = null
        supported.value = engine != null
        female = save?.let { runCatching { genderInSave(it.readBytes()) }.getOrNull() }
        val engine = engine ?: run {
            // No look for this game, but FOLLOWER may still be on: its Pokémon (this ROM's, if it has following
            // Pokémon, and the library's) are kept all the same.
            Thread({
                try {
                    val filesDir = context.filesDir.toOkioPath()
                    AppearanceAssets(filesDir).prepareFollowers(AppearanceAssets.file(rom.toOkioPath()),
                        ::librarySources, { gen == generation })
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (gen == generation) offeredMons.value = party.filter { it in MonArt.available(filesDir) }
                    }
                } catch (t: Throwable) {
                    Log.w("pokedaisy", "player look: Pokémon scan failed", t)
                }
            }, "player-look-mon").apply { isDaemon = true; start() }
            return
        }
        Thread({
            try {
                val filesDir = context.filesDir.toOkioPath()
                val catalog = PlayerLookAssets(filesDir, GameTitles::isRetail).prepare(
                    AppearanceAssets.file(rom.toOkioPath()), engine, code, ::librarySources, { gen == generation },
                )
                val mons = MonArt.available(filesDir)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (gen != generation) return@post
                    supported.value = catalog != null
                    if (catalog == null) return@post
                    patches = catalog.heroes
                    textPatches = catalog.text
                    keptScan = catalog.scan
                    keptKnown = catalog.known
                    offeredMons.value = party.filter { it in mons }
                    apply()
                }
            } catch (t: Throwable) {
                Log.w("pokedaisy", "player look: scan failed", t)
            }
        }, "player-look").apply { isDaemon = true; start() }
    }

    /** The game's own gender read (SaveBlock2's playerGender; -1 = not yet): a new game picks one in the intro. */
    fun onGender(gender: Int) {
        if (gender !in 0..1 || engine == null) return
        val f = gender == 1
        if (female == f) return
        female = f
        apply()
    }

    /** The pick for this ROM ([Prefs.playerLookFor]) changed, or the patches / gender came in: the engine gets the
     * patch now wanted. */
    fun apply() {
        val key = romKey ?: return
        if (patches.isEmpty()) return
        val prefs = Prefs(context)
        textOffered.value = textPatches.keys.toList()
        val text = textPatches.keys.firstOrNull { it.name == prefs.textLookFor(key) }
        textPicked.value = text
        val textBlocks = text?.let { textPatches[it] }.orEmpty()
        val f = female
        if (f == null) {
            // No gender yet (a new game, before the intro asks): every hero either player could wear; the pick goes
            // on once the game has one.
            offered.value = LookChar.entries.filter { c -> listOf(false, true).any { patches[Triple(it, c, false)]?.blocks?.isNotEmpty() == true } }
            worn.value = null
            own.value = null
            picked.value = LookChar.byName(prefs.playerLookFor(key))?.takeIf { it in offered.value }
            onPatch(PlayerLook.Patch(textBlocks, emptyList()).takeIf { textBlocks.isNotEmpty() })
            return
        }
        val me = LookChar.of(engine ?: return, f)
        own.value = me
        offered.value = LookChar.entries.filter { it != me && patches[Triple(f, it, false)]?.blocks?.isNotEmpty() == true }
        val saved = prefs.playerLookFor(key)
        // A Pokémon: its patch built off the UI thread the first time, then this again.
        MonArt.monOf(saved)?.takeIf { it in offeredMons.value }?.let { n ->
            pickedMon.value = n
            worn.value = null
            picked.value = null
            val patch = monPatch(f, me, n) ?: return
            onPatch(if (textBlocks.isEmpty()) patch else PlayerLook.Patch(patch.blocks + textBlocks, patch.palettes, patch.playerPal))
            return
        }
        pickedMon.value = null
        val pick = LookChar.byName(saved)?.takeIf { it in offered.value }
        worn.value = pick
        picked.value = pick
        val rival = prefs.rivalLook && PlayerLook.hasRival(me.engine)
        val look = ride(me, patches[Triple(f, pick, rival)] ?: patches[Triple(f, pick, false)], RideVehicle.of(prefs.rideVehicleId))
        onPatch(if (textBlocks.isEmpty()) look else PlayerLook.Patch(look?.blocks.orEmpty() + textBlocks, look?.palettes.orEmpty(), look?.playerPal, look?.ride))
    }

    /** RIDE ON: [look]'s patch with [vehicle] written over [me]'s bike sheets as worn (RideOn), in the worn palette. */
    private fun ride(me: LookChar, look: PlayerLook.Patch?, vehicle: RideVehicle): PlayerLook.Patch? {
        val scan = keptScan ?: return look
        if (vehicle == RideVehicle.BIKE) return look
        val pal = look?.playerPal ?: scan.data(me, LookPart.OW_PAL) ?: return look
        val under = look?.blocks.orEmpty().map { it.off to it.data }
        val rides = runCatching { RideOn.writes(scan, me, under, pal, vehicle) }
            .onFailure { Log.w("pokedaisy", "ride on", it) }.getOrNull().orEmpty()
        if (rides.isEmpty()) return look
        return PlayerLook.Patch(PlayerLook.merge(scan.rom, under + rides), look?.palettes.orEmpty(), look?.playerPal, RideOn.sprite(vehicle))
    }

    /** The Pokémon picked (a national number; null: a hero or the game's own). */
    val pickedMon = mutableStateOf<Int?>(null)

    /** The party changed: its species (national numbers, with their names in the game) are the Pokémon offered. */
    fun onParty(nationals: List<Int>, names: Map<Int, String>) {
        if (nationals == party) return
        party = nationals
        monNames = names
        if (keptScan == null) return
        offeredMons.value = nationals.filter { it in MonArt.available(context.filesDir.toOkioPath()) }
        apply()
    }

    /** [n]'s patch for the player ([female], [me]), or null while it's built (on its own thread, then [apply] again). */
    private fun monPatch(female: Boolean, me: LookChar, n: Int): PlayerLook.Patch? {
        monPatches[female to n]?.let { return it }
        val scan = keptScan ?: return null
        if (!building.add(female to n)) return null
        val gen = generation
        Thread({
            try {
                val mon = MonArt.load(context.filesDir.toOkioPath(), n) ?: return@Thread
                val own = { p: LookPart -> scan.data(me, p) }
                // The party's other Pokémon: a state made wearing one of them may have loaded its palette.
                val extra = offeredMons.value.filter { it != n }.mapNotNull { MonArt.load(context.filesDir.toOkioPath(), it) }.mapNotNull { PlayerLook.wearMon(me, it, own) }
                val patch = PlayerLook.buildMon(scan, me, mon, keptKnown, extra)
                if (patch != null) monPatches[female to n] = patch
                Log.i("pokedaisy", "player look: ${if (patch == null) "no" else patch.blocks.size} blocks for Pokémon $n")
            } catch (t: Throwable) {
                Log.w("pokedaisy", "player look: Pokémon $n failed", t)
            } finally {
                building.remove(female to n)
                android.os.Handler(android.os.Looper.getMainLooper()).post { if (gen == generation && monPatches.containsKey(female to n)) apply() }
            }
        }, "player-look-mon").apply { isDaemon = true; start() }
        return null
    }

    /** Heroes, text looks and following Pokémon still missing are looked for in the library's other FireRed / LeafGreen
     * / Ruby / Sapphire / Emerald ROMs (the hacks with following Pokémon carry those game codes), each once. */
    private fun librarySources() = runCatching { RomFolder.libraryRoms(context, Prefs(context)) }
        .getOrDefault(emptyList()).map { f ->
            AppearanceAssets.Source(f.canonicalPath, "${f.absolutePath}|${f.length()}|${f.lastModified()}", f.name,
                { RomIdentity.gameCode(f) }) { RomArchive.open(f).use { it.readBytes() } }
        }

    companion object {
        /**
         * The save's player gender (SaveBlock2 +8: after the 8-byte name in every Gen 3 game) from the save file's newer
         * slot - sector id 0 (SaveBlock2) with the higher save counter. Null if no sector checks out.
         */
        fun genderInSave(save: ByteArray): Boolean? {
            var best: Long = -1
            var gender: Int? = null
            for (s in 0 until minOf(32, save.size / 0x1000)) {
                val base = s * 0x1000
                fun u32(o: Int) = (save[base + o].toLong() and 0xFF) or ((save[base + o + 1].toLong() and 0xFF) shl 8) or
                    ((save[base + o + 2].toLong() and 0xFF) shl 16) or ((save[base + o + 3].toLong() and 0xFF) shl 24)
                val id = (save[base + 0xFF4].toInt() and 0xFF) or ((save[base + 0xFF5].toInt() and 0xFF) shl 8)
                if (u32(0xFF8) != 0x08012025L || id != 0) continue
                val counter = u32(0xFFC)
                val g = save[base + 8].toInt() and 0xFF
                if (counter > best && g <= 1) { best = counter; gender = g }
            }
            return gender?.let { it == 1 }
        }
    }
}

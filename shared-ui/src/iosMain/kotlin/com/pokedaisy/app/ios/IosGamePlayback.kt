package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.*
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.core.PkApiCore
import okio.Path.Companion.toPath
import platform.Foundation.NSUserDefaults

/** Drives the existing memory watchers once per game frame and feeds the common Android speed policy. */
internal class IosGamePlayback(
    private val romPath: String,
    private val code: String?,
    private val telemetry: TelemetryStore,
    private val battle: IosBattleInput,
) {
    private val gba = code != null
    private val defaults = NSUserDefaults.standardUserDefaults
    private var menu = false
    private var map = false
    private var field = 0
    val fieldState get() = field
    val onField get() = gba && !blocked && FfMenuWatch.onField
    var readField: () -> Int = PkApiCore::fieldState
    var blocked = false
        private set
    private var base = 1f
    private var ff = false
    private var ffSpeed = 2f
    private var smart = true
    private var battleSpeed = 0f
    private var textSpeed = 0f
    private var menuSpeed = 0f
    private var bikeSpeed = 0f
    private var surfSpeed = 0f
    var fieldReadable by mutableStateOf(false)
        private set
    var fieldMovesReadable by mutableStateOf(false)
        private set

    fun settingsChanged(s: IosCompanionSettings) {
        base = s.gameSpeed; ff = s.ffActive; ffSpeed = s.ffMaxSpeed; smart = s.ffMode == FfMode.SMART
        battleSpeed = s.battleSpeed; textSpeed = s.textSpeed; menuSpeed = s.menuSpeed
        bikeSpeed = s.bikeSpeed; surfSpeed = s.surfSpeed
    }

    fun start() {
        val crc = if (gba) runCatching { romCrc32(romPath.toPath()) }.getOrDefault(0L) else 0L
        val romKey = crc.toString(16).padStart(8, '0')
        blocked = BlockedGames.forCrc(romKey) != null
        val key = "ff_menu_cb2_v2_$romKey"
        val learned = defaults.stringForKey(key)?.split(',')
        FfMenuWatch.load(code.orEmpty(), learned?.getOrNull(0)?.toLongOrNull() ?: 0, learned?.getOrNull(1)?.toLongOrNull() ?: 0)
        FfMenuWatch.onLearned = { field, battle -> defaults.setObject("$field,$battle", key) }
        ShopWatch.load()
        RegionMapWatch.load(if (gba && !blocked) romPath.toPath() else null)
        fieldReadable = gba && !blocked && PkApiCore.configureField(crc)
        fieldMovesReadable = false
    }

    fun sampled() {
        val snapshot = telemetry.snapshot.value
        if (snapshot.blocked != null) {
            blocked = true
            PkApiCore.configureField(0)
            fieldReadable = false
            fieldMovesReadable = false
            return
        }
        if (!gba) return
        telemetry.knownGMain()?.let { (addr, offset) -> FfMenuWatch.useKnownGMain(addr, offset) }
        if (snapshot.connected) FfMenuWatch.notePosition(snapshot.x, snapshot.y, snapshot.mapGroup, snapshot.mapNum, snapshot.inBattle)
    }

    fun tick() {
        if (!gba || blocked || telemetry.snapshot.value.blocked != null) { field = 0; menu = false; map = false; return }
        map = RegionMapWatch.isOpen(InProcessReader)
        menu = FfMenuWatch.tick(InProcessReader) && !ShopWatch.isOpen(InProcessReader)
        field = if (fieldReadable) readField() else 0
        if (field and GameField.AVATAR != 0) fieldMovesReadable = true
    }

    /** Zero is the native host's uncapped sentinel; the common policy uses infinity as Android does. */
    fun speed(): Double {
        if (battle.holdPicture) return 0.0
        val scene = PlaybackPolicy.scene(battle.gameState, menu, map, gba && FfMenuWatch.battling, field)
        val requested = if (ff) ffSpeed.takeIf { it > 0f } ?: Float.POSITIVE_INFINITY else
            PlaybackPolicy.sceneSpeed(scene, base, battleSpeed, textSpeed, menuSpeed, bikeSpeed, surfSpeed)
        val speed = PlaybackPolicy.smartSpeed(requested, smart, battle.gameState, menu, map)
        return if (speed.isInfinite()) 0.0 else speed.toDouble()
    }

    fun close() {
        FfMenuWatch.onLearned = null
        RegionMapWatch.load(null)
    }
}

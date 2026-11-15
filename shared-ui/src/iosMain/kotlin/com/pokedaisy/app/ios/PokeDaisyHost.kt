@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.StateSlotController
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.SnapshotCache
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.data.Gen1Art
import com.pokedaisy.app.companion.data.Gen2Art
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.ui.CompanionScreen
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.game.GameViewController
import com.pokedaisy.app.game.RomPicker
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT

/** How often the companion reads the running game (Android's sampler runs about this often too). */
private const val SAMPLE_MILLIS = 500L

/** A save larger than this isn't one (a GBA flash chip is 128 KB; a Game Boy cart's RAM less). */
private const val MAX_SAVE_BYTES = 1 shl 20

/**
 * What the iPad app's Swift side calls (as `PokeDaisyHost.shared` in Swift; not `PokeDaisy`, which is the app's own
 * Swift module): [start] once at launch, [gameScreenViewController] for the game and companion, [games] /
 * [addGames] for the games added from Files, [closeGame] to close one. While a game runs,
 * [telemetry] reads it every half second, off the main thread (the core's reads may come from any thread), and the
 * companion follows it.
 */
object PokeDaisyHost {
    /** The companion's view of the running game; "starting…" until a game feeds it. */
    val telemetry = TelemetryStore()

    /** Swift's: CLOSE GAME / RESTART GAME in the companion's SETTINGS (the game's place is SwiftUI's to change). */
    var onCloseRequested: () -> Unit = {}
    var onRestartRequested: () -> Unit = {}

    // The running game's SETTINGS; null = NO GAME RUNNING.
    private var settings by mutableStateOf<IosCompanionSettings?>(null)
    private var battle by mutableStateOf<com.pokedaisy.app.companion.GameBattleInput?>(null)
    private var slots by mutableStateOf<StateSlotController?>(null)
    private var stateError by mutableStateOf<String?>(null)
    private var restartFresh = false
    private var started = false
    private var game: GameViewController? = null
    private val touch = IosTouchControls({ game }, { settings })
    private var screen: IosGameScreen? = null
    private var playback: com.pokedaisy.app.companion.GamePlayback? = null
    private var options: com.pokedaisy.app.companion.GameOptions? = null
    private var cheats: com.pokedaisy.app.companion.GameCheats? = null
    private var items by mutableStateOf<com.pokedaisy.app.companion.ItemUse?>(null)
    private var fly by mutableStateOf<com.pokedaisy.app.companion.FlyAction?>(null)
    private var sampler: com.pokedaisy.app.companion.GameSampler? = null

    /** Sets the shared screens' hosts (fonts, pictures, settings, the manual). Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        IosHosts.install(appFilesDir())
        IosTouchPreferences.changed = touch::releaseKeys
        IosTouchPreferences.install()
    }

    /** One Compose host for the native Metal game surface, shared controls and companion. */
    fun gameScreenViewController(romPath: String, onFailed: () -> Unit): UIViewController {
        start()
        val current = createGameController(romPath, onFailed)
        val gameBoy = romPath.substringAfterLast('.').lowercase() in listOf("gb", "gbc")
        val panels = IosGameScreen(game, touch, gameBoy).also { screen = it }
        val currentSettings = settings
        val currentSlots = slots
        if (currentSettings != null && currentSlots != null) {
            currentSettings.rewindUnavailable = panels::showRewindUnavailable
            val shortcuts = com.pokedaisy.app.companion.GameActions(currentSettings, currentSlots,
                selectSlot = { currentSlots.select(it) }, companion = panels::toggleCompanion,
                hold = { action, down, source ->
                    val touched = source == com.pokedaisy.app.companion.GameActions.Source.TOUCH
                    when (action) {
                        com.pokedaisy.app.Hotkeys.Action.FF_HOLD -> if (touched) currentSettings.holdFastForward(down) else currentSettings.holdHardwareFastForward(down)
                        com.pokedaisy.app.Hotkeys.Action.SLOWMO_HOLD -> currentSettings.holdSlowmo(down)
                        com.pokedaisy.app.Hotkeys.Action.REWIND_HOLD -> if (touched) currentSettings.holdTouchRewind(down) else currentSettings.holdHardwareRewind(down)
                        else -> Unit
                    }
                })
            touch.actions = shortcuts
            game?.onHotkey = { shortcuts.hotkey(it) }
        }
        return ComposeUIViewController(configure = { configureUiAutomation() }) {
            AppTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    panels.Content(
                        gameContent = { UIKitViewController(factory = { current }, modifier = Modifier.fillMaxSize(),
                            properties = UIKitInteropProperties(interactionMode = null)) },
                        companion = { Companion(panels) })
                }
            }
        }
    }

    @Composable
    private fun Companion(panels: IosGameScreen) {
        val snapshot by telemetry.snapshot.collectAsState()
        Box(Modifier.fillMaxSize()) {
            CompanionScreen(snapshot, slots = slots, settings = settings, battleInput = battle, gamePick = telemetry.gamePick,
                back = panels.back, keys = panels.keys, itemUse = items, fly = fly)
            stateError?.let { message ->
                OptionConfirm(tr("SAVE STATES"), message, tr("OK"), rememberGbaTextMetrics(),
                    onConfirm = { stateError = null }, onDismiss = { stateError = null }, cancelLabel = null)
            }
        }
    }

    /** The games added from Files (their paths in the app's Documents/roms), by name. */
    fun games(): List<String> {
        val dir = RomPicker.romsDir
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>().orEmpty()
        return names.filter { it.substringAfterLast('.', "").lowercase() in RomPicker.EXTENSIONS }
            .sortedBy { it.lowercase() }
            .map { "$dir/$it" }
    }

    /** Files' picker over [from]; [onAdded] gets the games it copied in (none if cancelled). */
    fun addGames(from: UIViewController, onAdded: (List<String>) -> Unit) = RomPicker.pick(from, onAdded)

    /**
     * LOAD SAVE (the Android library's, for the iPad's game list): the save picked in Files at [fromPath] becomes
     * [romPath]'s battery save, the one before kept beside it as `<name>.backup-<yyyyMMdd-HHmmss>.sav`. The game must
     * not be running. Null when it worked, else what went wrong, for the player.
     */
    fun loadSave(romPath: String, fromPath: String): String? {
        val fs = FileSystem.SYSTEM
        val source = fromPath.toPath()
        val size = fs.metadataOrNull(source)?.size ?: return "Can't read that file"
        if (size !in 1L..MAX_SAVE_BYTES.toLong()) return "That isn't a game save"
        val bytes = runCatching { fs.read(source) { readByteArray() } }.getOrNull() ?: return "Can't read that file"
        return loadSave(romPath, bytes)
    }

    /** The shared import dialog retains a bounded copy until the player confirms. */
    fun loadSave(romPath: String, bytes: ByteArray): String? {
        val fs = FileSystem.SYSTEM
        if (bytes.isEmpty() || bytes.size > MAX_SAVE_BYTES) return "That isn't a game save"
        // RetroArch's compressed saves start "#RZIPv": the Android app unpacks them, the iPad app can't yet.
        if (bytes.size >= 6 && bytes.copyOfRange(0, 6).decodeToString() == "#RZIPv") {
            return "That save is compressed: turn off RetroArch's save compression and save again"
        }
        val current = RomPicker.savePathFor(romPath).toPath()
        val dir = current.parent ?: return "Can't write the save"
        val backup = dir / backupName(current.name)
        val tmp = dir / "${current.name}.tmp"
        return runCatching {
            fs.createDirectories(dir)
            StateFiles.backupBattery(current)
            // Commit this before replacing the save: neither a crash nor a matching old slot may undo the import.
            StateResume.startFresh(StateFiles(RomPicker.statesDir.toPath() / StateFiles.romKey(romPath.toPath())))
            if (fs.exists(current)) fs.atomicMove(current, backup)
            fs.write(tmp) { write(bytes) }
            fs.atomicMove(tmp, current)
            null
        }.getOrElse {
            // Put the old save back.
            runCatching { fs.delete(tmp) }
            if (!fs.exists(current) && fs.exists(backup)) runCatching { fs.atomicMove(backup, current) }
            "Can't write the save"
        }
    }

    private fun backupName(saveName: String): String {
        val f = NSDateFormatter().apply {
            dateFormat = "yyyyMMdd-HHmmss"
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
        }
        return "${saveName.substringBeforeLast('.')}.backup-${f.stringFromDate(NSDate())}.${saveName.substringAfterLast('.')}"
    }

    /** The game at [romPath] running, its battery save beside the others; closes the one before. [onFailed] hears a
     * game that won't open. */
    private fun createGameController(romPath: String, onFailed: () -> Unit): UIViewController {
        val fresh = restartFresh
        restartFresh = false
        closeGame()
        telemetry.reset()
        val savePath = RomPicker.savePathFor(romPath)
        val files = runCatching {
            StateFiles.backupBattery(savePath.toPath())
            StateFiles(RomPicker.statesDir.toPath() / StateFiles.romKey(romPath.toPath())).also {
                if (fresh) StateResume.startFresh(it)
            }
        }.getOrElse { onFailed(); return UIViewController() }
        val g = GameViewController(romPath, savePath, onFailed)
        game = g
        val defaults = platform.Foundation.NSUserDefaults.standardUserDefaults
        val stateSnapshot = { telemetry.forState()?.takeIf { it.hasData && !it.unsupported }?.let(SnapshotCache::encode) }
        val stateLoaded: (Path) -> Unit = { state ->
            telemetry.reset()
            SnapshotCache.read(state)?.let(telemetry::showCached)
            sample()
        }
        val resume = StateResume(files,
            write = { PkApiCore.saveState(it.toString()) }, read = { PkApiCore.loadState(it.toString()) },
            matchesSave = { PkApiCore.stateMatchesSave(it.toString()) },
            enabled = { defaults.objectForKey("auto_resume") == null || defaults.boolForKey("auto_resume") },
            snapshot = stateSnapshot, loaded = stateLoaded)
        val (code, revision) = (if (romPath.lowercase().endsWith(".gba"))
            com.pokedaisy.app.companion.data.RomIdentityReader.forPath(romPath.toPath()).header() else null) ?: (null to 0)
        g.onCoreStarted = { playback?.start(romPath.toPath(), code, if (code != null) runCatching { com.pokedaisy.app.companion.data.romCrc32(romPath.toPath()) }.getOrDefault(0L) else 0L); options?.start(romPath.toPath(), PkApiCore::configureWild); cheats?.start(); g.withState { resume.restore() } }
        g.beforeSuspend = {
            val saved = g.withState { resume.save() }
            if (!saved) resumeFailed()
            saved
        }
        settings = IosCompanionSettings(romPath, code, g,
            onClose = { finishClose() },
            onRestart = { if (prepareClose()) { restartFresh = true; onRestartRequested() } },
            onDisableResume = {
                runCatching { resume.startFresh(); true }.getOrElse { resumeFailed(); false }
            }, onEditTouch = touch::edit, readTouchStage = touch::readStage, gameScreen = { screen }, playback = { playback }, options = { options }, cheatHost = { cheats })
            .also { s ->
                L10n.apply(s.appLanguage, code)
                s.rewindChanged = { g.rewindEnabled = s.rewind; g.holdRewind(s.rewindHeld) }
                s.rewindChanged()
            }
        battle = com.pokedaisy.app.companion.GameBattleInput(telemetry, code, revision, changed = { sample() }, silent = { g.muted || g.volume == 0f || g.speed == 0.0 }).also { b ->
            g.frameKeys = b::keys
            g.holdPicture = { b.holdPicture }
            g.quietPress = { b.quietPress }
        }
        playback = com.pokedaisy.app.companion.GamePlayback(
            com.pokedaisy.app.game.GameSession(PkApiCore, PkApiCore::callSafe) { where, error -> com.pokedaisy.app.companion.data.Logs.i("$where: $error") },
            telemetry, iosPreferences, battle!!, PkApiCore::configureField, { PkApiCore.configureField(0) }).also { p ->
            settings!!.playbackChanged = { settings?.let { p.settingsChanged(it, it.ffActive, it.slowmoHeld) } }
            p.settingsChanged(settings!!, settings!!.ffActive, settings!!.slowmoHeld)
            g.frameSpeed = { p.speed().let { if (it.isInfinite()) 0.0 else it.toDouble() } }
            val bKeys = battle!!::keys
            val gameOptions = com.pokedaisy.app.companion.GameOptions(PkApiCore,
                { code != null && !p.blocked && telemetry.snapshot.value.blocked == null }).also { o ->
                settings!!.optionsChanged = { settings?.let(o::settingsChanged) }
                o.settingsChanged(settings!!)
                p.readField = { o.fieldState(PkApiCore::stepField, com.pokedaisy.app.companion.data.GameField.supportsRunIndoors(code.orEmpty())) }
                g.frameKeys = { held -> bKeys(o.keys(held, p.fieldState, p.onField,
                    com.pokedaisy.app.companion.data.activeGame in com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN)) }
            }
            options = gameOptions
            g.beforeFrame = p::beforeFrame
            g.afterFrame = { p.tick(); gameOptions.tick() }
        }
        val p = playback!!
        val fields = com.pokedaisy.app.companion.data.FieldItemSupportCache()
        fun live() = game === g && g.isRunning && code != null && !p.blocked && telemetry.snapshot.value.blocked == null
        val readSupport = {
            val snapshot = telemetry.snapshot.value
            if (live()) fields.get(snapshot, code.orEmpty(), revision, telemetry.knownFieldItems()) else null
        }
        val currentSession = { p.session.takeIf { live() } }
        val sharedItems = com.pokedaisy.app.companion.FieldItemUse(
            support = readSupport, session = currentSession,
            inBattle = { telemetry.snapshot.value.inBattle },
            deliver = { result -> result(); if (live()) sample() },
        )
        val classicFly = com.pokedaisy.app.companion.ClassicFlyAction(sharedItems, readSupport, currentSession) {
            if (screen?.state?.side?.windowOpen == true) screen?.state?.hide()
        }
        fly = object : com.pokedaisy.app.companion.FlyAction by classicFly {
            override val actionBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_CLASSIC_FLY)
        }
        items = object : com.pokedaisy.app.companion.ItemUse by sharedItems {
            override val actionBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FIELD_ITEMS)
            override fun openPc(done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {
                sharedItems.openPc { outcome ->
                    if (outcome.result == com.pokedaisy.app.companion.data.ItemUseResult.USED && screen?.state?.side?.windowOpen == true) screen?.state?.hide()
                    done(outcome)
                }
            }
        }
        g.beforeRewind = battle!!::cancel
        val currentSettings = settings!!
        cheats = com.pokedaisy.app.companion.GameCheats(
            supported = romPath.substringAfterLast('.').lowercase() == "gba",
            openStore = { com.pokedaisy.app.cheats.CheatStore.forCrc(appFilesDir(),
                com.pokedaisy.app.companion.data.romCrc32(romPath.toPath()).toString(16).padStart(8, '0')) },
            enabled = { currentSettings.cheatsEnabled },
            submit = { text -> if (g.isRunning && PkApiCore.applyCheats(text) < 0) com.pokedaisy.app.companion.data.Logs.i("cheats didn't load") })
        g.beforeStateChange = { currentSettings.releaseRewind(); battle?.cancel(); touch.releaseKeys() }
        slots = StateSlotController(files, onCore = g::withState,
            write = { PkApiCore.saveState(it.toString()) }, read = { PkApiCore.loadState(it.toString()) },
            beforeLoad = { StateFiles.backupBattery(savePath.toPath()) },
            thumbnail = { PkApiCore.stateThumbnail() },
            snapshot = stateSnapshot, loaded = stateLoaded,
            failed = { stateError = it },
            initialIndex = defaults.integerForKey("state_slot").toInt(),
            selected = { defaults.setInteger(it.toLong(), "state_slot") })
        prefetchArt(romPath)
        sampler = com.pokedaisy.app.companion.GameSampler.forGame(telemetry,
            gameBoy = !romPath.lowercase().endsWith(".gba"), game = kotlinx.coroutines.Dispatchers.Main,
            current = { game === g && g.isRunning }, after = {
                battle?.sampled(); playback?.sampled(); settings?.let { options?.settingsChanged(it) }
            }).also { it.startPeriodic(SAMPLE_MILLIS) }
        return g
    }

    /** Full-window shared library, setup and application settings. */
    fun libraryViewController(failed: String?, onPlay: (String) -> Unit): UIViewController {
        start()
        return IosLibraryHost(touch, onPlay, failed).controller()
    }

    private fun finishClose() {
        if (prepareClose()) onCloseRequested()
    }

    private fun prepareClose(): Boolean {
        if (game?.pause() != false) return true
        // A background capture may fail too, but an explicit close can keep the running moment available.
        game?.resume()
        return false
    }

    private fun resumeFailed() {
        stateError = tr("Couldn't update resume data. Keep an in-game save too.")
        platform.Foundation.NSLog("PokeDaisy: couldn't update resume data")
    }

    /** Writes the running game's save and closes it; the companion goes back to waiting. */
    fun closeGame() {
        stateError = null
        sampler?.close()
        sampler = null
        options?.close()
        options = null
        cheats = null
        items = null
        fly = null
        playback?.close()
        playback = null
        battle?.cancel()
        battle = null
        screen?.close()
        screen = null
        touch.close()
        game?.close()
        game = null
        slots = null
        settings = null
        telemetry.reset()
    }

    /** The party art, region maps and card pieces the screens draw, rebuilt from the player's ROM the first time it
     * runs (as the Android app does on launch; RomArt / Gen1Art / Gen2Art scan in the background). */
    private fun prefetchArt(romPath: String) {
        val files = appFilesDir()
        val rom = romPath.toPath()
        when (romPath.substringAfterLast('.', "").lowercase()) {
            "gba" -> RomArt.prefetch(files, "${rom.name}-${FileSystem.SYSTEM.metadataOrNull(rom)?.size ?: 0}", rom)
            "gb", "gbc" -> {
                Gen1Art.prefetch(files, rom)
                Gen2Art.prefetch(files, rom)
            }
        }
    }

    private fun sample() { sampler?.request() }
}

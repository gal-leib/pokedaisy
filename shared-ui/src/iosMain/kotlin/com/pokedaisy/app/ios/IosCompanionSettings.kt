package com.pokedaisy.app.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.BattleStyle
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.displayName
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.i18n.LANGUAGE_AUTO
import com.pokedaisy.app.companion.ui.theme.ThemeStore
import com.pokedaisy.app.companion.ui.theme.pickTheme
import com.pokedaisy.app.companion.ui.theme.themeById
import platform.Foundation.NSUserDefaults
import com.pokedaisy.app.game.GameViewController

/**
 * The companion's SETTINGS on the iPad: shared battle and appearance options, playback and sound, kept in the
 * app's defaults. Only supported rows are shown ([shownRows]); shaders and EXPAND wait for later ports.
 */
internal class IosCompanionSettings(
    private val romPath: String?,
    /** The ROM header's game code (BPEE...), for LANGUAGE's AUTO; null for a Game Boy cart. */
    private val romCode: String?,
    private val game: GameViewController?,
    private val onClose: () -> Unit,
    private val onRestart: () -> Unit,
    private val onDisableResume: () -> Boolean,
    private val onEditTouch: () -> Unit,
    private val readTouchStage: () -> com.pokedaisy.app.companion.TouchStage?,
    private val gameScreen: () -> IosGameScreen? = { null },
    private val playback: () -> IosGamePlayback? = { null },
    private val options: () -> IosGameOptions? = { null },
) : CompanionSettings {
    private val d = NSUserDefaults.standardUserDefaults
    private val libraryInput = if (game == null) com.pokedaisy.app.game.GameInput() else null
    override val keyCapture get() = game?.keyCapture ?: libraryInput!!.capture
    fun pollKeyCapture() { libraryInput?.pollCapture() }
    private fun reloadBindings() {
        game?.reloadBindings() ?: libraryInput?.reloadBindings()
        game?.setHotkeys(if (hotkeysEnabled) Hotkeys.load(com.pokedaisy.app.game.IosGameInputBindings.directory).forActions(hotkeyActions) else null)
    }

    private fun bool(key: String, default: Boolean) = if (d.objectForKey(key) == null) default else d.boolForKey(key)

    override val hasRunningGame get() = game != null
    override val gamePickBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_PICKER)
    override val shownRows: Set<String> get() = setOf("BATTLE HINTS", "FOE IVS", "TAB BAR", "DARK MODE", "THEME", "LANGUAGE", "HELP",
        "FF", "FF SPEED", "GAME SPEED", "GAME VOLUME", "MUTE GAME", "ONE-TAP MOVES", "BATTLE INFO", "BATTLE STYLE", "BATTLE VIEW", "BATTLE FOCUS", "RESUME GAMES", "TOUCH CONTROLS", "COMPANION VIEW", "SPEED OPTIONS", "INSTANT TEXT", "REUSABLE TMS", "AUTO-RUN", "RUN INDOORS", "WILD ENCOUNTERS", "GAME BUTTONS", "HOTKEYS")
        .let { if (game == null) it - "GAME SPEED" - "FF" else it }

    var optionsChanged: () -> Unit = {}
    override val autoRun get() = bool("auto_run", false)
    override fun setAutoRun(on: Boolean) { d.setBool(on, "auto_run"); optionsChanged() }
    override val autoRunBuiltIn get() = hasRunningGame && activeGame in com.pokedaisy.app.companion.data.GAMES_WITH_OWN_AUTO_RUN
    override val runIndoors get() = bool("run_indoors", false)
    override fun setRunIndoors(on: Boolean) { d.setBool(on, "run_indoors"); optionsChanged() }
    override val runIndoorsSupported get() = fieldReadable && com.pokedaisy.app.companion.data.GameField.supportsRunIndoors(romCode.orEmpty())
    override val wildEncounters get() = if (d.objectForKey("wild_encounters") != null && d.integerForKey("wild_encounters") == 0L) 0 else 100
    override fun setWildEncounters(percent: Int) { d.setInteger(if (percent == 0) 0L else 100L, "wild_encounters"); optionsChanged() }
    override val wildEncountersSupported get() = fieldReadable && options()?.wildFlagFound == true
    override val fieldOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_FIELD_OPTIONS)
    override val instantText get() = bool("instant_text", false)
    override fun setInstantText(on: Boolean) { d.setBool(on, "instant_text"); optionsChanged() }
    override val instantTextSupported get() = options()?.instantTextSupported == true
    override val reusableTms get() = bool("reusable_tms", false)
    override fun setReusableTms(on: Boolean) { d.setBool(on, "reusable_tms"); optionsChanged() }
    override val reusableTmsSupported get() = options()?.reusableTmsSupported == true
    override val reusableTmsBuiltIn get() = hasRunningGame && activeGame in com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN
    override val gameOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_OPTIONS)

    override val touchRevision get() = IosTouchPreferences.revision
    override val touchStyle get() = IosTouchPreferences.style
    override fun setTouchStyle(style: TouchControlsStyle) { IosTouchPreferences.style = style }
    override val touchButtons get() = IosTouchPreferences.buttons
    override val touchSkinsSupported = false
    override val touchBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_SCREEN)
        ?: com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_TOUCH)
    override val companionViewBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_SCREEN)
    override val companionWindow get() = gameScreen()?.takeUnless { it.portrait }?.window
    override fun setCompanionWindow(on: Boolean) { gameScreen()?.window = on }
    override val touchStage get() = readTouchStage()
    override val touchStageInOtherOrientation get() = touchStage?.let {
        gameScreen()?.previewStage(otherOrientation = true) ?:
            com.pokedaisy.app.companion.ui.gamePanelLayout(it.h, it.w, it.dp, gameBoy = it.gameBoy, underGame = IosTouchPreferences.underGame, style = IosTouchPreferences.style)
    }
    override val touchControlsShown get() = IosTouchPreferences.visible
    override fun setPortraitUnderGame(on: Boolean) = IosTouchPreferences.setUnderGame(on)
    override val uprightUnderGame get() = IosTouchPreferences.underGame
    override fun editTouchLayout() = onEditTouch()
    override fun feelVibration(strength: Int) = IosTouchPreferences.feel(strength)

    override val autoResume: Boolean get() = bool("auto_resume", true)
    override val resumeGamesEditable = true
    override val resumeBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_RESUME)
    override fun setAutoResume(on: Boolean) {
        if (on == autoResume) return
        if (on || onDisableResume()) d.setBool(on, "auto_resume")
    }

    override val showHints: Boolean get() = bool("show_hints", true)
    override fun setShowHints(on: Boolean) = d.setBool(on, "show_hints")
    override val showFoeIvs: Boolean get() = bool("show_foe_ivs", false)
    override fun setShowFoeIvs(on: Boolean) = d.setBool(on, "show_foe_ivs")

    override val appLanguage: String get() = d.stringForKey("app_language") ?: LANGUAGE_AUTO
    override fun setAppLanguage(code: String) {
        d.setObject(code, "app_language")
        L10n.apply(code, romCode)
    }

    override fun setDarkMode(v: Int) { ThemeStore.current?.darkMode = v }
    override fun setAppTheme(id: Int) = pickTheme(themeById(id))

    override val companionTabs: List<String>
        get() = d.stringForKey("companion_tabs")?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_COMPANION_TABS
    override fun setCompanionTabs(tabs: List<String>) = d.setObject(tabs.joinToString(","), "companion_tabs")

    private fun guideNotices() = d.stringForKey("guide_notice_ok")?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
    override fun guideNoticeAccepted(game: String) = game in guideNotices()
    override fun acceptGuideNotice(game: String) = d.setObject((guideNotices() + game).joinToString(","), "guide_notice_ok")

    override val gameName: String get() = if (game == null) "" else activeGame.displayName()
    override val romFileName: String get() = romPath.orEmpty().substringAfterLast('/')

    override fun closeGame() = onClose()
    override fun restartGame() = onRestart()

    override val statesBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_STATES)

    override val playbackBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_PLAYBACK)

    private var ffToggle by mutableStateOf(bool("ff_toggled", false))
    override val ffToggled get() = ffToggle
    private var ffHeld by mutableStateOf(false)
    private var hardwareFfHeld by mutableStateOf(false)
    var slowmoHeld by mutableStateOf(false)
        private set
    val ffActive get() = ffToggled || ffHeld || hardwareFfHeld
    fun holdHardwareFastForward(on: Boolean) { hardwareFfHeld = on; applyPlayback() }
    fun holdSlowmo(on: Boolean) { slowmoHeld = on; applyPlayback() }
    fun holdFastForward(on: Boolean) { ffHeld = on; applyPlayback() }

    var playbackChanged: () -> Unit = {}
    init { applyPlayback(); reloadBindings() }

    private fun applyPlayback() {
        val game = game ?: return
        val requested = com.pokedaisy.app.companion.PlaybackPolicy.requestedSpeed(ffActive, ffMaxSpeed, slowmoHeld, gameSpeed)
        game.speed = if (requested.isInfinite()) 0.0 else requested.toDouble()
        game.volume = gameVolume / 100f
        game.muted = gameMuted
        playbackChanged()
    }

    override val ffMaxSpeed: Float get() = if (d.objectForKey("ff_max_speed") == null) 2f else d.floatForKey("ff_max_speed")
    override fun setFfMaxSpeed(v: Float) {
        if (v !in listOf(0f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f)) return
        d.setFloat(v, "ff_max_speed"); applyPlayback()
    }
    override fun setFfToggled(on: Boolean) { ffToggle = on; d.setBool(on, "ff_toggled"); applyPlayback() }
    override val gameSpeed: Float get() = if (romPath == null) 1f else d.floatForKey("game_speed:$romPath").takeIf { it in gameSpeeds } ?: 1f
    override fun setGameSpeed(v: Float) {
        if (romPath == null || v !in gameSpeeds) return
        d.setFloat(v, "game_speed:$romPath"); applyPlayback()
    }
    override val gameVolume: Int get() = if (d.objectForKey("game_volume") == null) 100 else d.integerForKey("game_volume").toInt().coerceIn(0, 100)
    override fun setGameVolume(percent: Int) { d.setInteger(percent.coerceIn(0, 100).toLong(), "game_volume"); applyPlayback() }
    override val gameMuted: Boolean get() = bool("game_muted", false)
    override fun setGameMuted(on: Boolean) { d.setBool(on, "game_muted"); applyPlayback() }

    override val battleInfo: Boolean get() = bool("battle_info", true)
    override fun setBattleInfo(on: Boolean) = d.setBool(on, "battle_info")
    override val oneTapMoves: Boolean get() = bool("one_tap_moves", false)
    override fun setOneTapMoves(on: Boolean) = d.setBool(on, "one_tap_moves")

    override val battleInfoFirst: Boolean get() = bool("battle_info_first", false)
    override fun setBattleInfoFirst(on: Boolean) = d.setBool(on, "battle_info_first")
    override val battleSuggestionsFirst: Boolean get() = bool("battle_suggestions_first", false)
    override fun setBattleSuggestionsFirst(on: Boolean) = d.setBool(on, "battle_suggestions_first")
    override val battleFocus: Boolean get() = bool("battle_focus", false)
    override fun setBattleFocus(on: Boolean) = d.setBool(on, "battle_focus")
    override val battleStyle: BattleStyle get() = BattleStyle.entries.firstOrNull { it.name == d.stringForKey("battle_style") } ?: BattleStyle.GEN4
    override fun setBattleStyle(style: BattleStyle) = d.setObject(style.name, "battle_style")

    // Not on the iPad yet: never shown (shownRows), so fixed values.
    override val ffMusicMode = FfMusicMode.SPED_UP
    override fun setFfMusicMode(mode: FfMusicMode) {}
    override val ffMode get() = FfMode.entries.firstOrNull { it.name == d.stringForKey("ff_mode") } ?: FfMode.SMART
    override fun setFfMode(mode: FfMode) { d.setObject(mode.name, "ff_mode"); applyPlayback() }
    override val ffMusicModes = listOf(FfMusicMode.SPED_UP)
    override val speedOptionsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_SCENE_SPEEDS)
    override val fieldReadable get() = playback()?.fieldReadable == true
    override val fieldMovesReadable get() = playback()?.fieldMovesReadable == true
    private fun sceneSpeed(key: String) = d.floatForKey("scene_speed_$key").takeIf { it in com.pokedaisy.app.companion.SCENE_SPEEDS } ?: 0f
    private fun setSceneSpeed(key: String, speed: Float) {
        if (speed !in com.pokedaisy.app.companion.SCENE_SPEEDS) return
        d.setFloat(speed, "scene_speed_$key"); applyPlayback()
    }
    override val battleSpeed get() = sceneSpeed("battle")
    override fun setBattleSpeed(v: Float) = setSceneSpeed("battle", v)
    override val textSpeed get() = sceneSpeed("text")
    override fun setTextSpeed(v: Float) = setSceneSpeed("text", v)
    override val menuSpeed get() = sceneSpeed("menu")
    override fun setMenuSpeed(v: Float) = setSceneSpeed("menu", v)
    override val bikeSpeed get() = sceneSpeed("bike")
    override fun setBikeSpeed(v: Float) = setSceneSpeed("bike", v)
    override val surfSpeed get() = sceneSpeed("surf")
    override fun setSurfSpeed(v: Float) = setSceneSpeed("surf", v)
    override val gameButtonsBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_BINDINGS)
    override val buttonGroup = GbaControls.Group.GBA
    override fun gbaControlBindings() = GbaControls.rawBindings(com.pokedaisy.app.game.IosGameInputBindings.directory)
    override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {
        GbaControls.setBindingNamed(com.pokedaisy.app.game.IosGameInputBindings.directory, btn, keyName); reloadBindings()
    }
    override fun clearGbaControlBinding(btn: GbaControls.Btn) {
        GbaControls.clearBinding(com.pokedaisy.app.game.IosGameInputBindings.directory, btn); reloadBindings()
    }
    override val hotkeyActions get() = IosGameHotkeys.ACTIONS
    override val hotkeysBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IOS_GAME_HOTKEYS)
    // Preserve the old triggers as L/R until the player opts in to emulator shortcuts.
    override val hotkeysEnabled get() = bool("hotkeys_enabled", false)
    override fun setHotkeysEnabled(on: Boolean) { d.setBool(on, "hotkeys_enabled"); reloadBindings() }
    override fun hotkeyBindings() = Hotkeys.load(com.pokedaisy.app.game.IosGameInputBindings.directory).forActions(hotkeyActions).rawBindings
    override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {
        if (action !in hotkeyActions) return
        val directory = com.pokedaisy.app.game.IosGameInputBindings.directory
        if (keyNames == null) Hotkeys.clearBinding(directory, action)
        else Hotkeys.setBindingNamed(directory, action, keyNames, takeFrom)
        reloadBindings()
    }
    override val clickSound = false
    override fun setClickSound(on: Boolean) {}
    override val statusBar = false
    override fun setStatusBar(on: Boolean) {}
    override val gameAspect = GameAspect.ORIGINAL
    override fun setGameAspect(aspect: GameAspect) {}
    override val gbaColors = false
    override fun setGbaColors(on: Boolean) {}
    override val screenFilter = ScreenFilter.NONE
    override fun setScreenFilter(filter: ScreenFilter) {}
    override val companionShaders = false
    override fun setCompanionShaders(on: Boolean) {}
    override val gridStrength = GridStrength.MEDIUM
    override fun setGridStrength(strength: GridStrength) {}
    override val smoothing = Smoothing.OFF
    override fun setSmoothing(mode: Smoothing) {}
}

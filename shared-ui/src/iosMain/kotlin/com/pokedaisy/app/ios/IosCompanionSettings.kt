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
 * app's defaults. Only supported rows are shown ([shownRows]); shaders, EXPAND and key bindings wait for later ports.
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
) : CompanionSettings {
    private val d = NSUserDefaults.standardUserDefaults

    private fun bool(key: String, default: Boolean) = if (d.objectForKey(key) == null) default else d.boolForKey(key)

    override val hasRunningGame get() = game != null
    override val shownRows: Set<String> get() = setOf("BATTLE HINTS", "FOE IVS", "TAB BAR", "DARK MODE", "THEME", "LANGUAGE", "HELP",
        "FF", "FF SPEED", "GAME SPEED", "GAME VOLUME", "MUTE GAME", "ONE-TAP MOVES", "BATTLE INFO", "BATTLE STYLE", "BATTLE VIEW", "BATTLE FOCUS", "RESUME GAMES", "TOUCH CONTROLS")
        .let { if (game == null) it - "GAME SPEED" - "FF" else it }

    override val touchRevision get() = IosTouchPreferences.revision
    override val touchStyle get() = IosTouchPreferences.style
    override fun setTouchStyle(style: TouchControlsStyle) { IosTouchPreferences.style = style }
    override val touchButtons get() = IosTouchPreferences.buttons
    override val touchSkinsSupported = false
    override val touchBadge get() = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.IPAD_TOUCH)
    override val touchStage get() = readTouchStage()
    override val touchStageInOtherOrientation get() = touchStage?.let {
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
    val ffActive get() = ffToggled || ffHeld
    fun holdFastForward(on: Boolean) { ffHeld = on; applyPlayback() }

    init { applyPlayback() }

    private fun applyPlayback() {
        val game = game ?: return
        game.speed = (if (ffActive) ffMaxSpeed else gameSpeed).toDouble()
        game.volume = gameVolume / 100f
        game.muted = gameMuted
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
    override val ffMode = FfMode.NORMAL
    override fun setFfMode(mode: FfMode) {}
    override fun gbaControlBindings(): Map<GbaControls.Btn, List<String>> = emptyMap()
    override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {}
    override val hotkeysEnabled = false
    override fun setHotkeysEnabled(on: Boolean) {}
    override fun hotkeyBindings(): Map<Hotkeys.Action, List<String>> = emptyMap()
    override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {}
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

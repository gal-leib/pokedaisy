package com.pokedaisy.app.ios

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

/**
 * The companion's SETTINGS on the iPad: what the companion itself does (BATTLE HINTS, FOE IVS, TAB BAR, DARK MODE,
 * THEME, LANGUAGE, HELP) kept in the app's defaults under the Android app's pref names, and only those rows shown
 * ([shownRows]) - the rest (FF, shaders, EXPAND, key bindings...) wait for the iPad game to have them.
 */
internal class IosCompanionSettings(
    private val romPath: String,
    /** The ROM header's game code (BPEE...), for LANGUAGE's AUTO; null for a Game Boy cart. */
    private val romCode: String?,
    private val onClose: () -> Unit,
    private val onRestart: () -> Unit,
) : CompanionSettings {
    private val d = NSUserDefaults.standardUserDefaults

    private fun bool(key: String, default: Boolean) = if (d.objectForKey(key) == null) default else d.boolForKey(key)

    override val shownRows: Set<String> = setOf("BATTLE HINTS", "FOE IVS", "TAB BAR", "DARK MODE", "THEME", "LANGUAGE", "HELP",
        "ONE-TAP MOVES", "BATTLE INFO", "BATTLE STYLE", "BATTLE VIEW", "BATTLE FOCUS")

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

    override val gameName: String get() = activeGame.displayName()
    override val romFileName: String get() = romPath.substringAfterLast('/')

    override fun closeGame() = onClose()
    override fun restartGame() = onRestart()

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
    override val ffMaxSpeed = 2f
    override fun setFfMaxSpeed(v: Float) {}
    override val ffToggled = false
    override fun setFfToggled(on: Boolean) {}
    override val ffMusicMode = FfMusicMode.STEADY
    override fun setFfMusicMode(mode: FfMusicMode) {}
    override val ffMode = FfMode.SMART
    override fun setFfMode(mode: FfMode) {}
    override val touchStyle = TouchControlsStyle()
    override fun setTouchStyle(style: TouchControlsStyle) {}
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

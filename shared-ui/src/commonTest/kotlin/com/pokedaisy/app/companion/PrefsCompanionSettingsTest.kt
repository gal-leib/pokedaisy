package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.Prefs
import kotlin.test.*

class PrefsCompanionSettingsTest {
    internal class Store : PreferenceStore {
        val data = mutableMapOf<String, Any?>()
        override fun contains(key: String) = key in data
        override fun getBoolean(key: String, default: Boolean) = data[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = data[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = data[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = data[key] as? Float ?: default
        override fun getString(key: String, default: String?) = data[key] as? String ?: default
        override fun write(values: Map<String, Any?>) { values.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v } }
    }
    internal class Settings(p: Prefs) : PrefsCompanionSettings(p) {
        val speeds = mutableListOf<Float>()
        val fieldOptions = mutableListOf<Boolean>()
        var canDiscardResume = false
        var blocked = false
        override fun onPlaybackChanged() { speeds += ffMaxSpeed }
        override fun onFieldOptionsChanged() { fieldOptions += reusableTms }
        override fun allowDisableResume() = canDiscardResume
        override fun setReusableTms(on: Boolean) { if (!blocked) super.setReusableTms(on) }
        override val gameName = "test"
        override val romFileName = "test.gba"
        override fun gbaControlBindings(): Map<GbaControls.Btn, List<String>> = emptyMap()
        override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {}
        override fun hotkeyBindings(): Map<Hotkeys.Action, List<String>> = emptyMap()
        override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {}
        override fun closeGame() {}
        override fun restartGame() {}
    }

    @Test fun effectsObserveCommittedValuesAndInvalidSpeedChoicesDoNotFire() {
        val p = Prefs(Store()); val s = Settings(p)
        s.setFfMaxSpeed(4f)
        assertEquals(4f, p.ffMaxSpeed); assertEquals(listOf(4f), s.speeds)
        s.setFfMaxSpeed(Float.NaN); s.setBattleSpeed(-1f)
        assertEquals(listOf(4f), s.speeds); assertEquals(0f, s.battleSpeed)
        s.setBattleSpeed(3f)
        assertEquals(3f, p.battleSpeed)
        p.menuSpeed = 99f
        assertEquals(0f, s.menuSpeed)
    }

    @Test fun hostVetoesProtectBothStoredValuesAndRuntimeEffects() {
        val p = Prefs(Store()); val s = Settings(p)
        s.setAutoResume(false)
        assertTrue(p.autoResume)
        s.canDiscardResume = true; s.setAutoResume(false)
        assertFalse(p.autoResume)
        s.blocked = true; s.setReusableTms(true)
        assertFalse(p.reusableTms); assertTrue(s.fieldOptions.isEmpty())
        s.blocked = false; s.setReusableTms(true)
        assertTrue(p.reusableTms); assertEquals(listOf(true), s.fieldOptions)
    }

    @Test fun bothInstalledBattleStyleFormatsKeepTheirRepresentation() {
        val androidStore = Store().apply { data["battle_style"] = BattleStyle.GEN4.id }
        val iosStore = Store().apply { data["battle_style"] = BattleStyle.GEN4.name }
        val android = Settings(Prefs(androidStore))
        val ios = Settings(Prefs(iosStore, Prefs.Defaults(battleStyleNames = true)))
        val style = BattleStyle.entries.first { it != BattleStyle.GEN4 }
        android.setBattleStyle(style); ios.setBattleStyle(style)
        assertEquals(style, android.battleStyle); assertEquals(style, ios.battleStyle)
        assertEquals(style.id, androidStore.data["battle_style"])
        assertEquals(style.name, iosStore.data["battle_style"])
    }
}

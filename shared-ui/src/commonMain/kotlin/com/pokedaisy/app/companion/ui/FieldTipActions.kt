package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.FlyAction
import com.pokedaisy.app.companion.ItemUse
import com.pokedaisy.app.companion.QuickAction
import com.pokedaisy.app.companion.TipId
import com.pokedaisy.app.companion.data.FlyWithoutMoveGame
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.i18n.tr

/** The field tips use the same eligibility checks and existing actions on either host. */
class FieldTipActions(
    private val settings: CompanionSettings,
    private val snapshot: () -> SnapshotView,
    private val items: () -> ItemUse?,
    private val fly: () -> FlyAction?,
    private val openQuickMenu: () -> Unit,
    private val quickAction: (QuickAction) -> Unit,
    private val notice: (String) -> Unit,
) {
    fun available(tip: TipId): Boolean = when (tip) {
        TipId.PC -> items()?.canPc() == true
        TipId.AUTO_RUN -> !settings.autoRun && settings.fieldReadable && !settings.autoRunBuiltIn
        TipId.WILD -> settings.wildEncountersSupported && settings.wildEncounters != 0
        TipId.REUSABLE_TMS -> settings.reusableTmsSupported && !settings.reusableTmsBuiltIn && !settings.reusableTms
        TipId.FLY -> fly()?.canFly() == true && (FlyWithoutMoveGame.active ||
            snapshot().party.any { !it.isEgg && it.moves.any { move -> move.id == FLY_MOVE } })
        else -> false
    }

    fun tryIt(tip: TipId) {
        if (!available(tip)) return
        when (tip) {
            TipId.PC -> quickAction(QuickAction.PC)
            TipId.AUTO_RUN -> { settings.setAutoRun(true); notice(tr("AUTO-RUN") + ": " + tr("ON")) }
            TipId.WILD -> quickAction(QuickAction.WILD)
            TipId.REUSABLE_TMS -> { settings.setReusableTms(true); notice(tr("REUSABLE TMS") + ": " + tr("ON")) }
            TipId.FLY -> { openQuickMenu(); quickAction(QuickAction.FLY) }
            else -> Unit
        }
    }
}

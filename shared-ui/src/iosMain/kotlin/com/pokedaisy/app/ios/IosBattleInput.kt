package com.pokedaisy.app.ios

import com.pokedaisy.app.BattleInputController
import com.pokedaisy.app.ScriptInput
import com.pokedaisy.app.battleSwitchAddrsFor
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.isGen1
import com.pokedaisy.app.companion.data.isGen2

/** The shared battle menu driver on the iPad. Called on the main/game thread, once per emulated frame. */
internal class IosBattleInput(
    private val telemetry: TelemetryStore,
    private val romCode: String?,
    private val romRev: Int,
    private val changed: () -> Unit,
    private val silent: () -> Boolean,
) : BattleInput {
    private var script = 0
    private var exclusive = false
    private var frames = 0
    private var lastState: Pair<Int, Int>? = null
    private val controller = BattleInputController(ScriptInput { bits, ownsKeys ->
        script = bits
        exclusive = ownsKeys
    }).apply {
        stateNow = { telemetry.refreshBattleInputFast()?.second }
    }

    override val busy get() = controller.isBusy
    override val menuCursor get() = controller.menuCursor
    override val gameSilent get() = silent()
    override val canSwitch get() = controller.switchAddrs != null
    val holdPicture get() = controller.holdFrame
    val quietPress get() = controller.muteGame

    fun keys(held: Int): Int {
        // A fresh state before the very first tap, then about 15 checks per game second, including during FF.
        if (frames++ % 4 == 0) {
            telemetry.refreshBattleInputFast()?.let { state ->
                controller.onState(state.first, state.second, telemetry.battleMenuCursor())
                if (state != lastState) {
                    lastState = state
                    changed()
                }
            }
        }
        controller.tick()
        return if (exclusive) script else held or script
    }

    /** Uses detected addresses only; an unmapped game keeps its own party menu. */
    fun sampled() {
        val snapshot = telemetry.snapshot.value
        controller.gen1 = snapshot.game.isGen1 || snapshot.game.isGen2
        controller.runAsksFirst = snapshot.battleOpponent.any { it.shiny }
        controller.cursorAddrs = telemetry.knownCursorAddrs()?.let { (action, move) ->
            BattleInputController.CursorAddrs(action, move,
                if (snapshot.game == GameKind.ELITE_REDUX) BattleInputController.MenuCells.ELITE_REDUX
                else BattleInputController.MenuCells.GEN3)
        }
        controller.switchAddrs = battleSwitchAddrsFor(telemetry, snapshot.game, romCode, romRev)
    }

    override fun selectAction(actionIndex: Int) = controller.selectAction(actionIndex)
    override fun selectMove(moveIndex: Int) = controller.selectMove(moveIndex)
    override fun back() = controller.back()
    override fun switchTo(personality: Long) = controller.switchTo(personality)
    fun cancel() = controller.cancel()
}

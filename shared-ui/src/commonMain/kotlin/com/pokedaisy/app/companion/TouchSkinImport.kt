package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** One import policy for native document pickers: a single skin is selected, a collection leaves the choice to you. */
object TouchSkinImport {
    data class Completed(val style: TouchControlsStyle, val notice: Pair<String, String>)

    fun complete(result: DeltaSkinStore.ImportResult, style: TouchControlsStyle): Completed {
        val added = result.added
        val single = added.singleOrNull()
        val selected = single?.skin?.system?.let { style.withSkin(it, single.id) } ?: style
        val notice = when {
            added.isEmpty() -> tk("NOT A SKIN WE CAN USE") to tr("PokeDaisy reads Delta skins for GBA and Game Boy games: {0}", result.refused.joinToString("; "))
            single != null -> tk("SKIN ADDED") to tr("{0} is on for {1} games.", single.skin.name, tr(single.skin.system?.label ?: ""))
            else -> tk("SKINS ADDED") to tr("{0} skins added. Pick one under SKINS.", added.size)
        }
        return Completed(selected, notice)
    }
}

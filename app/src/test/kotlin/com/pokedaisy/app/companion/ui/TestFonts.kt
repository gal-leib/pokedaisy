package com.pokedaisy.app.companion.ui

import android.content.Context
import com.pokedaisy.app.AndroidFonts
import com.pokedaisy.app.companion.ui.theme.FontSource

/** The screens' fonts for a screenshot test: the app's own, from [context]'s assets, as PokeDaisyApp sets them at start. */
fun installTestFonts(context: Context) {
    FontSource.current = AndroidFonts(context)
}
